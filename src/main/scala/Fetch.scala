package fetch

import scala.collection.immutable.Seq
import scala.collection.immutable.Queue

import fetch.types._

import cats.{ Functor, Monad, MonadError, ~> }
import cats.data.{ State, StateT, Const }
import cats.std.option._
import cats.std.list._
import cats.syntax.cartesian._
import cats.syntax.traverse._
import cats.free.{ Free }

/**
  * A `DataSource` is the recipe for fetching a certain identity `I`, which yields
  * results of type `A` with the concurrency and error handling specified by the Monad
  * `M`.
  */
trait DataSource[I, A, M[_]] {
  def name: DataSourceName = this.toString
  def identity(i: I): DataSourceIdentity = (name, i)
  def fetch(ids: List[I]): M[Map[I, A]]
}

/**
  * A marker trait for cache implementations.
  */
trait DataSourceCache

/**
  * A `Cache` trait so the users of the library can provide their own cache.
  */
trait Cache[T <: DataSourceCache]{
  def update[I, A](c: T, k: DataSourceIdentity, v: A): T
  def get[I](c: T, k: DataSourceIdentity): Option[Any]
}

/**
  * An environment that is passed along during the fetch rounds. It holds the
  * cache and the list of rounds that have been executed.
  */
trait Env[C <: DataSourceCache]{
  def cache: C
  def rounds: Seq[Round]

  def cached: Seq[Round] =
    rounds.filter(_.cached)

  def uncached: Seq[Round] =
    rounds.filterNot(_.cached)

  def next(
    newCache: C,
    newRound: Round,
    newIds: List[Any]
  ): Env[C]
}
// todo: configuration, profiling, etc. based on the used environment?

/**
  * A data structure that holds information about a fetch round.
  */
case class Round(
  cache: DataSourceCache,
  ds: DataSourceName,
  kind: RoundKind,
  startRound: Long,
  endRound: Long,
  cached: Boolean = false
) {
  def duration: Double = (endRound - startRound) / 1e6

  def isConcurrent: Boolean = kind match {
    case ConcurrentRound(_) => true
    case _ => false
  }
}

sealed trait RoundKind
final case class OneRound(id: Any) extends RoundKind
final case class ManyRound(ids: List[Any]) extends RoundKind
final case class ConcurrentRound(ids: Map[String, List[Any]]) extends RoundKind

/**
  * A concrete implementation of `Env` used in the default Fetch interpreter.
  */
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

/**
  * An exception thrown from the interpreter when failing to perform a data fetch.
  */
case class FetchFailure[C <: DataSourceCache](env: Env[C])(
  implicit CC: Cache[C]
) extends Throwable

object algebra {
  /**
    * Primitive operations in the Fetch Free monad.
    */
  sealed abstract class FetchOp[A] extends Product with Serializable

  final case class FetchOne[I, A, M[_]](a: I, ds: DataSource[I, A, M]) extends FetchOp[A]
  final case class FetchMany[I, A, M[_]](as: List[I], ds: DataSource[I, A, M]) extends FetchOp[List[A]]
  final case class Concurrent[M[_]](as: List[FetchMany[_, _, M]]) extends FetchOp[Unit]
  final case class FetchError[A, E <: Throwable](err: E) extends FetchOp[A]
}

object types {
  import algebra.FetchOp

  type DataSourceName = String

  type DataSourceIdentity = (DataSourceName, Any)

  type Fetch[A] = Free[FetchOp, A]

  type FetchInterpreter[M[_], C <: DataSourceCache] = {
    type f[x] = StateT[M, FetchEnv[C], x]
  }
}

object cache {
  /** A cache that stores its elements in memory.
    */
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

  /**
    * Lift a plain value to the Fetch monad.
    */
  def pure[A](a: A): Fetch[A] =
    Free.pure(a)

  /**
    * Lift an error to the Fetch monad.
    */
  def error[A](e: Throwable): Fetch[A] =
    Free.liftF(FetchError(e))

