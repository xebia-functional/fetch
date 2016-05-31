/*
 * Copyright 2016 47 Degrees, LLC. <http://www.47deg.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fetch

import scala.collection.immutable.Map

import cats.{Applicative, Monad, ApplicativeError, MonadError, ~>}
import cats.data.{StateT, Const, NonEmptyList}
import cats.free.{Free}
import cats.std.list._
import cats.std.option._
import cats.syntax.traverse._
import scala.concurrent.duration._

sealed trait Query[A] extends Product with Serializable
final case class Now[A](a: A)                                                 extends Query[A]
final case class Later[A](a: () => A)                                         extends Query[A]
final case class Async[A](action: (Query.Callback[A], Query.Errback) => Unit) extends Query[A] // todo: timeout

object Query {
  type Callback[A] = A => Unit
  type Errback     = Throwable => Unit

  def now[A](x: A): Query[A]       = Now(x)
  def later[A](th: => A): Query[A] = Later(th _)
  def async[A](
      action: (Callback[A], Errback) => Unit //,      timeout: FiniteDuration
  ): Query[A] = Async(action)
}

/** Requests in Fetch Free monad.
  */
sealed trait FetchRequest[I, A] extends Product with Serializable {
  def fullfilledBy(cache: DataSourceCache): Boolean
  def missingIdentities(cache: DataSourceCache): List[I]
  def dataSource: DataSource[I, A]
  def identities: NonEmptyList[I]
}

/**
  * Primitive operations in the Fetch Free monad.
  */
sealed abstract class FetchOp[A] extends Product with Serializable

final case class Cached[A](a: A) extends FetchOp[A]
final case class FetchOne[I, A](a: I, ds: DataSource[I, A])
    extends FetchOp[A]
    with FetchRequest[I, A] {
  override def fullfilledBy(cache: DataSourceCache): Boolean = {
    cache.get(ds.identity(a)).isDefined
  }
  override def missingIdentities(cache: DataSourceCache): List[I] = {
    cache.get(ds.identity(a)).fold(List(a))((res: Any) => Nil)
  }
  override def dataSource: DataSource[I, A] = ds
  override def identities: NonEmptyList[I]  = NonEmptyList(a, Nil)
}
final case class FetchMany[I, A](as: NonEmptyList[I], ds: DataSource[I, A])
    extends FetchOp[List[A]]
    with FetchRequest[I, A] {
  override def fullfilledBy(cache: DataSourceCache): Boolean = {
    as.forall((i: I) => cache.get(ds.identity(i)).isDefined)
  }
  override def missingIdentities(cache: DataSourceCache): List[I] = {
    as.unwrap.distinct.filterNot(i => cache.get(ds.identity(i)).isDefined)
  }
  override def dataSource: DataSource[I, A] = ds
  override def identities: NonEmptyList[I]  = as
}
final case class Concurrent(as: List[FetchRequest[_, _]]) extends FetchOp[DataSourceCache]
final case class FetchError[A](err: Throwable)            extends FetchOp[A]

object `package` {
  type DataSourceName = String

  type DataSourceIdentity = (DataSourceName, Any)

  type Fetch[A] = Free[FetchOp, A]

  trait FetchMonadError[M[_]] extends MonadError[M, Throwable] {
    def runQuery[A](q: Query[A]): M[A]
  }

  type FetchInterpreter[M[_]] = {
    type f[x] = StateT[M, FetchEnv, x]
  }

  implicit val fetchApplicative: Applicative[Fetch] = new Applicative[Fetch] {
    def pure[A](a: A): Fetch[A] = Fetch.pure(a)

    def ap[A, B](ff: Fetch[A => B])(fa: Fetch[A]): Fetch[B] =
      Fetch.join(ff, fa).map({ case (f, a) => f(a) })
  }

  object Fetch extends FetchInterpreters {

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
    def apply[I, A](i: I)(
        implicit DS: DataSource[I, A]
    ): Fetch[A] =
      Free.liftF(FetchOne[I, A](i, DS))

    type FM = List[FetchOp[_]]
    private[this] val DM: Monad[Const[FM, ?]] = new Monad[Const[FM, ?]] {
      def pure[A](x: A): Const[FM, A] = Const(List())

      def flatMap[A, B](fa: Const[FM, A])(f: A => Const[FM, B]): Const[FM, B] = fa match {
        case Const(List(Cached(a))) => f(a.asInstanceOf[A])
        case other                  => fa.asInstanceOf[Const[FM, B]]
      }
    }

    private[this] def deps[A](f: Fetch[_]): List[FetchRequest[_, _]] = {
      f.foldMap[Const[FM, ?]](new (FetchOp ~> Const[FM, ?]) {
          def apply[X](x: FetchOp[X]): Const[FM, X] = x match {
            case one @ FetchOne(id, ds) => Const(List(one))
            case conc @ Concurrent(as)  => Const(as.asInstanceOf[FM])
            case cach @ Cached(a)       => Const(List(cach))
            case _                      => Const(List())
          }
        })(DM)
        .getConst
        .collect({
          case one @ FetchOne(_, _)   => one
          case many @ FetchMany(_, _) => many
        })
    }

