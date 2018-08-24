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

import cats.effect.Effect

import cats.{~>, Monad, MonadError}
import cats.data.{Ior, IorNel, NonEmptyList, StateT, Validated}
import cats.free.Free
import cats.implicits._

object CoreInterpreter {

  // def apply[M[_]](implicit M: Effect[M]): FetchOp ~> FetchInterpreter[M]#f =
  //   new (FetchOp ~> FetchInterpreter[M]#f) {
  //     def apply[A](fa: FetchOp[A]): FetchInterpreter[M]#f[A] =
  //       fa match {
  //         case Join(fl, fr) =>
  //           Monad[FetchInterpreter[M]#f].tuple2(
  //             fl.foldMap[FetchInterpreter[M]#f](this),
  //             fr.foldMap[FetchInterpreter[M]#f](this))

  //         case other =>
  //           StateT[M, FetchEnv, A] { env: FetchEnv =>
  //             other match {
  //               case Thrown(e)              => M.raiseError(UnhandledException(e))
  //               case one @ FetchOne(_, _)   => processOne(one, env)
  //               case many @ FetchMany(_, _) => processMany(many, env)
  //               case conc @ Concurrent(_)   => processConcurrent(conc, env)
  //               case Join(_, _)             => throw new Exception("join already handled")
  //             }
  //           }
  //       }
  //   }

  // private[this] def processOne[M[_], A](
  //     one: FetchOne[Any, A, M],
  //     env: FetchEnv
  // )(
  //   implicit M: Effect[M]
  // ): M[(FetchEnv, A)] = {
  //   val FetchOne(id, ds) = one
  //   val startRound       = System.nanoTime()

  //   ds.fetchOne[M](id).flatMap { (res: Option[A]) =>
  //     val endRound = System.nanoTime()
  //     res.fold[M[(FetchEnv, A)]] {
  //       // could not get result from datasource
  //       M.raiseError(NotFound(one))
  //     } { result =>
  //       // found result (and update cache)
  //       val round    = Round(one, result, startRound, endRound)
  //       M.pure(env.evolve(round) -> result)
  //     }
  //   }
  // }

  // private[this] def processMany[M[_], A](
  //     many: FetchMany[Any, Any, M],
  //     env: FetchEnv
  // )(
  //   implicit M: Effect[M],
  //   ev: List[Any] =:= A
  // ): M[(FetchEnv, A)] = {
  //   val FetchMany(ids, ds) = many
  //   val startRound         = System.nanoTime()
  //   val cache              = env.cache

  //   def fetchIds(ids: NonEmptyList[Any], cachedResults: Map[Any, Any]): M[Map[Any, Any]] =
  //     ds.fetchMany[M](ids).map(_ ++ cachedResults)

  //   def getResultList(resMap: Map[Any, Any]): M[(FetchEnv, List[Any])] =
  //     ids
  //       .traverse(id => resMap.get(id).toValidNel(id))
  //       .fold[M[(FetchEnv, List[Any])]](
  //         missingIds => M.raiseError(MissingIdentities(Map(ds.name -> missingIds.toList))),
  //         results => {
  //           val endRound = System.nanoTime()
  //           val round    = Round(many, results, startRound, endRound)
  //           M.pure(env.evolve(round) -> results.toList)
  //         }
  //       )

  //   fetchIds(uncachedIds, Map.empty).flatMap(getResultList)
  // }

  // private[this] def processConcurrent[M[_]](
  //     concurrent: Concurrent,
  //     env: FetchEnv
  // )(
  //   implicit M: Effect[M]
  // ): M[(FetchEnv, InMemoryCache)] = {

  //   val startRound = System.nanoTime()
  //   val cache      = env.cache

  //   type AnyQuery  = FetchQuery[Any, Any]
  //   type AnyResult = Map[Any, Any]

  //   def executeQueries(queries: NonEmptyList[FetchQuery[Any, Any]]): M[
  //     (Long, NonEmptyList[(AnyQuery, AnyResult)])] =
  //     queries.traverse(runFetchQueryAsMap).flatMap { results =>
  //       val endRound = System.nanoTime()
  //       M.fromEither(
  //         errorOrAllFound(queries, results)
  //         .map(zipped => (endRound, zipped.widen[(AnyQuery, AnyResult)])))
  //     }

