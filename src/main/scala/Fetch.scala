package fetch

import cats._
import cats.implicits._
import cats.free._
import shapeless._


// Data source

trait DataSource[I, A, M[_]] {
  def fetchMany(ids: List[I]): M[Map[I, A]]
}

// Fetch

sealed abstract class Fetch[A] extends Product with Serializable
final case class One[I, A, M[_]](a: I, ds: DataSource[I, A, M]) extends Fetch[A]
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

  def interpreter[I, A, M[_]](
    implicit
      AP: ApplicativeError[M, Throwable]
  ): Fetch ~> M =
    new (Fetch ~> M) {
      def apply[A](fa: Fetch[A]): M[A] = fa match {
        case Result(a) => AP.pureEval(Eval.now(a))
        case Errored(e) => AP.raiseError(e)
        case One(id: I, ds) => AP.pureEval(Eval.later({
          ds.fetchMany(List(id)).asInstanceOf[Map[I, A]].get(id).get
        }))
      }
    }

  def run[I, A, M[_]](fa: FreeApplicative[Fetch, A])(
    implicit
      AP: ApplicativeError[M, Throwable]
  ): M[A] = fa foldMap {
    new (Fetch ~> M) {
      def apply[A](fa: Fetch[A]): M[A] = fa match {
        case Result(a) => AP.pureEval(Eval.now(a))
        case Errored(e) => AP.raiseError(e)
        case One(id: I, ds) => AP.pureEval(Eval.later({
          ds.fetchMany(List(id)).asInstanceOf[Map[I, A]].get(id).get
        }))
      }
    }
  }

  def run[I, A, M[_]](fa: Free[Fetch, A])(
    implicit
      AP: ApplicativeError[M, Throwable],
    MI: Monad[M]
  ): M[A] = fa foldMap {
    new (Fetch ~> M) {
      def apply[A](fa: Fetch[A]): M[A] = fa match {
        case Result(a) => AP.pureEval(Eval.now(a))
        case Errored(e) => AP.raiseError(e)
        case One(id: I, ds) => AP.pureEval(Eval.later({
          ds.fetchMany(List(id)).asInstanceOf[Map[I, A]].get(id).get
        }))          
      }
    }
  }

  type DataSourceCache[I, A] = Map[I, A]
  type Cache[I, A, M[_]] = Map[DataSource[I, A, M], DataSourceCache[I, A]]

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
    i: I,
    v: A
  ): Cache[I, A, M] =
    cache
}

