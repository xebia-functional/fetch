package fetch

import scala.collection.immutable.Seq
import scala.collection.immutable.Queue

import fetch.types._

import cats.{ MonadError, ~>, Eval }
import cats.data.{ State, StateT }
import cats.std.option._
import cats.std.list._
import cats.syntax.cartesian._
import cats.syntax.traverse._
import cats.free.{ Free }

trait DataSource[I, A, M[_]] {
  def name: DataSourceName = this.toString
  def identity(i: I): DataSourceIdentity = (name, i)
  def fetchMany(ids: List[I]): M[Map[I, A]]
}

trait DataSourceCache

trait Cache[T <: DataSourceCache]{
  def update[I, A](c: T, k: DataSourceIdentity, v: A): T
  def get[I](c: T, k: DataSourceIdentity): Option[Any]
}

trait Env[C <: DataSourceCache]{
  def cache: C
  def rounds: Seq[Round]

  def printRounds = {
    rounds.foreach({
      case r@Round(cache, identity, ids, _, _, cached) => {
        println(">" * 100)
        // info
        if (cached) {
          println("[" + identity + "][" + ids + "] fetched from the cache in " + r.duration  + " miliseconds")
        } else {
          println("[" + identity + "][" + ids + "] took " + r.duration + " miliseconds to fetch")
        }
        // cache
        println("[CACHE]")
        println(cache)
      }
    })
  }

  def next(
    newCache: C,
    newRound: Round,
    newIds: List[Any]
  ): Env[C]
}

case class Round(
  cache: DataSourceCache,
  ds: DataSourceName,
  kind: RoundKind,
  startRound: Long,
  endRound: Long,
  cached: Boolean = false
) {
  def duration: Double = (endRound - startRound) / 1e6
}

sealed trait RoundKind
final case class OneRound(id: Any) extends RoundKind
final case class ManyRound(ids: List[Any]) extends RoundKind

case class FetchEnv[C <: DataSourceCache](
  cache: C,
  ids: List[Any] = Nil,
  rounds: Queue[Round] = Queue.empty
) extends Env[C]{
  def next(
    newCache: C,
    newRound: Round,
    newIds: List[Any]
  ): FetchEnv[C] =
    copy(cache = newCache, rounds = rounds :+ newRound, ids = newIds)
}

case class FetchFailure[C <: DataSourceCache](env: Env[C])(
  implicit CC: Cache[C]
) extends Throwable

object algebra {
  sealed abstract class FetchOp[A] extends Product with Serializable
  final case class FetchOne[I, A, M[_]](a: I, ds: DataSource[I, A, M]) extends FetchOp[A]
  final case class FetchMany[I, A, M[_]](as: List[I], ds: DataSource[I, A, M]) extends FetchOp[List[A]]
  final case class Result[A](a: A) extends FetchOp[A]
  final case class FetchError[A, E <: Throwable](err: E)() extends FetchOp[A]
}


object types {
  import algebra.FetchOp

  type DataSourceName = String

  type DataSourceIdentity = (DataSourceName, Any)

  type Fetch[A] = Free[FetchOp, A]

  type FetchInterpreter[M[_], E <: Env[_]] = {
    type f[x] = StateT[M, E, x]
  }
}

object cache {
  // no cache
  case class NoCache() extends DataSourceCache

  implicit object NoCacheImpl extends Cache[NoCache]{
    override def get[I](c: NoCache, k: DataSourceIdentity): Option[Any] = None
    override def update[I, A](c: NoCache, k: DataSourceIdentity, v: A): NoCache = c
  }

  // in-memory cache
  case class InMemoryCache(state:Map[Any, Any]) extends DataSourceCache

  object InMemoryCache {
    def empty: InMemoryCache = InMemoryCache(Map.empty[Any, Any])

    def apply(results: (Any, Any)*): InMemoryCache =
      InMemoryCache(results.foldLeft(Map.empty[Any, Any])({
        case (c, (k, v)) => c.updated(k, v)
      }))
  }

  implicit object InMemoryCacheImpl extends Cache[InMemoryCache]{
    override def get[I](c: InMemoryCache, k: DataSourceIdentity): Option[Any] = c.state.get(k)
    override def update[I, A](c: InMemoryCache, k: DataSourceIdentity, v: A): InMemoryCache = InMemoryCache(c.state.updated(k, v))
  }
}

object Fetch {
  import algebra._
  import types._
  import cache._
  import interpreters._

  def pure[A](a: A): Fetch[A] =
    Free.liftF(Result(a))

  def error[A](e: Throwable): Fetch[A] =
    Free.liftF(FetchError(e))

