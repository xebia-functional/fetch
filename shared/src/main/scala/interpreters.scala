package fetch

import cats.{MonadError, ~>}
import cats.data.StateT

import scala.collection.immutable._

import cats.std.option._
import cats.std.list._

import cats.syntax.traverse._

/**
 * An exception thrown from the interpreter when failing to perform a data fetch.
 */
case class FetchFailure[C <: DataSourceCache](env: Env[C])(implicit CC: Cache[C]) extends Throwable

trait FetchInterpreters {

  def interpreter[C <: DataSourceCache, I, E <: Env[C], M[_]](
    implicit
    MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): FetchOp ~> FetchInterpreter[M, C]#f = {
    def dedupeIds[I, A, M[_]](ids: List[I], ds: DataSource[I, A, M], cache: C) = {
      ids.distinct.filterNot(i => CC.get(cache, ds.identity(i)).isDefined)
    }

    new (FetchOp ~> FetchInterpreter[M, C]#f) {
      def apply[A](fa: FetchOp[A]): FetchInterpreter[M, C]#f[A] = {
        StateT[M, FetchEnv[C], A] { env: FetchEnv[C] =>
          fa match {
            case FetchError(e) => MM.raiseError(e)
            case Cached(a) => MM.pure((env, a))
            case Concurrent(manies) => {
              val startRound = System.nanoTime()
              val cache = env.cache
              val sources = manies.map(_.ds)
              val ids = manies.map(_.as)

              val sourcesAndIds = (sources zip ids).map({
                case (ds, ids) => (
                  ds,
                  dedupeIds[I, A, M](ids.asInstanceOf[List[I]], ds.asInstanceOf[DataSource[I, A, M]], cache)
                )
              }).filterNot({
                case (_, ids) => ids.isEmpty
              })

              if (sourcesAndIds.isEmpty)
                MM.pure((env, env.asInstanceOf[A]))
              else
                MM.flatMap(sourcesAndIds.map({
                  case (ds, as) => ds.asInstanceOf[DataSource[I, A, M]].fetch(as.asInstanceOf[List[I]])
                }).sequence)((results: List[Map[_, _]]) => {
                  val endRound = System.nanoTime()
                  val newCache = (sources zip results).foldLeft(cache)((accache, resultset) => {
                    val (ds, resultmap) = resultset
                    val tresults = resultmap.asInstanceOf[Map[I, A]]
                    val tds = ds.asInstanceOf[DataSource[I, A, M]]
                    CC.cacheResults[I, A, M](accache, tresults, tds)
                  })
                  val newEnv = env.next(
                    newCache,
                    Round(
                      cache,
                      "Concurrent",
                      ConcurrentRound(
                        sourcesAndIds.map({
                        case (ds, as) => (ds.name, as)
                      }).toMap
                      ),
                      startRound,
                      endRound
                    ),
                    Nil
                  )
                  MM.pure((newEnv, newEnv.asInstanceOf[A]))
                })
            }
            case FetchOne(id, ds) => {
              val startRound = System.nanoTime()
              val cache = env.cache
              CC.get(cache, ds.identity(id)).fold[M[(FetchEnv[C], A)]](
                MM.flatMap(ds.fetch(List(id)).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
                  val endRound = System.nanoTime()
                  res.get(id.asInstanceOf[I]).fold[M[(FetchEnv[C], A)]](
                    MM.raiseError(
                      FetchFailure(
                        env.next(
                          cache,
                          Round(cache, ds.name, OneRound(id), startRound, endRound),
                          List(id)
                        )
                      )
                    )
                  )(result => {
                      val endRound = System.nanoTime()
                      val newCache = CC.update(cache, ds.identity(id), result)
                      MM.pure(
                        (env.next(
                          newCache,
                          Round(cache, ds.name, OneRound(id), startRound, endRound),
                          List(id)
                        ), result)
                      )
                    })
                })
              )(cached => {
                  val endRound = System.nanoTime()
                  MM.pure(
                    (env.next(
                      cache,
                      Round(cache, ds.name, OneRound(id), startRound, endRound, true),
                      List(id)
                    ), cached.asInstanceOf[A])
                  )
                })
            }
            case FetchMany(ids, ds) => {
              val startRound = System.nanoTime()
              val cache = env.cache
              val oldIds = ids.distinct
              val newIds = dedupeIds[Any, Any, Any](ids, ds, cache)
              if (newIds.isEmpty)
                MM.pure(
                  (env.next(
                    cache,
                    Round(cache, ds.name, ManyRound(ids), startRound, System.nanoTime(), true),
                    newIds
                  ), ids.flatMap(id => CC.get(cache, ds.identity(id))))
                )
              else {
                MM.flatMap(ds.fetch(newIds).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
                  val endRound = System.nanoTime()
                  ids.map(i => res.get(i.asInstanceOf[I])).sequence.fold[M[(FetchEnv[C], A)]](
                    MM.raiseError(
                      FetchFailure(
                        env.next(
                          cache,
                          Round(cache, ds.name, ManyRound(ids), startRound, endRound),
                          newIds
                        )
                      )
                    )
                  )(results => {
                      val endRound = System.nanoTime()
                      val newCache = CC.cacheResults[I, A, M](cache, res, ds.asInstanceOf[DataSource[I, A, M]])
                      val someCached = oldIds.size == newIds.size
                      MM.pure(
                        (env.next(
                          newCache,
                          Round(cache, ds.name, ManyRound(ids), startRound, endRound, someCached),
                          newIds
                        ), results)
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
