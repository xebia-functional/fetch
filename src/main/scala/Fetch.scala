package fetch

import cats._
import cats.free._

// tweets

case class Tweet(txt: String)


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

  def dependencies[A](f: FreeApplicative[Fetch, A])(
    implicit
      MM: MonoidK[List]
  ): List[_] =
    f.analyze(new (Fetch ~> ({type L[A] = List[_]})#L) {
      def apply[B](fa: Fetch[B]): List[_] = fa match {
        case One(i, _) => List(i)
        case Many(ids, _) => ids
      }
    })(MM.algebra.asInstanceOf[Monoid[List[_]]])

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
}

