/*
 * Copyright 2016-2018 47 Degrees, LLC. <http://www.47deg.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fetch
package interpreters

import scala.collection.immutable._
import scala.annotation.tailrec

import cats.{~>, Id}
import cats.data.{EitherK, NonEmptyList}
import cats.free.Free
import cats.implicits._

import cats.free.fetch.FreeExt

object ParallelJoinPhase {
  lazy val apply: FetchOp ~> Fetch = {
    λ[FetchOp ~> Fetch] {
      case join @ Join(fl, fr) => parallelJoin(Free.liftF[FetchOp, (Any, Any)](join))
      case other => Free.liftF(other)
    }
  }

  private[this] def parallelJoin[A, B](
      fetchJoin: Fetch[(A, B)],
      oldCache: InMemoryCache = InMemoryCache.empty
  ): Fetch[(A, B)] =
    combineQueries(independentQueries(fetchJoin)).asInstanceOf[List[FetchQuery[Any, Any]]].toNel match {
      case None => fetchJoin
      case Some(queriesNel) =>
        Free.liftF(Concurrent(queriesNel)).flatMap { newCache =>
          val cache = newCache |+| oldCache
          parallelJoin(simplify(cache)(fetchJoin), cache)
        }
    }

  private[this] def independentQueries(f: Fetch[_]): List[FetchQuery[_, _]] =
    independentQueriesRec(f, Nil)

  @tailrec
  private[this] def independentQueriesRec(
      f: Fetch[_],
      acc: List[FetchQuery[_, _]]
  ): List[FetchQuery[_, _]] =
    // we need the `.step` below to ignore pure values when we search for
    // independent queries, but this also has the consequence that pure
    // values can be executed multiple times.
    //  eg : Fetch.pure(5).map { i => println("hello"); i * 2 }
    FreeExt.getSuspend(f.step) match {
      case Some(Join(ffl, ffr)) =>
        val nacc = independentQueries(ffl)
        independentQueriesRec(ffr, acc ++ nacc)
      case Some(one @ FetchOne(_, _))   => one :: acc
      case Some(many @ FetchMany(_, _)) => many :: acc
      case _                            => acc
    }

  /**
   * Use a `DataSourceCache` to optimize a `FetchOp`.
   * If the cache contains all the fetch identities, the fetch doesn't need to be
   * executed and can be replaced by cached results.
   */
  private[this] def simplify(cache: InMemoryCache): Fetch ~> Fetch = {
    val interpreter: FetchOp ~> EitherK[FetchOp, Id, ?] =
      new (FetchOp ~> EitherK[FetchOp, Id, ?]) {
        def apply[X](fetchOp: FetchOp[X]): EitherK[FetchOp, Id, X] = fetchOp match {
          case one @ FetchOne(id, ds) =>
            EitherK[FetchOp, Id, X](cache.get[X](ds.identity(id)).toRight(one))
          case many @ FetchMany(ids, ds) =>
            val fetched = ids.traverse(id => cache.get(ds.identity(id)))
            EitherK[FetchOp, Id, X](fetched.map(_.toList).toRight(many))
          case join @ Join(fl, fr) =>
            val sfl      = FreeExt.modifySuspend(fl)(this)
            val sfr      = FreeExt.modifySuspend(fr)(this)
            val optTuple = (FreeExt.getPure(sfl), FreeExt.getPure(sfr)).tupled
            EitherK[FetchOp, Id, X](optTuple.toRight(Join(sfl, sfr)))
          case other =>
            EitherK.leftc(other)
        }
      }

    λ[Fetch ~> Fetch](FreeExt.modifySuspend(_)(interpreter))
  }

  /**
   * Combine multiple queries so the resulting `List` only contains one `FetchQuery`
   * per `DataSource`.
   */
  private[this] def combineQueries(qs: List[FetchQuery[_, _]]): List[FetchQuery[_, _]] =
    qs.foldMap[Map[DataSource[_, _], NonEmptyList[Any]]] {
        case FetchOne(id, ds)   => Map(ds -> NonEmptyList.one(id))
        case FetchMany(ids, ds) => Map(ds -> ids)
      }
      .mapValues { nel =>
        // workaround because NEL[Any].distinct would need Order[Any]
        nel.unsafeListOp(_.distinct)
      }
      .toList
      .map {
        case (ds, NonEmptyList(id, Nil)) => FetchOne(id, ds.castDS[Any, Any])
        case (ds, ids)                   => FetchMany(ids, ds.castDS[Any, Any])
      }
}
