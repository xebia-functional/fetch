package fetch

import scala.math.Equiv
import scala.util.hashing.Hashing

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

  def cachedInterpreter[I, AA, M[_]](
    implicit
      MM: MonadError[M, Throwable]
  ): Fetch ~> M = {
    new (Fetch ~> M) {
      // xxx: make it safe to concurrent updates
      var cache: Map[I, Any] = Map()

      def apply[A](fa: Fetch[A]): M[A] = fa match {
        case Result(a) => MM.pureEval(Eval.now(a))
        case Errored(e) => MM.raiseError(e)
        case Collect(ids: List[I], ds) => {
          val newIds = ids.distinct.filterNot(i => cache.get(i).isDefined)
          if (newIds.isEmpty)
            MM.pure(ids.map(id => cache.get(id).get))
          else {
            MM.flatMap(ds.fetchMany(newIds).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
              // update cache
              res.foreach({ case (i, a) => {
                cache = cache.updated(i, a)
              }})
              val maybeResults = ids.map(res.get(_))
              if (maybeResults.forall(_.isDefined))
                MM.pure(ids.map(id => cache.get(id).get))
              else
                MM.raiseError(FetchFailure())
            })
          }


        }
        case One(id: I, ds) => {
          val maybeCached = cache.get(id)
          if (maybeCached.isDefined) {
            MM.pure(maybeCached.get.asInstanceOf[A])
          } else {
            MM.flatMap(ds.fetchMany(List(id)).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
              val maybeResult = res.get(id)
              if (maybeResult.isDefined) {
                val result = maybeResult.get
                cache = cache.updated(id, result)
                MM.pure(result)
              } else {
                MM.raiseError(FetchFailure())
              }
            })
          }
        }
      }
    }
  }

  def run[I, A, M[_]](fa: FreeApplicative[Fetch, A])(
    implicit
    MM: MonadError[M, Throwable]
  ): M[A] = fa foldMap interpreter

  def run[I, A, M[_]](fa: Free[Fetch, A])(
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

  def runWith[I, A, M[_]](
    fa: Free[Fetch, A],
    i: Fetch ~> M
  )(
    implicit
    MM: MonadError[M, Throwable]
  ): M[A] = fa foldMap i

  def runCached[I, A, M[_]](fa: FreeApplicative[Fetch, A])(
    implicit
    MM: MonadError[M, Throwable]
  ): M[A] = fa foldMap cachedInterpreter

  def runCached[I, A, M[_]](fa: Free[Fetch, A])(
    implicit
    MM: MonadError[M, Throwable]
  ): M[A] = fa foldMap cachedInterpreter


}

