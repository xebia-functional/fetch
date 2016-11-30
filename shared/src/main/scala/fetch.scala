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

import cats.{Applicative, ApplicativeError, Eval, Monad, MonadError, ~>}
import cats.data.{EitherT, NonEmptyList, StateT, Writer}
import cats.free.Free
import cats.instances.list._
import cats.instances.map._
import cats.instances.option._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.list._
import cats.syntax.traverse._
import scala.concurrent.duration.Duration

sealed trait Query[A] extends Product with Serializable

/** A query that can be satisfied synchronously. **/
final case class Sync[A](action: Eval[A]) extends Query[A]

/** A query that can only be satisfied asynchronously. **/
final case class Async[A](action: (Query.Callback[A], Query.Errback) => Unit, timeout: Duration)
    extends Query[A]

final case class Ap[A, B](ff: Query[A => B], fa: Query[A]) extends Query[B]

object Query {
  type Callback[A] = A => Unit
  type Errback     = Throwable => Unit

  def eval[A](e: Eval[A]): Query[A] = Sync(e)

  def sync[A](th: => A): Query[A] = Sync(Eval.later(th))

  def async[A](
      action: (Callback[A], Errback) => Unit,
      timeout: Duration = Duration.Inf
  ): Query[A] = Async(action, timeout)

  implicit val fetchQueryApplicative: Applicative[Query] = new Applicative[Query] {
    def pure[A](x: A): Query[A] = Sync(Eval.now(x))
    def ap[A, B](ff: Query[A => B])(fa: Query[A]): Query[B] =
      Ap(ff, fa)
  }
}

trait FetchException extends Throwable with Product with Serializable {
  def env: Env
}
case class NotFound(env: Env, request: FetchOne[_, _]) extends FetchException
case class MissingIdentities(env: Env, missing: Map[DataSourceName, List[Any]])
    extends FetchException
case class UnhandledException(env: Env, err: Throwable) extends FetchException

/** Requests in Fetch Free monad. */
sealed trait FetchRequest extends Product with Serializable

sealed trait FetchQuery[I, A] extends FetchRequest {
  def missingIdentities(cache: DataSourceCache): List[I]
  def dataSource: DataSource[I, A]
  def identities: NonEmptyList[I]
}

/**
  * Primitive operations in the Fetch Free monad.
  */
sealed abstract class FetchOp[A] extends Product with Serializable

final case class FetchOne[I, A](id: I, ds: DataSource[I, A])
    extends FetchOp[A]
    with FetchQuery[I, A] {
  override def missingIdentities(cache: DataSourceCache): List[I] = {
    cache.get[A](ds.identity(id)).fold(List(id))(_ => Nil)
  }
  override def dataSource: DataSource[I, A] = ds
  override def identities: NonEmptyList[I]  = NonEmptyList(id, Nil)
}

final case class FetchMany[I, A](ids: NonEmptyList[I], ds: DataSource[I, A])
    extends FetchOp[List[A]]
    with FetchQuery[I, A] {

  override def missingIdentities(cache: DataSourceCache): List[I] = {
    ids.toList.distinct.filterNot(i => cache.contains(ds.identity(i)))
  }
  override def dataSource: DataSource[I, A] = ds
  override def identities: NonEmptyList[I]  = ids
}
final case class Concurrent(queries: NonEmptyList[FetchQuery[Any, Any]])
    extends FetchOp[InMemoryCache]
    with FetchRequest

final case class Join[A, B](fl: Fetch[A], fr: Fetch[B]) extends FetchOp[(A, B)]
final case class Thrown[A](err: Throwable)              extends FetchOp[A]

object `package` {
  type DataSourceName     = String
  type DataSourceIdentity = (DataSourceName, Any)

  type Fetch[A] = Free[FetchOp, A]

  type FetchInterpreter[M[_]] = {
    type f[x] = StateT[M, FetchEnv, x]
  }

