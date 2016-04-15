
package fetch

import cats.{ Monad, ApplicativeError, MonadError, ~>, Eval }
import cats.data.{ State, StateT }
import cats.std.option._
import cats.std.list._
import cats.syntax.cartesian._
import cats.syntax.traverse._
import cats.free.{ Free, FreeApplicative }

// Errors

case class FetchFailure() extends Exception

// Data source

trait DataSource[I, A, M[_]] {
  def fetchMany(ids: List[I]): M[Map[I, A]]
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
            ids.map(res.get(_)).sequence.fold[M[A]](
              MM.raiseError(FetchFailure())
            )(results => { MM.pure(results) })
          })
        }
        case One(id: I, ds) => {
          MM.flatMap(ds.fetchMany(List(id)).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
            res.get(id).fold[M[A]](
              MM.raiseError(FetchFailure())
            )(result => MM.pure(result))
          })
        }
      }
    }
  }

  type Cache = Map[Any, Any]

  type FetchST[M[_]] = {
    type f[x] = StateT[M, Cache, x]
  }

  def cachedInterpreter[I, M[_]](
    implicit
      MM: MonadError[M, Throwable]
  ): Fetch ~> FetchST[M]#f = {
    new (Fetch ~> FetchST[M]#f) {
      def apply[A](fa: Fetch[A]): FetchST[M]#f[A] = {
        StateT[M, Cache, A] { cache => fa match {
          case Result(a) => MM.pure((cache, a))
          case Errored(e) => MM.raiseError(e)
          case One(id: I, ds) => {
            cache.get(id).fold[M[(Cache, A)]](
              MM.flatMap(ds.fetchMany(List(id)).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
                res.get(id).fold[M[(Cache, A)]](MM.raiseError(FetchFailure()))(result => {
                  MM.pure((cache.updated(id, result), result))
                })
              })
            )(cached => {
              MM.pure((cache, cached.asInstanceOf[A]))
            })
          }
          case Collect(ids: List[I], ds) => {
            val newIds = ids.distinct.filterNot(i => cache.get(i).isDefined)
            if (newIds.isEmpty)
              MM.pureEval(Eval.later((cache, ids.flatMap(id => cache.get(id)))))
            else {
              MM.flatMap(ds.fetchMany(newIds).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
                ids.map(res.get(_)).sequence.fold[M[(Cache, A)]](
                  MM.raiseError(FetchFailure())
                )(results => {
                  val newCache = res.foldLeft(cache)({
                    case (c, (k, v)) => c.updated(k, v)
                  })
                  MM.pureEval(Eval.later((newCache, results)))
                })
              })
            }
          }
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

  def runCached[I, A, M[_]](fa: FreeApplicative[Fetch, A])(
    implicit
    MM: MonadError[M, Throwable]
  ): M[A] = fa.foldMap[FetchST[M]#f](cachedInterpreter).runA(Map.empty[Any, Any])

  def runCached[I, A, M[_]](fa: Free[Fetch, A])(
    implicit
    MM: MonadError[M, Throwable]
  ): M[A] = fa.foldMap[FetchST[M]#f](cachedInterpreter).runA(Map.empty[Any, Any])

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
}

