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

import monix.eval.Task

import cats.{MonadError, ~>}
import cats.data.{StateT, NonEmptyList}
import cats.std.option._
import cats.std.list._
import cats.syntax.traverse._

/**
  * An exception thrown from the interpreter when failing to perform a data fetch.
  */
case class FetchFailure(env: Env) extends Throwable

trait FetchInterpreters {

  def interpreter[I]: FetchOp ~> FetchInterpreter = {
    def dedupeIds[I, A](ids: NonEmptyList[I], ds: DataSource[I, A], cache: DataSourceCache) = {
      ids.unwrap.distinct.filterNot(i => cache.get(ds.identity(i)).isDefined)
    }

    new (FetchOp ~> FetchInterpreter) {
      def apply[A](fa: FetchOp[A]): FetchInterpreter[A] = {
        StateT[Task, FetchEnv, A] { env: FetchEnv =>
          fa match {
            case FetchError(e) => Task.raiseError(e)
            case Cached(a)     => Task.pure((env, a))
            case Concurrent(manies) => {
                val startRound = System.nanoTime()
                val cache      = env.cache
                val sources    = manies.map(_.ds)
                val ids        = manies.map(_.as)

                val sourcesAndIds = (sources zip ids)
                  .map({
                    case (ds, ids) =>
                      (
                          ds,
                          dedupeIds[I, A](ids.asInstanceOf[NonEmptyList[I]],
                                          ds.asInstanceOf[DataSource[I, A]],
                                          cache)
                      )
                  })
                  .collect({
                    case (ds, ids) if !ids.isEmpty => (ds, NonEmptyList(ids(0), ids.tail))
                  })

                if (sourcesAndIds.isEmpty)
                  Task.pure((env, env.cache.asInstanceOf[A]))
                else
                  Task
                    .sequence(sourcesAndIds.map({
                      case (ds, as) =>
                        ds.asInstanceOf[DataSource[I, A]]
                          .fetchMany(as.asInstanceOf[NonEmptyList[I]])
                    }))
                    .flatMap((results: List[Map[_, _]]) => {
                      val endRound = System.nanoTime()
                      val newCache =
                        (sources zip results).foldLeft(cache)((accache, resultset) => {
                          val (ds, resultmap) = resultset
                          val tresults        = resultmap.asInstanceOf[Map[I, A]]
                          val tds             = ds.asInstanceOf[DataSource[I, A]]
                          accache.cacheResults[I, A](tresults, tds)
                        })
                      val newEnv = env.next(
                          newCache,
                          Round(
                              cache,
                              "Concurrent",
                              ConcurrentRound(
                                  sourcesAndIds
                                    .map({
                                      case (ds, as) => (ds.name, as.unwrap)
                                    })
                                    .toMap
                              ),
                              startRound,
                              endRound
                          ),
                          Nil
                      )

                      val allFetched = (sourcesAndIds zip results).forall({
                        case ((_, theIds), results) => theIds.unwrap.size == results.size
                        case _                      => false
                      })

                      if (allFetched) {
                        // since user-provided caches may discard elements, we use an in-memory
                        // cache to gather these intermediate results that will be used for
                        // concurrent optimizations.
                        val cachedResults = (sources zip results).foldLeft(InMemoryCache.empty)(
                            (cach, resultSet) => {
                          val (ds, resultmap) = resultSet
                          val tresults        = resultmap.asInstanceOf[Map[I, A]]
                          val tds             = ds.asInstanceOf[DataSource[I, A]]
                          cach.cacheResults[I, A](tresults, tds).asInstanceOf[InMemoryCache]
                        })
                        Task.pure((newEnv, cachedResults.asInstanceOf[A]))
                      } else {
                        Task.raiseError(FetchFailure(newEnv))
                      }
                    })
              }
            case FetchOne(id, ds) => {
                val startRound = System.nanoTime()
                val cache      = env.cache
                cache
                  .get(ds.identity(id))
                  .fold[Task[(FetchEnv, A)]](
                      ds.fetchOne(id)
                        .flatMap((res: Option[A]) => {
                          val endRound = System.nanoTime()
                          res.fold[Task[(FetchEnv, A)]](
                              Task.raiseError(
                                  FetchFailure(
                                      env.next(
                                          cache,
                                          Round(cache,
                                                ds.name,
                                                OneRound(id),
                                                startRound,
                                                endRound),
                                          List(id)
                                      )
                                  )
                              )
                          )(result => {
                            val endRound = System.nanoTime()
                            val newCache = cache.update(ds.identity(id), result)
                            Task.pure(
                                (env.next(
                                     newCache,
                                     Round(cache,
                                           ds.name,
                                           OneRound(id),
                                           startRound,
                                           endRound),
                                     List(id)
                                 ),
                                 result)
                            )
                          })
                        })
                  )(cached => {
                    val endRound = System.nanoTime()
                    Task.pure(
                        (env.next(
                             cache,
                             Round(cache,
                                   ds.name,
                                   OneRound(id),
                                   startRound,
                                   endRound,
                                   true),
                             List(id)
                         ),
                         cached.asInstanceOf[A])
                    )
                  })
              }
            case FetchMany(ids, ds) => {
                val startRound = System.nanoTime()
                val cache      = env.cache
                val oldIds     = ids.unwrap.distinct
                val newIds     = dedupeIds[Any, Any](ids, ds, cache)
                if (newIds.isEmpty)
                  Task.pure(
                      (env.next(
                           cache,
                           Round(cache,
                                 ds.name,
                                 ManyRound(ids.unwrap),
                                 startRound,
                                 System.nanoTime(),
                                 true),
                           newIds
                       ),
                       ids.unwrap.flatMap(id => cache.get(ds.identity(id))))
                  )
                else {
                  ds.asInstanceOf[DataSource[I, A]]
                    .fetchMany(
                        NonEmptyList(newIds(0).asInstanceOf[I], newIds.tail.asInstanceOf[List[I]]))
                    .flatMap((res: Map[I, A]) => {
                      val endRound = System.nanoTime()
                      ids.unwrap
                        .map(i => res.get(i.asInstanceOf[I]))
                        .sequence
                        .fold[Task[(FetchEnv, A)]](
                            Task.raiseError(
                                FetchFailure(
                                    env.next(
                                        cache,
                                        Round(cache,
                                              ds.name,
                                              ManyRound(ids.unwrap),
                                              startRound,
                                              endRound),
                                        newIds
                                    )
                                )
                            )
                        )(results => {
                          val endRound = System.nanoTime()
                          val newCache =
                            cache.cacheResults[I, A](res, ds.asInstanceOf[DataSource[I, A]])
                          val someCached = oldIds.size == newIds.size
                          Task.pure(
                              (env.next(
                                   newCache,
                                   Round(cache,
                                         ds.name,
                                         ManyRound(ids.unwrap),
                                         startRound,
                                         endRound,
                                         someCached),
                                   newIds
                               ),
                               results)
                          )
                        })
                    })
                }
              }
          }
        }
      }
    }
  }
}