  /**
    * Given a value that has a related `DataSource` implementation, lift it
    * to the `Fetch` monad. When executing the fetch the data source will be
    * queried and the fetch will return its result.
    */
  def apply[I, A, M[_]](i: I)(
    implicit DS: DataSource[I, A, M]
  ): Fetch[A] =
    Free.liftF(FetchOne[I, A, M](i, DS))

  def deps[A, M[_]](f: Fetch[A]): List[FetchMany[_, A, M]] = {
    type FM = List[FetchMany[_, A, M]]

    f.foldMap[Const[List[FetchMany[_, A, M]], ?]](new (FetchOp ~> Const[FM, ?]) {
      def apply[X](x: FetchOp[X]): Const[FM, X] = x match {
        case one@FetchOne(id, ds) => Const(List(FetchMany(List(id), ds.asInstanceOf[DataSource[Any, A, M]])))
        case conc@Concurrent(as) => Const(as.asInstanceOf[FM])
        case _ => Const(List())
      }
    })(new Monad[Const[FM, ?]] {
      def pure[A](x: A): Const[FM, A] = Const(List())

      def flatMap[A, B](fa: Const[FM, A])(f: A => Const[FM, B]): Const[FM, B] =
        fa.asInstanceOf[Const[FM, B]]
    }).getConst
  }

  def combineDeps[A, M[_]](ds: List[FetchOp[A]]): List[FetchMany[_, _, M]] = {
    ds.foldLeft(Map.empty[Any, List[_]])((acc, op) => op match {
      case one@FetchOne(id, ds) => acc.updated(ds, acc.get(ds).fold(List(id))(accids => accids :+ id))
      case many@FetchMany(ids, ds) => acc.updated(ds, acc.get(ds).fold(ids)(accids => accids ++ ids))
      case _ => acc
    }).toList.map({
      case (ds, ids) => FetchMany[Any, A, M](ids, ds.asInstanceOf[DataSource[Any, A, M]])
    })
  }

  /**
    * Given a list of `Fetch` instances, return a new `Fetch` that will run as much as it can
    * concurrently.
    */
  def concurrently[A, M[_]](fs: List[Fetch[A]]): Fetch[Unit] = {
    val fetches: List[FetchMany[_, _, M]] = combineDeps(fs.map(deps).flatten)
    Free.liftF(Concurrent[M](fetches))
  }

  /**
    * Collect a list of fetches into a fetch of a list. It implies concurrent execution of fetches.
    */
  def collect[I, A, M[_]](ids: List[Fetch[A]]): Fetch[List[A]] = {
    for {
      _ <- concurrently[A, M](ids)
      l <- ids.sequence
    } yield l
  }

  /**
    * Apply a fetch-returning function to every element in a list and return a Fetch of the list of
    * results. It implies concurrent execution of fetches.
    */
  def traverse[I, A, B, M[_]](ids: List[I])(f: I => Fetch[A]): Fetch[List[A]] =
    collect(ids.map(f))

  /**
    * Join two fetches from any data sources and return a Fetch that returns a tuple with the two
    * results. It implies concurrent execution of fetches.
    */
  def join[A, B, C, M[_]](fl: Fetch[A], fr: Fetch[B]): Fetch[(A, B)] = {
    for {
      _ <- concurrently[C, M](List(fl, fr).asInstanceOf[List[Fetch[C]]])
      l <- fl
      r <- fr
    } yield (l, r)
  }

