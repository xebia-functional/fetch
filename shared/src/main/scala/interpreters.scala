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
import cats.std.option._
import cats.std.list._
import cats.syntax.traverse._

/**
  * An exception thrown from the interpreter when failing to perform a data fetch.
  */
case class FetchFailure(env: Env) extends Throwable

trait FetchInterpreters {

  def pendingRequests(
      requests: List[FetchRequest[_, _]], cache: DataSourceCache): List[FetchRequest[Any, Any]] = {
    requests
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
            case FetchError(e) => M.raiseError(e)
            case Cached(a)     => M.pure((env, a))
            case Concurrent(concurrentRequests) => {
                val startRound = System.nanoTime()
                val cache      = env.cache

                val requests: List[FetchRequest[Any, Any]] =
                  pendingRequests(concurrentRequests, cache)

                if (requests.isEmpty)
                  M.pure((env, cache.asInstanceOf[A]))
                else {
                  val sentRequests = M.sequence(requests.map({
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

                  M.flatMap(sentRequests)((results: List[Map[_, _]]) => {
                    val endRound = System.nanoTime()
                    val newCache = (requests zip results).foldLeft(cache)((accache, resultset) => {
                      val (req, resultmap) = resultset
                      val ds               = req.dataSource
                      val tresults         = resultmap.asInstanceOf[Map[I, A]]
                      val tds              = ds.asInstanceOf[DataSource[I, A]]
                      accache.cacheResults[I, A](tresults, tds)
                    })
                    val newEnv = env.next(
                        newCache,
                        Round(
                            cache,
                            "Concurrent",
                            ConcurrentRound(
                                requests
                                  .map({
                                    case FetchOne(a, ds)   => (ds.name, List(a))
                                    case FetchMany(as, ds) => (ds.name, as.unwrap)
                                  })
                                  .toMap
                            ),
                            startRound,
                            endRound
                        ),
                        Nil
                    )

                    val allFullfilled = (requests zip results).forall({
                      case (FetchOne(_, _), results)   => results.size == 1
                      case (FetchMany(as, _), results) => as.unwrap.size == results.size
                      case _                           => false
                    })

                    if (allFullfilled) {
                      // since user-provided caches may discard elements, we use an in-memory
                      // cache to gather these intermediate results that will be used for
                      // concurrent optimizations.
                      val cachedResults =
                        (requests zip results).foldLeft(InMemoryCache.empty)((cach, resultSet) => {
                          val (req, resultmap) = resultSet
                          val ds               = req.dataSource
                          val tresults         = resultmap.asInstanceOf[Map[I, A]]
                          val tds              = ds.asInstanceOf[DataSource[I, A]]
                          cach.cacheResults[I, A](tresults, tds).asInstanceOf[InMemoryCache]
                        })
                      M.pure((newEnv, cachedResults.asInstanceOf[A]))
                    } else {
                      M.raiseError(FetchFailure(newEnv))
                    }
                  })
                }
              }
            case FetchOne(id, ds) => {
                val startRound = System.nanoTime()
                val cache      = env.cache
                cache
                  .get[A](ds.identity(id))
                  .fold[M[(FetchEnv, A)]](
                      M.flatMap(M.runQuery(ds.fetchOne(id)))((res: Option[A]) => {
                        val endRound = System.nanoTime()
                        res.fold[M[(FetchEnv, A)]](
                            M.raiseError(
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
                          M.pure(
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
                    M.pure(
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
                         cached)
                    )
                  })
              }
            case many @ FetchMany(ids, ds) => {
                val startRound = System.nanoTime()
                val cache      = env.cache
                val newIds     = many.missingIdentities(cache)
                if (newIds.isEmpty)
                  M.pure(
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
                  M.flatMap(M.runQuery(ds
                            .asInstanceOf[DataSource[I, A]]
                            .fetchMany(NonEmptyList(newIds(0).asInstanceOf[I],
                                                    newIds.tail.asInstanceOf[List[I]]))))(
                      (res: Map[I, A]) => {
                    val endRound = System.nanoTime()
                    ids.unwrap
                      .map(i => res.get(i.asInstanceOf[I]))
                      .sequence
                      .fold[M[(FetchEnv, A)]](
                          M.raiseError(
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
                        M.pure(
                            (env.next(
                                 newCache,
                                 Round(cache,
                                       ds.name,
                                       ManyRound(ids.unwrap),
                                       startRound,
                                       endRound,
                                       results.size < ids.unwrap.distinct.size),
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