  implicit val fetchApplicative: Applicative[Fetch] = new Applicative[Fetch] {
    def pure[A](a: A): Fetch[A] = Fetch.pure(a)

    def ap[A, B](ff: Fetch[A => B])(fa: Fetch[A]): Fetch[B] =
      Fetch.join(ff, fa).map({ case (f, a) => f(a) })

    override def product[A, B](fa: Fetch[A], fb: Fetch[B]): Fetch[(A, B)] =
      Fetch.join(fa, fb)

    override def map2[A, B, Z](fa: Fetch[A], fb: Fetch[B])(f: (A, B) => Z): Fetch[Z] =
      Fetch.join(fa, fb).map { case (a, b) => f(a, b) }

    override def tuple2[A, B](fa: Fetch[A], fb: Fetch[B]): Fetch[(A, B)] =
      Fetch.join(fa, fb)
  }

  object Fetch extends FetchInterpreters {

    /**
      * Lift a plain value to the Fetch monad.
      */
    def pure[A](a: A): Fetch[A] =
      Free.pure(a)

    /**
      * Lift an exception to the Fetch monad.
      */
    def error[A](e: Throwable): Fetch[A] =
      Free.liftF(Thrown(e))

    /**
      * Given a value that has a related `DataSource` implementation, lift it
      * to the `Fetch` monad. When executing the fetch the data source will be
      * queried and the fetch will return its result.
      */
    def apply[I, A](i: I)(
        implicit DS: DataSource[I, A]
    ): Fetch[A] =
      Free.liftF(FetchOne[I, A](i, DS))

    /**
      * Given multiple values with a related `DataSource` lift them to the `Fetch` monad.
      */
    def multiple[I, A](i: I, is: I*)(implicit DS: DataSource[I, A]): Fetch[List[A]] =
      Free.liftF(FetchMany(NonEmptyList(i, is.toList), DS))

    /**
      * Given a non empty list of `FetchQuery`s, lift it to the `Fetch` monad. When executing
      * the fetch, data sources will be queried and the fetch will return an `InMemoryCache`
      * containing the results.
      */
    private[fetch] def concurrently(
        queries: NonEmptyList[FetchQuery[Any, Any]]): Fetch[InMemoryCache] =
      Free.liftF(Concurrent(queries))

    /**
      * Transform a list of fetches into a fetch of a list. It implies concurrent execution of fetches.
      */
    def sequence[I, A](ids: List[Fetch[A]]): Fetch[List[A]] =
      Applicative[Fetch].sequence(ids)

    /**
      * Apply a fetch-returning function to every element in a list and return a Fetch of the list of
      * results. It implies concurrent execution of fetches.
      */
    def traverse[A, B](ids: List[A])(f: A => Fetch[B]): Fetch[List[B]] =
      Applicative[Fetch].traverse(ids)(f)

    /**
      * Apply the given function to the result of the two fetches. It implies concurrent execution of fetches.
      */
    def map2[A, B, C](f: (A, B) => C)(fa: Fetch[A], fb: Fetch[B]): Fetch[C] =
      Applicative[Fetch].map2(fa, fb)(f)

    /**
      * Join two fetches from any data sources and return a Fetch that returns a tuple with the two
      * results. It implies concurrent execution of fetches.
      */
    def join[A, B](fl: Fetch[A], fr: Fetch[B]): Fetch[(A, B)] =
      Free.liftF(Join(fl, fr))

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

  private[fetch] implicit class DataSourceCast[A, B](val ds: DataSource[A, B]) extends AnyVal {
    def castDS[C, D]: DataSource[C, D] = ds.asInstanceOf[DataSource[C, D]]
  }

  private[fetch] implicit class NonEmptyListDetourList[A](val nel: NonEmptyList[A])
      extends AnyVal {
    def unsafeListOp[B](f: List[A] => List[B]): NonEmptyList[B] =
      NonEmptyList.fromListUnsafe(f(nel.toList))
  }
}