  //   def runFetchQueryAsMap[I, A](op: FetchQuery[I, A]): M[Map[I, A]] =
  //     op match {
  //       case FetchOne(a, ds) =>
  //         ds.fetchOne[M](a).map(_.fold(Map.empty[I, A])(r => Map(a -> r)))
  //       case FetchMany(as, ds) =>
  //         ds.fetchMany[M](as)
  //     }

  //   // return a MissingIdentities error or a NonEmptyList with all
  //   // the query - result pairs
  //   def errorOrAllFound(
  //       queries: NonEmptyList[AnyQuery],
  //       results: NonEmptyList[AnyResult]
  //   ): Either[MissingIdentities, NonEmptyList[(AnyQuery, AnyResult)]] = {
  //     val queriesAndResults = NonEmptyList.fromListUnsafe(queries.toList zip results.toList)

  //     val missingIdentitiesOrOK: Validated[Map[DataSourceName, List[Any]], Unit] =
  //       queriesAndResults.traverse_ {
  //         case (FetchOne(id, ds), resultMap) =>
  //           Either.cond(resultMap.size == 1, (), Map(ds.name -> List(id))).toValidated
  //         case (FetchMany(as, ds), resultMap) =>
  //           Either
  //             .cond(
  //               as.toList.size == resultMap.size,
  //               (),
  //               Map(ds.name -> as.toList.filter(id => resultMap.get(id).isEmpty)))
  //             .toValidated
  //         case _ =>
  //           Map.empty[DataSourceName, List[Any]].invalid
  //       }

  //     missingIdentitiesOrOK
  //       .as(queriesAndResults)
  //       .leftMap(missingIds => MissingIdentities(missingIds))
  //       .toEither
  //   }

  //   def executeQueriesAndEndRound(
  //       queries: NonEmptyList[AnyQuery],
  //       cachedResults: Map[DataSourceIdentity, Any]
  //   ): M[(FetchEnv, InMemoryCache)] =
  //     executeQueries(queries).map {
  //       case (endRound, queriesAndResults) =>
  //         val queries = queriesAndResults.map(_._1)
  //         val results = queriesAndResults.map(_._2)
  //         val round   = Round(cache, Concurrent(queries), results, startRound, endRound)

  //         // since user-provided caches may discard elements, we use an in-memory
  //         // cache to gather these intermediate results that will be used for
  //         // concurrent optimizations.
  //         val (newCache, inmcache) = queriesAndResults.foldLeft((cache, InMemoryCache.empty)) {
  //           case ((userCache, internCache), (req, resultMap)) =>
  //             val anyMap = resultMap.asInstanceOf[AnyResult]
  //             val anyDS  = req.dataSource.castDS[Any, Any]
  //             (
  //               userCache.cacheResults(anyMap, anyDS),
  //               internCache.cacheResults(anyMap, anyDS).asInstanceOf[InMemoryCache])
  //         }

  //         (env.evolve(round, newCache), inmcache |+| InMemoryCache(cachedResults))
  //     }

  //   executeQueriesAndEndRound(queries, Map.empty)
  // }

  // private[this] def queriesIorCachedResults[I, A](
  //     queries: NonEmptyList[FetchQuery[I, A]],
  //     cache: DataSourceCache
  // ): IorNel[FetchQuery[I, A], NonEmptyList[(DataSourceIdentity, A)]] = {
  //   def idOrResult[I, A](id: I, ds: DataSource[I, A]): Either[I, (DataSourceIdentity, A)] = {
  //     val identity = ds.identity(id)
  //     cache.get[A](identity).map(identity -> _).toRight(id)
  //   }

  //   queries.reduceMap[IorNel[FetchQuery[I, A], NonEmptyList[(DataSourceIdentity, A)]]] { query =>
  //     val ds = query.dataSource
  //     query.identities
  //       .reduceMap(id =>
  //         idOrResult(id, ds).bimap(NonEmptyList.one, NonEmptyList.one).toIor)
  //       .leftMap {
  //         case NonEmptyList(id, Nil) => NonEmptyList.one(FetchOne(id, ds))
  //         case ids                   => NonEmptyList.one(FetchMany(ids, ds))
  //       }
  //   }
  // }

}