  def apply[I, A, M[_]](i: I)(
    implicit DS: DataSource[I, A, M]
  ): Fetch[A] =
    Free.liftF(FetchOne[I, A, M](i, DS))

  def collect[I, A, M[_]](ids: List[I])(
    implicit DS: DataSource[I, A, M]
  ): Fetch[List[A]] =
    Free.liftF(FetchMany[I, A, M](ids, DS))

  /// xxx: List[B] -> (B -> Fetch[A]) -> Fetch[List[A]]
  def traverse[I, A, B, M[_]](ids: List[B])(f: B => I)(
    implicit DS: DataSource[I, A, M]
  ): Fetch[List[A]] =
    collect(ids.map(f))

  def coalesce[I, A, M[_]](fl: I, fr: I)(
    implicit
      DS: DataSource[I, A, M]
  ): Fetch[(A, A)] =
    Free.liftF(FetchMany[I, A, M](List(fl, fr), DS)).map(l => (l(0), l(1)))

  def join[I, R, A, B, M[_]](fl: I, fr: R)(
    implicit
      DS: DataSource[I, A, M],
    DSS: DataSource[R, B, M]
  ): Fetch[(A, B)] =
    (Fetch(fl) |@| Fetch(fr)).tupled

  def runFetch[I, A, C <: DataSourceCache, M[_]](
    fa: Fetch[A],
    cache: C
  )(
    implicit
      MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): M[A] = fa.foldMap[FetchInterpreter[M, FetchEnv[C]]#f](interpreter).runA(FetchEnv(cache))

  def runEnv[I, A, C <: DataSourceCache, M[_]](
    fa: Fetch[A],
    env: FetchEnv[C]
  )(
    implicit
      MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): M[FetchEnv[C]] = fa.foldMap[FetchInterpreter[M, FetchEnv[C]]#f](interpreter).runS(env)
  def run[I, A, M[_]](
    fa: Fetch[A]
  )(
    implicit
      MM: MonadError[M, Throwable]
  ): M[A] = runFetch[I, A, NoCache, M](fa, NoCache())(MM, NoCacheImpl)

  def runCached[I, A, C <: DataSourceCache, M[_]](
    fa: Fetch[A],
    cache: C
  )(
    implicit
      MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): M[A] = runFetch[I, A, C, M](fa, cache)(MM, CC)
}

object interpreters {
  import algebra._
  import types._
  import cache._

  def interpreter[C <: DataSourceCache, I, E <: Env[C], M[_]](
    implicit
      MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): FetchOp ~> FetchInterpreter[M, FetchEnv[C]]#f = {
    new (FetchOp ~> FetchInterpreter[M, FetchEnv[C]]#f) {
      def apply[A](fa: FetchOp[A]): FetchInterpreter[M, FetchEnv[C]]#f[A] = {
        StateT[M, FetchEnv[C], A] { env: FetchEnv[C] => fa match {
          case Result(a) => MM.pure((env, a))
          case FetchError(e) => MM.raiseError(e)
          case FetchOne(id: I, ds) => {
            val startRound = System.nanoTime()
            val cache = env.cache
            CC.get(cache, ds.identity(id)).fold[M[(FetchEnv[C], A)]](
              MM.flatMap(ds.fetchMany(List(id)).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
                val endRound = System.nanoTime()
                res.get(id).fold[M[(FetchEnv[C], A)]](
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
                      Round(cache, ds.name, OneRound(id), startRound, endRound, true),
                      List(id)
                    ), result))
                })
              })
            )(cached => {
              val endRound = System.nanoTime()
              MM.pure(
                (env.next(
                  cache,
                  Round(cache, ds.name, OneRound(id), startRound, endRound, true),
                  List(id)), cached.asInstanceOf[A]))
            })
          }
          case FetchMany(ids: List[I], ds) => {
            val startRound = System.nanoTime()
            val cache = env.cache
            val newIds = ids.distinct.filterNot(i => CC.get(cache, ds.identity(i)).isDefined)
            if (newIds.isEmpty)
              MM.pure(
                (env.next(
                  cache,
                  Round(cache, ds.name, ManyRound(ids), startRound, System.nanoTime(), true),
                  newIds
                ), ids.flatMap(id => CC.get(cache, ds.identity(id)))))
            else {
              MM.flatMap(ds.fetchMany(newIds).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
                val endRound = System.nanoTime()
                ids.map(res.get(_)).sequence.fold[M[(FetchEnv[C], A)]](
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
                  val newCache = res.foldLeft(cache)({
                    case (c, (k, v)) => CC.update(c, ds.identity(k), v)
                  })
                  MM.pure(
                    (env.next(
                      newCache,
                      Round(cache, ds.name, ManyRound(ids), startRound, endRound),
                      newIds
                    ), results))
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