    private[this] def combineDeps(ds: List[FetchRequest[_, _]]): List[FetchRequest[_, _]] = {
      ds.foldLeft(Map.empty[DataSource[_, _], NonEmptyList[Any]])((acc, op) =>
              op match {
            case one @ FetchOne(id, ds) =>
              acc.updated(ds,
                          acc
                            .get(ds)
                            .fold(NonEmptyList(id): NonEmptyList[Any])(accids =>
                                  accids.combine(NonEmptyList(id))))
            case many @ FetchMany(ids, ds) =>
              acc.updated(ds,
                          acc
                            .get(ds)
                            .fold(ids.asInstanceOf[NonEmptyList[Any]])(accids =>
                                  accids.combine(ids.asInstanceOf[NonEmptyList[Any]])))
            case _ => acc
        })
        .toList
        .map({
          case (ds, ids) if ids.unwrap.size == 1 =>
            FetchOne[Any, Any](ids.head, ds.asInstanceOf[DataSource[Any, Any]])
          case (ds, ids) =>
            FetchMany[Any, Any](ids, ds.asInstanceOf[DataSource[Any, Any]])
        })
    }

    private[this] def concurrently(fa: Fetch[_], fb: Fetch[_]): Fetch[DataSourceCache] = {
      val fetches: List[FetchRequest[_, _]] = combineDeps(deps(fa) ++ deps(fb))
      Free.liftF(Concurrent(fetches))
    }

    /**
      * Transform a list of fetches into a fetch of a list. It implies concurrent execution of fetches.
      */
    def sequence[I, A](ids: List[Fetch[A]]): Fetch[List[A]] = {
      ids.foldLeft(Fetch.pure(List(): List[A]))((f, newF) =>
            Fetch.join(f, newF).map(t => t._1 :+ t._2))
    }

    /**
      * Apply a fetch-returning function to every element in a list and return a Fetch of the list of
      * results. It implies concurrent execution of fetches.
      */
    def traverse[A, B](ids: List[A])(f: A => Fetch[B]): Fetch[List[B]] =
      sequence(ids.map(f))

    /**
      * Apply the given function to the result of the two fetches. It implies concurrent execution of fetches.
      */
    def map2[A, B, C](f: (A, B) => C)(fa: Fetch[A], fb: Fetch[B]): Fetch[C] =
      Fetch.join(fa, fb).map({ case (a, b) => f(a, b) })

    private[this] def simplify(results: DataSourceCache): (FetchOp ~> FetchOp) = {
      new (FetchOp ~> FetchOp) {
        def apply[B](f: FetchOp[B]): FetchOp[B] = f match {
          case one @ FetchOne(id, ds) => {
              results
                .get(ds.identity(id))
                .fold(one: FetchOp[B])(b => Cached(b).asInstanceOf[FetchOp[B]])
            }
          case many @ FetchMany(ids, ds) => {
              val fetched = ids.map(id => results.get(ds.identity(id))).unwrap.sequence
              fetched.fold(many: FetchOp[B])(results => Cached(results))
            }
          case conc @ Concurrent(manies) => {
              val newManies = manies.filterNot(_.fullfilledBy(results))

              if (newManies.isEmpty)
                Cached(results).asInstanceOf[FetchOp[B]]
              else
                Concurrent(newManies).asInstanceOf[FetchOp[B]]
            }
          case other => other
        }
      }
    }

    /**
      * Join two fetches from any data sources and return a Fetch that returns a tuple with the two
      * results. It implies concurrent execution of fetches.
      */
    def join[A, B](fl: Fetch[A], fr: Fetch[B]): Fetch[(A, B)] = {
      for {
        cache <- concurrently(fl, fr)
        result <- {
          val sfl = fl.compile(simplify(cache))
          val sfr = fr.compile(simplify(cache))

          val remainingDeps = combineDeps(deps(sfl) ++ deps(sfr))

          if (remainingDeps.isEmpty) {
            for {
              a <- sfl
              b <- sfr
            } yield (a, b)
          } else {
            join[A, B](sfl, sfr)
          }
        }
      } yield result
    }

    class FetchRunner[M[_]] {
      def apply[A](
          fa: Fetch[A],
          cache: DataSourceCache = InMemoryCache.empty
      )(
          implicit MM: FetchMonadError[M]
      ): M[(FetchEnv, A)] =
        fa.foldMap[FetchInterpreter[M]#f](interpreter).run(FetchEnv(cache))
    }

    /**
      * Run a `Fetch` with the given cache, returning a pair of the final environment and result
      * in the monad `M`.
      */
    def runFetch[M[_]]: FetchRunner[M] = new FetchRunner[M]

    class FetchRunnerEnv[M[_]] {
      def apply[A](
          fa: Fetch[A],
          cache: DataSourceCache = InMemoryCache.empty
      )(
          implicit MM: FetchMonadError[M]
      ): M[FetchEnv] =
        fa.foldMap[FetchInterpreter[M]#f](interpreter).runS(FetchEnv(cache))
    }

    /**
      * Run a `Fetch` with the given cache, returning the final environment in the monad `M`.
      */
    def runEnv[M[_]]: FetchRunnerEnv[M] = new FetchRunnerEnv[M]

    class FetchRunnerA[M[_]] {
      def apply[A](
          fa: Fetch[A],
          cache: DataSourceCache = InMemoryCache.empty
      )(
          implicit MM: FetchMonadError[M]
      ): M[A] =
        fa.foldMap[FetchInterpreter[M]#f](interpreter).runA(FetchEnv(cache))
    }

    /**
      * Run a `Fetch` with the given cache, the result in the monad `M`.
      */
    def run[M[_]]: FetchRunnerA[M] = new FetchRunnerA[M]
  }
}
