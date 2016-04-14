package fetch

import cats.{ Monad, ApplicativeError, MonadError, ~>, Eval }
import cats.syntax.cartesian._
import cats.free.{ Free, FreeApplicative }

// Errors

case class FetchFailure() extends Exception

// Data source

trait DataSource[I, A, M[_]] {
  def fetchMany(ids: List[I]): M[Map[I, A]]
}

// Cache

object Caching {
  type DataSourceCache[I, A] = Map[I, A]
  type Cache[I, A, M[_]] = Map[DataSource[I, A, M], DataSourceCache[I, A]]

  object Cache {
    def apply[I, A, M[_]](): Cache[I, A, M] = Map.empty[DataSource[I, A, M], DataSourceCache[I, A]]
  }

  def lookup[I, A, M[_]](
    cache: Cache[I, A, M],
    ds: DataSource[I, A, M],
    id: I
  ): Option[A] = for {
    sources <- cache.get(ds)
    result <- sources.get(id)
  } yield result

  def insert[I, A, M[_]](
    cache: Cache[I, A, M],
    ds: DataSource[I, A, M],
    i: I,
    v: A
  ): Cache[I, A, M] = {
    lazy val initialCache = Map(i -> v)
    val resourceCache = cache.get(ds).fold(initialCache)(_.updated(i, v))
    cache.updated(ds, resourceCache)
  }
}

// Fetch

sealed abstract class Fetch[A] extends Product with Serializable
final case class One[I, A, M[_]](a: I, ds: DataSource[I, A, M]) extends Fetch[A]
final case class Collect[I, A, M[_]](as: List[I], ds: DataSource[I, A, M]) extends Fetch[List[A]]
final case class Result[A](a: A) extends Fetch[A]
final case class Errored[A](e: Throwable) extends Fetch[A]

object Fetch {
  implicit def freeApToFreeMonad[F[_], A](fa : FreeApplicative[F, A]) : Free[F, A] =
    fa.monad

  class OnePartiallyApplied[A] {
    def apply[I, M[_]](id : I)(
      implicit DS : DataSource[I, A, M]
    ) : FreeApplicative[Fetch, A] =
      FreeApplicative.lift(One[I, A, M](id, DS))
  }

  def one[A] = new OnePartiallyApplied[A]

  def pure[A](a: A): FreeApplicative[Fetch, A] =
    FreeApplicative.lift(Result(a))

  def error[A](e: Throwable): FreeApplicative[Fetch, A] =
    FreeApplicative.lift(Errored(e))

  def apply[I, A, M[_]](i: I)(
    implicit DS: DataSource[I, A, M]
  ): FreeApplicative[Fetch, A] =
    FreeApplicative.lift(One[I, A, M](i, DS))

  def collect[I, A, M[_]](ids: List[I])(
    implicit DS: DataSource[I, A, M]
  ): FreeApplicative[Fetch, List[A]] =
    FreeApplicative.lift(Collect[I, A, M](ids, DS))

  def traverse[I, A, B, M[_]](ids: List[B])(f: B => I)(
    implicit DS: DataSource[I, A, M]
  ): FreeApplicative[Fetch, List[A]] =
    collect(ids.map(f))

  // xxx: how can we avoid having separated `join` and `tupled`?
  def join[I, A, M[_]](fl: I, fr: I)(
    implicit
      DS: DataSource[I, A, M]
  ): FreeApplicative[Fetch, (A, A)] =
    FreeApplicative.lift(Collect[I, A, M](List(fl, fr), DS)).map(l => (l(0), l(1)))

  def tupled[I, R, A, B, M[_]](fl: I, fr: R)(
    implicit
      DS: DataSource[I, A, M],
    DSS: DataSource[R, B, M]
  ): FreeApplicative[Fetch, (A, B)] =
    (Fetch(fl) |@| Fetch(fr)).tupled  

  def interpreter[I, A, M[_]](
    implicit
      MM: MonadError[M, Throwable]
  ): Fetch ~> M = {
    new (Fetch ~> M) {
      def apply[A](fa: Fetch[A]): M[A] = fa match {
        case Result(a) => MM.pureEval(Eval.now(a))
        case Errored(e) => MM.raiseError(e)
        case Collect(ids: List[I], ds) => {
          MM.flatMap(ds.fetchMany(ids.distinct).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
            val maybeResults = ids.map(res.get(_))
            if (maybeResults.forall(_.isDefined))
              MM.pure(ids.map(res(_)))
            else
              MM.raiseError(FetchFailure())
          })
        }
        case One(id: I, ds) => {
          MM.flatMap(ds.fetchMany(List(id)).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
            val maybeResult = res.get(id)
            if (maybeResult.isDefined)
              MM.pure(maybeResult.get)
            else
              MM.raiseError(FetchFailure())
          })
        }
      }
    }
  }

  def cachedInterpreter[I, A, M[_]](
    implicit
      MM: MonadError[M, Throwable]
  ): Fetch ~> M = {
    new (Fetch ~> M) {
      def apply[A](fa: Fetch[A]): M[A] = fa match {
        case Result(a) => MM.pureEval(Eval.now(a))
        case Errored(e) => MM.raiseError(e)
        case Collect(ids: List[I], ds) => {
          MM.flatMap(ds.fetchMany(ids.distinct).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
            val maybeResults = ids.map(res.get(_))
            if (maybeResults.forall(_.isDefined))
              MM.pure(ids.map(res(_)))
            else
              MM.raiseError(FetchFailure())
          })
        }
        case One(id: I, ds) => {
          MM.flatMap(ds.fetchMany(List(id)).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
            val maybeResult = res.get(id)
            if (maybeResult.isDefined)
              MM.pure(maybeResult.get)
            else
              MM.raiseError(FetchFailure())
          })
        }
      }
    }      
  }


  def run[I, A, M[_]](fa: FreeApplicative[Fetch, A])(
    implicit
    MM: MonadError[M, Throwable]
  ): M[A] = fa foldMap interpreter

  def runCached[I, A, M[_]](fa: FreeApplicative[Fetch, A])(
    implicit
    MM: MonadError[M, Throwable]
  ): M[A] = fa foldMap interpreter

  def runWith[I, A, M[_]](
    fa: FreeApplicative[Fetch, A],
    i: Fetch ~> M
  )(
    implicit
    MM: MonadError[M, Throwable]
  ): M[A] = fa foldMap i

  def run[I, A, M[_]](fa: Free[Fetch, A])(
    implicit
    MM: MonadError[M, Throwable]
  ): M[A] = fa foldMap interpreter
}

