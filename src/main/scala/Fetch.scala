package fetch

import cats._
import cats.implicits._
import cats.free._
import shapeless._


// Data source

trait DataSource[I, A] {
  def fetch(id: I): Option[A]
  def fetchMany(ids: List[I]): List[A]
}

// Fetch


sealed abstract class Fetch[A] extends Product with Serializable
final case class One[I, A](a: I, ds: DataSource[I, A]) extends Fetch[Option[A]]
final case class Many[I, A](as: List[I], ds: DataSource[I, A]) extends Fetch[List[A]]

object Fetch {
  implicit def freeApToFreeMonad[F[_], A](fa : FreeApplicative[F, A]) : Free[F, A] =
    fa.monad

  class OnePartiallyApplied[A] {
    def apply[I](id : I)(implicit DS : DataSource[I, A]) : FreeApplicative[Fetch, Option[A]] =
      FreeApplicative.lift(One[I, A](id, DS))
  }

  def one[A] = new OnePartiallyApplied[A]

  class ManyPartiallyApplied[A] {
    def apply[I](ids : List[I])(implicit DS : DataSource[I, A]) : FreeApplicative[Fetch, List[A]] =
      FreeApplicative.lift(Many[I, A](ids, DS))
  }

  def many[A] = new ManyPartiallyApplied[A]

  def pure[A](a: A): FreeApplicative[Fetch, A] =
    FreeApplicative.pure(a)

  def apply[I, A](i: I)(
    implicit DS: DataSource[I, A]
  ): FreeApplicative[Fetch, Option[A]] =
    one[A](i)

  def run[I, A, M[_]](fa: FreeApplicative[Fetch, A])(
    implicit
      AP: ApplicativeError[M, Throwable]
  ): M[A] = fa foldMap {
    new (Fetch ~> M) {
      def apply[A](fa: Fetch[A]): M[A] = fa match {
        case One(id, ds) => AP.pureEval(Eval.later(ds.fetch(id)))
        case Many(ids, ds) => AP.pureEval(Eval.later(ds.fetchMany(ids)))
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
        case One(id, ds) => AP.pureEval(Eval.later(ds.fetch(id)))
        case Many(ids, ds) => AP.pureEval(Eval.later(ds.fetchMany(ids)))
      }
    }
  }

  case class Dep(ds: DataSource[_, _], ids: List[_])

  val depMonoid: Monoid[List[Dep]] = new Monoid[List[Dep]] with Semigroup[List[Dep]]{
    override def empty: List[Dep] = Nil
    override def combine(x: List[Dep], y: List[Dep]): List[Dep] =
      x ++ y
  }

  def dependencies[A](f: FreeApplicative[Fetch, A]): List[Dep] =
    f.analyze(new (Fetch ~> ({type L[A] = List[Dep]})#L) {
      def apply[B](fa: Fetch[B]): List[Dep] = fa match {
        case One(i, ds) => List(Dep(ds, List(i)))
        case Many(ids, ds) => List(Dep(ds, ids))
      }
    })(depMonoid)

  type DataSourceCache[I, A] = Map[I, A]
  type Cache[I, A] = Map[DataSource[I, A], DataSourceCache[I, A]]

  def lookup[I, A](cache: Cache[I, A], ds: DataSource[I, A], id: I): Option[A] = for {
    sources <- cache.get(ds)
    result <- sources.get(id)
  } yield result

  def insert[I, A](cache: Cache[I, A], i: I, v: A): Cache[I, A] =
    cache
}

