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
import cats.data.{StateT, NonEmptyList}
import cats.instances.option._
import cats.instances.list._
import cats.syntax.traverse._

trait FetchInterpreters {
  def pendingQueries(
      queries: List[FetchQuery[_, _]], cache: DataSourceCache): List[FetchQuery[Any, Any]] = {

    queries
      .filterNot(_.fullfilledBy(cache))
      .map(req => {
        (req.dataSource, req.missingIdentities(cache))
      })
      .collect({
        case (ds, ids) if ids.size == 1 =>
          FetchOne[Any, Any](ids.head, ds.asInstanceOf[DataSource[Any, Any]])
        case (ds, ids) if ids.size > 1 =>
          FetchMany[Any, Any](
              NonEmptyList(ids(0), ids.tail), ds.asInstanceOf[DataSource[Any, Any]])
      })
  }

  def interpreter[I, M[_]](
      implicit M: FetchMonadError[M]
  ): FetchOp ~> FetchInterpreter[M]#f = {
    new (FetchOp ~> FetchInterpreter[M]#f) {
      def apply[A](fa: FetchOp[A]): FetchInterpreter[M]#f[A] = {
        StateT[M, FetchEnv, A] { env: FetchEnv =>
          fa match {
            case Thrown(e)  => M.raiseError(UnhandledException(e))
            case Fetched(a) => M.pure((env, a))
            case one @ FetchOne(id, ds) => {
                val startRound = System.nanoTime()
                val cache      = env.cache

                cache
                  .get[A](ds.identity(id))
                  .fold[M[(FetchEnv, A)]](
                      M.flatMap(M.runQuery(ds.fetchOne(id)))((res: Option[A]) => {
                        val endRound = System.nanoTime()
                        res.fold[M[(FetchEnv, A)]](
                            M.raiseError(
                                NotFound(env, one)
                            )
                        )(result => {
                          val endRound = System.nanoTime()
                          val newCache = cache.update(ds.identity(id), result)
                          val round    = Round(cache, one, result, startRound, endRound)
                          M.pure(env.evolve(round, newCache) -> result)
                        })
                      })
                  )(cached => {
                    val endRound = System.nanoTime()
                    M.pure(env -> cached)
                  })
              }
            case many @ FetchMany(ids, ds) => {
                val startRound = System.nanoTime()
                val cache      = env.cache
                val newIds     = many.missingIdentities(cache)
                val result     = ids.toList.flatMap(id => cache.get(ds.identity(id)))

                if (newIds.isEmpty)
                  M.pure(env -> result)
                else {
                  M.flatMap(M.runQuery(ds
                            .asInstanceOf[DataSource[I, A]]
                            .fetchMany(NonEmptyList(newIds(0).asInstanceOf[I],
                                                    newIds.tail.asInstanceOf[List[I]]))))(
                      (res: Map[I, A]) => {
                    val endRound = System.nanoTime()

                    ids.toList
                      .map(i => res.get(i.asInstanceOf[I]))
                      .sequence
                      .fold[M[(FetchEnv, A)]]({
                        val missingIdentities = ids.toList
                          .map(i => i.asInstanceOf[I] -> res.get(i.asInstanceOf[I]))
                          .collect({
                            case (i, None) => i
                          })
                        M.raiseError(
                            MissingIdentities(env, Map(ds.name -> missingIdentities))
                        )
                      })(results => {
                        val endRound = System.nanoTime()
                        val newCache =
                          cache.cacheResults[I, A](res, ds.asInstanceOf[DataSource[I, A]])
                        val round = Round(cache, many, results, startRound, endRound)
                        M.pure(env.evolve(round, newCache) -> results.asInstanceOf[A])
                      })
                  })
                }
              }

            case conc @ Concurrent(concurrentQueries) => {
                val startRound = System.nanoTime()
                val cache      = env.cache

                val queries: List[FetchQuery[Any, Any]] = pendingQueries(concurrentQueries, cache)

                if (queries.isEmpty)
                  M.pure((env, cache.asInstanceOf[A]))
                else {
                  val sentQueries = M.sequence(queries.map({
                    case FetchOne(a, ds) => {
                        val ident = a.asInstanceOf[I]
                        val task  = M.runQuery(ds.asInstanceOf[DataSource[I, A]].fetchOne(ident))
                        M.map(task)((r: Option[A]) =>
                              r.fold(Map.empty[I, A])((result: A) => Map(ident -> result)))
                      }
                    case FetchMany(as, ds) =>
                      M.runQuery(ds
                            .asInstanceOf[DataSource[I, A]]
                            .fetchMany(as.asInstanceOf[NonEmptyList[I]]))
                  }))

                  M.flatMap(sentQueries)((results: List[Map[_, _]]) => {
                    val endRound = System.nanoTime()
                    val newCache = (queries zip results).foldLeft(cache)((accache, resultset) => {
                      val (req, resultmap) = resultset
                      val ds               = req.dataSource
                      val tresults         = resultmap.asInstanceOf[Map[I, A]]
                      val tds              = ds.asInstanceOf[DataSource[I, A]]
                      accache.cacheResults[I, A](tresults, tds)
                    })

                    val allFullfilled = (queries zip results).forall({
                      case (FetchOne(_, _), results)   => results.size == 1
                      case (FetchMany(as, _), results) => as.toList.size == results.size
                      case _                           => false
                    })

                    if (allFullfilled) {
                      val round = Round(
                          cache,
                          Concurrent(queries),
                          results,
                          startRound,
                          endRound
                      )
                      val newEnv = env.evolve(round, newCache)
                      // since user-provided caches may discard elements, we use an in-memory
                      // cache to gather these intermediate results that will be used for
                      // concurrent optimizations.
                      val cachedResults =
                        (queries zip results).foldLeft(InMemoryCache.empty)((cach, resultSet) => {
                          val (req, resultmap) = resultSet
                          val ds               = req.dataSource
                          val tresults         = resultmap.asInstanceOf[Map[I, A]]
                          val tds              = ds.asInstanceOf[DataSource[I, A]]
                          cach.cacheResults[I, A](tresults, tds).asInstanceOf[InMemoryCache]
                        })

                      M.pure((newEnv, cachedResults.asInstanceOf[A]))
                    } else {
                      val missingIdentities: Map[DataSourceName, List[Any]] = (queries zip results)
                        .collect({
                          case (FetchOne(id, ds), results) if results.size != 1 =>
                            ds.name -> List(id)
                          case (FetchMany(as, ds), results) if results.size != as.toList.size =>
                            ds.name -> as.toList.collect({
                              case i if !results.asInstanceOf[Map[Any, Any]].get(i).isDefined => i
                            })
                        })
                        .toMap
                      M.raiseError(
                          MissingIdentities(env, missingIdentities)
                      )
                    }
                  })
                }
              }
          }
        }
      }
    }
  }
}
