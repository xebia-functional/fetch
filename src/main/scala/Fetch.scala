package fetch

import cats._
import cats.implicits._
import cats.free._
import shapeless._


// Result status

sealed abstract class ResultStatus[A] extends Product with Serializable
final case class NotFetched[A]() extends ResultStatus[A]
final case class FetchSuccess[A](a: A) extends ResultStatus[A]
final case class FetchFailure[A](e: Throwable) extends ResultStatus[A]

// Data source

trait DataSource[I, A, M[_]] {
  def fetchMany(ids: List[I]): M[Map[I, A]]
}

// Cache

object Caching {
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
        case Collect(ids: List[I], ds) => {
          AP.pureEval(Eval.later({
            ds.fetchMany(ids).asInstanceOf[Map[I, A]].values.toList
          }))
        }
        case One(id: I, ds) => AP.pureEval(Eval.later({
          // FIXME: Option.get
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


}