  /**
    * Run a `Fetch` with the given cache, returning a pair of the final environment and result
    * in the monad `M`.
    */
  def runFetch[A, C <: DataSourceCache, M[_]](
    fa: Fetch[A],
    cache: C
  )(
    implicit
      MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): M[(FetchEnv[C], A)] = fa.foldMap[FetchInterpreter[M, C]#f](interpreter).run(FetchEnv(cache))

  /**
    * Run a `Fetch` with the given cache, returning the final environment in the monad `M`.
    */
  def runEnv[A, C <: DataSourceCache, M[_]](
    fa: Fetch[A],
    cache: C = InMemoryCache.empty
  )(
    implicit
      MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): M[FetchEnv[C]] = MM.map(runFetch[A, C, M](fa, cache)(MM, CC))(_._1)

  /**
    * Run a `Fetch` with the given cache, the result in the monad `M`.
    */
  def run[A, C <: DataSourceCache, M[_]](
    fa: Fetch[A],
    cache: C = InMemoryCache.empty
  )(
    implicit
      MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): M[A] = MM.map(runFetch[A, C, M](fa, cache)(MM, CC))(_._2)
}

object interpreters {
  import algebra._
  import types._
  import cache._

  def interpreter[C <: DataSourceCache, I, E <: Env[C], M[_]](
    implicit
      MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): FetchOp ~> FetchInterpreter[M, C]#f = {
    new (FetchOp ~> FetchInterpreter[M, C]#f) {
      def apply[A](fa: FetchOp[A]): FetchInterpreter[M, C]#f[A] = {
        StateT[M, FetchEnv[C], A] { env: FetchEnv[C] => fa match {
          case FetchError(e) => MM.raiseError(e)
          case Concurrent(manies) => {
            val startRound = System.nanoTime()
            val cache = env.cache
            // dedupe ids
            val sources = manies.map(_.ds)
            val ids = manies.map(_.as.distinct)
            // don't ask for cached results
            val sids = (sources zip ids).map({
              case (ds, ids) => (
                ds,
                ids.filterNot(id => CC.get(cache, ds.asInstanceOf[DataSource[I, A, M]].identity(id.asInstanceOf[I])).isDefined)
              )
            }).filterNot({
              case (_, ids) => ids.isEmpty
            })
            if (sids.isEmpty)
              MM.pure((env, ()))
            else
              MM.flatMap(sids.map({
                case (ds, as) => ds.asInstanceOf[DataSource[I, A, M]].fetch(as.asInstanceOf[List[I]])
              }).sequence)((results: List[Map[_, _]]) => {
                val endRound = System.nanoTime()
                val newCache = (sources zip results).foldLeft(cache)((accache, resultset) => resultset match {
                  case (ds: DataSource[I, _, Any], resultmap) => resultmap.foldLeft(accache)({
                    case (c, (k, v)) => CC.update(c, ds.identity(k.asInstanceOf[I]), v)
                  })
                })
                val newEnv = env.next(
                  newCache,
                  Round(
                    cache,
                    "Concurrent",
                    ConcurrentRound(
                      sids.map({
                        case (ds, as) => (ds.name, as)
                      }).toMap
                    ),
                    startRound,
                    endRound
                  ),
                  Nil
                )
                MM.pure((newEnv, ()))
              })
          }
          case FetchOne(id: I, ds) => {
            val startRound = System.nanoTime()
            val cache = env.cache
            CC.get(cache, ds.identity(id)).fold[M[(FetchEnv[C], A)]](
              MM.flatMap(ds.fetch(List(id)).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
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
                      Round(cache, ds.name, OneRound(id), startRound, endRound),
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
            val oldIds = ids.distinct
            val newIds = oldIds.filterNot(i => CC.get(cache, ds.identity(i)).isDefined)
            if (newIds.isEmpty)
              MM.pure(
                (env.next(
                  cache,
                  Round(cache, ds.name, ManyRound(ids), startRound, System.nanoTime(), true),
                  newIds
                ), ids.flatMap(id => CC.get(cache, ds.identity(id)))))
            else {
              MM.flatMap(ds.fetch(newIds).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
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
                  // todo: improve round reporting
                  val someCached = oldIds.size == newIds.size
                  MM.pure(
                    (env.next(
                      newCache,
                      Round(cache, ds.name, ManyRound(ids), startRound, endRound, someCached),
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
