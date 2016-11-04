/*
 * Copyright 2016 47 Degrees, LLC. <http://www.47deg.com>
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

import scala.collection.immutable._

import cats.{MonadError, ~>}
import cats.data.{OptionT, NonEmptyList, StateT, Validated, XorT}
import cats.instances.option._
import cats.instances.list._
import cats.instances.map._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.traverse._
import cats.syntax.validated._

trait FetchInterpreters {
  def pendingQueries(
      queries: List[FetchQuery[_, _]],
      cache: DataSourceCache
  ): List[FetchQuery[Any, Any]] =
    queries.mapFilter { query =>
      val dsAny = query.dataSource.castDS[Any, Any]
      NonEmptyList.fromList(query.missingIdentities(cache)).map {
        case NonEmptyList(id, Nil) => FetchOne(id, dsAny)
        case ids                   => FetchMany(ids.widen[Any], dsAny)
      }
    }

  def interpreter[I, M[_]](
      implicit M: FetchMonadError[M]
  ): FetchOp ~> FetchInterpreter[M]#f = {
    new (FetchOp ~> FetchInterpreter[M]#f) {
      def apply[A](fa: FetchOp[A]): FetchInterpreter[M]#f[A] =
        StateT[M, FetchEnv, A] { env: FetchEnv =>
          fa match {
            case Thrown(e)              => M.raiseError(UnhandledException(e))
            case Fetched(a)             => M.pure((env, a))
            case one @ FetchOne(_, _)   => processOne(one, env)
            case many @ FetchMany(_, _) => processMany(many, env)
            case conc @ Concurrent(_)   => processConcurrent(conc, env)
          }
        }
    }
  }

  private[this] def processOne[M[_], A](
      one: FetchOne[Any, A],
      env: FetchEnv
  )(
      implicit M: FetchMonadError[M]
  ): M[(FetchEnv, A)] = {
    val FetchOne(id, ds) = one
    val startRound       = System.nanoTime()
    env.cache
      .get[A](ds.identity(id))
      .fold[M[(FetchEnv, A)]](
          M.runQuery(ds.fetchOne(id)).flatMap { (res: Option[A]) =>
            val endRound = System.nanoTime()
            res.fold[M[(FetchEnv, A)]] {
              // could not get result from datasource
              M.raiseError(NotFound(env, one))
            } { result =>
              // found result (and update cache)
              val newCache = env.cache.update(ds.identity(id), result)
              val round    = Round(env.cache, one, result, startRound, endRound)
              M.pure(env.evolve(round, newCache) -> result)
            }
          }
      ) { cached =>
        // get result from cache
        M.pure(env -> cached)
      }
  }

  private[this] def processMany[M[_], A](
      many: FetchMany[Any, Any],
      env: FetchEnv
  )(
      implicit M: FetchMonadError[M],
      ev: List[Any] =:= A
  ): M[(FetchEnv, A)] = {
    val ids        = many.as
    val ds         = many.ds //.castDS[Any, Any]
    val startRound = System.nanoTime()
    val cache      = env.cache
    val newIds     = many.missingIdentities(cache)

    (for {
      newIdsNel <- XorT.fromXor[M] {
                    NonEmptyList.fromList(newIds).toRightXor {
                      // no missing ids, get all from cache
                      val cachedResults = ids.toList.mapFilter(id => cache.get(ds.identity(id)))
                      env -> cachedResults
                    }
                  }
      resMap <- XorT.right(M.runQuery(ds.fetchMany(newIdsNel)))
      results <- ids.toList
                  .traverseU(id => resMap.get(id).toValidNel(id))
                  .fold[XorT[M, (FetchEnv, List[Any]), List[Any]]]({ missingIds =>
                    // not all identities could be found
                    val map = Map(ds.name -> missingIds.toList)
                    XorT.left(M.raiseError(MissingIdentities(env, map)))
                  }, XorT.pure)
    } yield {
      // found all results (and update cache)
      val endRound = System.nanoTime()
      val newCache = cache.cacheResults(resMap, ds)
      val round    = Round(cache, many, results, startRound, endRound)
      env.evolve(round, newCache) -> results
    }).merge.map { case (env, l) => (env, ev(l)) } // A =:= List[Any]
  }

  private[this] def processConcurrent[M[_]](
      concurrent: Concurrent,
      env: FetchEnv
  )(
      implicit M: FetchMonadError[M]
  ): M[(FetchEnv, DataSourceCache)] = {
    def runFetchQueryAsMap[I, A](op: FetchQuery[I, A]): M[Map[I, A]] =
      op match {
        case FetchOne(a, ds) =>
          OptionT(M.runQuery(ds.fetchOne(a))).map(r => Map(a -> r)).getOrElse(Map.empty)
        case FetchMany(as, ds) => M.runQuery(ds.fetchMany(as))
      }

    type MissingIdentitiesMap = Map[DataSourceName, List[Any]]

    // Give for a list of queries and result(maps) all the missing identities
    def missingIdentitiesOrAllFulfilled(
        queriesAndResults: List[(FetchQuery[Any, Any], Map[Any, Any])]
    ): Validated[MissingIdentitiesMap, Unit] =
      queriesAndResults.traverseU_ {
        case (FetchOne(id, ds), resultMap) =>
          Either.cond(resultMap.size == 1, (), Map(ds.name -> List(id))).toValidated
        case (FetchMany(as, ds), resultMap) =>
          Either
            .cond(as.toList.size == resultMap.size,
                  (),
                  Map(ds.name -> as.toList.filter(id => resultMap.get(id).isEmpty)))
            .toValidated
        case _ =>
          Map.empty[DataSourceName, List[Any]].invalid
      }

    val startRound = System.nanoTime()
    val cache      = env.cache

    val queries: List[FetchQuery[Any, Any]] = pendingQueries(concurrent.as, cache)

    if (queries.isEmpty)
      // there are no pending queries
      M.pure((env, cache))
    else {
      val sentRequests = queries.traverse(r => runFetchQueryAsMap(r))

      sentRequests.flatMap { results =>
        val endRound          = System.nanoTime()
        val queriesAndResults = queries zip results

        val missingOrFulfilled = missingIdentitiesOrAllFulfilled(queriesAndResults)

        missingOrFulfilled.fold({ missingIds =>
          // not all identiies were found
          M.raiseError(MissingIdentities(env, missingIds))
        }, { _ =>
          // results found for all identities
          val round = Round(cache, Concurrent(queries), results, startRound, endRound)

          // since user-provided caches may discard elements, we use an in-memory
          // cache to gather these intermediate results that will be used for
          // concurrent optimizations.
          val (newCache, cachedResults) =
            queriesAndResults.foldLeft((cache, InMemoryCache.empty)) {
              case ((userCache, internCache), (req, resultMap)) =>
                val anyMap = resultMap.asInstanceOf[Map[Any, Any]]
                val anyDS  = req.dataSource.castDS[Any, Any]
                (userCache.cacheResults(anyMap, anyDS),
                 internCache.cacheResults(anyMap, anyDS).asInstanceOf[InMemoryCache])
            }

          M.pure((env.evolve(round, newCache), cachedResults))
        })
      }
    }
  }
}
