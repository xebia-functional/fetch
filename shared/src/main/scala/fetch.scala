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

import cats.{Applicative, Monad, ApplicativeError, MonadError, ~>, Eval, RecursiveTailRecM}
import cats.data.{StateT, Const, NonEmptyList, Writer, XorT}
import cats.free.Free
import cats.instances.list._
import cats.instances.map._
import cats.instances.option._
import cats.syntax.foldable._
import cats.syntax.functor._
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

/** Requests in Fetch Free monad.
  */
sealed trait FetchRequest extends Product with Serializable {
  def fullfilledBy(cache: DataSourceCache): Boolean
}

sealed trait FetchQuery[I, A] extends FetchRequest {
  def missingIdentities(cache: DataSourceCache): List[I]
  def dataSource: DataSource[I, A]
  def identities: NonEmptyList[I]
}

trait FetchException extends Throwable with Product with Serializable
case class NotFound(env: Env, request: FetchOne[_, _]) extends FetchException
case class MissingIdentities(env: Env, missing: Map[DataSourceName, List[Any]])
    extends FetchException
case class UnhandledException(err: Throwable) extends FetchException

/**
  * Primitive operations in the Fetch Free monad.
  */
sealed abstract class FetchOp[A] extends Product with Serializable

final case class Fetched[A](a: A) extends FetchOp[A]
final case class FetchOne[I, A](a: I, ds: DataSource[I, A])
    extends FetchOp[A]
    with FetchQuery[I, A] {
  override def fullfilledBy(cache: DataSourceCache): Boolean = {
    cache.get[A](ds.identity(a)).isDefined
  }
  override def missingIdentities(cache: DataSourceCache): List[I] = {
    cache.get[A](ds.identity(a)).fold(List(a))((res: A) => Nil)
  }
  override def dataSource: DataSource[I, A] = ds
  override def identities: NonEmptyList[I]  = NonEmptyList(a, Nil)
}

final case class FetchMany[I, A](as: NonEmptyList[I], ds: DataSource[I, A])
    extends FetchOp[List[A]]
    with FetchQuery[I, A] {
  override def fullfilledBy(cache: DataSourceCache): Boolean = {
    as.forall((i: I) => cache.get[A](ds.identity(i)).isDefined)
  }

  override def missingIdentities(cache: DataSourceCache): List[I] = {
    as.toList.distinct.filterNot(i => cache.get[A](ds.identity(i)).isDefined)
  }
  override def dataSource: DataSource[I, A] = ds
  override def identities: NonEmptyList[I]  = as
}
final case class Concurrent(as: List[FetchQuery[_, _]])
    extends FetchOp[DataSourceCache]
    with FetchRequest {
  override def fullfilledBy(cache: DataSourceCache): Boolean = {
    as.forall(_.fullfilledBy(cache))
  }
}
final case class Thrown[A](err: Throwable) extends FetchOp[A]

object `package` {
  type DataSourceName     = String
  type DataSourceIdentity = (DataSourceName, Any)

  type Fetch[A] = Free[FetchOp, A]

  trait FetchMonadError[M[_]] extends MonadError[M, FetchException] {
    def runQuery[A](q: Query[A]): M[A]
  }

  object FetchMonadError {
    def apply[M[_]](implicit ME: FetchMonadError[M]): FetchMonadError[M] = ME
  }

  type FetchInterpreter[M[_]] = {
    type f[x] = StateT[M, FetchEnv, x]
  }

  implicit val fetchApplicative: Applicative[Fetch] = new Applicative[Fetch] {
    def pure[A](a: A): Fetch[A] = Fetch.pure(a)

    def ap[A, B](ff: Fetch[A => B])(fa: Fetch[A]): Fetch[B] =
      Fetch.join(ff, fa).map({ case (f, a) => f(a) })

    override def product[A, B](fa: Fetch[A], fb: Fetch[B]): Fetch[(A, B)] =
      Fetch.join(fa, fb)

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
      * Given a list of `FetchRequest`s, lift it to the `Fetch` monad. When executing
      * the fetch, data sources will be queried and the fetch will return a `DataSourceCache`
      *  containing the results.
      */
    private[this] def concurrently(fetches: List[FetchQuery[_, _]]): Fetch[DataSourceCache] =
      Free.liftF(Concurrent(fetches))

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
    def join[A, B](fl: Fetch[A], fr: Fetch[B]): Fetch[(A, B)] = {
      def depFetches(fa: Fetch[_], fb: Fetch[_]): List[FetchQuery[_, _]] =
        combineQueries(dependentQueries(fa) ++ dependentQueries(fb))

      def joinWithFetches(
          fl: Fetch[A], fr: Fetch[B], fetches: List[FetchQuery[_, _]]): Fetch[(A, B)] =
        concurrently(fetches).flatMap(cache => joinH(fl, fr, cache))

      def joinH(fl: Fetch[A], fr: Fetch[B], cache: DataSourceCache): Fetch[(A, B)] = {
        val sfl = fl.compile(simplify(cache))
        val sfr = fr.compile(simplify(cache))

        val remainingDeps = depFetches(sfl, sfr)

        if (remainingDeps.isEmpty) Monad[Fetch].tuple2(sfl, sfr)
        else joinWithFetches(sfl, sfr, remainingDeps)
      }

      joinWithFetches(fl, fr, depFetches(fl, fr))
    }

    /**
      * Use a `DataSourceCache` to optimize a `FetchOp`.
      * If the cache contains all the fetch identities, the fetch doesn't need to be
      * executed and can be replaced by cached results.
      */
    private[this] def simplify(cache: DataSourceCache): (FetchOp ~> FetchOp) = {
      new (FetchOp ~> FetchOp) {
        def apply[B](fetchOp: FetchOp[B]): FetchOp[B] = fetchOp match {
          case one @ FetchOne(id, ds) =>
            cache.get[B](ds.identity(id)).fold(fetchOp)(b => Fetched(b))
          case many @ FetchMany(ids, ds) =>
            val fetched = ids.traverse(id => cache.get(ds.identity(id)))
            fetched.fold(fetchOp)(results => Fetched(results.toList))
          case conc @ Concurrent(manies) =>
            val newManies = manies.filterNot(_.fullfilledBy(cache))
            (if (newManies.isEmpty) Fetched(cache) else Concurrent(newManies)): FetchOp[B]
          case other => other
        }
      }
    }

    /**
      * Combine multiple queries so the resulting `List` only contains one `FetchQuery`
      * per `DataSource`.
      */
    private[this] def combineQueries(ds: List[FetchQuery[_, _]]): List[FetchQuery[_, _]] =
      ds.foldMap[Map[DataSource[_, _], NonEmptyList[Any]]] {
          case FetchOne(id, ds)   => Map(ds -> NonEmptyList.of[Any](id))
          case FetchMany(ids, ds) => Map(ds -> ids.widen[Any])
        }
        .mapValues { nel =>
          // workaround because NEL[Any].distinct needs Order[Any]
          NonEmptyList.fromListUnsafe(nel.toList.distinct)
        }
        .toList
        .map {
          case (ds, NonEmptyList(id, Nil)) => FetchOne(id, ds.castDS[Any, Any])
          case (ds, ids)                   => FetchMany(ids, ds.castDS[Any, Any])
        }

    private[this] type FetchOps       = List[FetchOp[_]]
    private[this] type KeepFetches[A] = Writer[FetchOps, A]
    private[this] type AnalyzeTop[A]  = XorT[KeepFetches, Unit, A]

    private[this] object AnalyzeTop {
      def stopWith[R](list: FetchOps): AnalyzeTop[R] =
        AnalyzeTop.stop(Writer.tell(list))

      def stopEmpty[R]: AnalyzeTop[R] =
        AnalyzeTop.stop(Writer.value(()))

      def stop[R](k: KeepFetches[Unit]): AnalyzeTop[R] =
        XorT.left[KeepFetches, Unit, R](k)

      def go[X](k: KeepFetches[X]): AnalyzeTop[X] =
        XorT.right[KeepFetches, Unit, X](k)
    }

    /**
      * Get a list of dependent `FetchQuery`s for a given `Fetch`.
      */
    private[this] def dependentQueries(f: Fetch[_]): List[FetchQuery[_, _]] = {
      val analyzeTop: FetchOp ~> AnalyzeTop = new (FetchOp ~> AnalyzeTop) {
        def apply[A](op: FetchOp[A]): AnalyzeTop[A] = op match {
          case fetc @ Fetched(c)     => AnalyzeTop.go(Writer(List(fetc), c))
          case one @ FetchOne(_, _)  => AnalyzeTop.stopWith(List(one))
          case conc @ Concurrent(as) => AnalyzeTop.stopWith(as.asInstanceOf[FetchOps])
          case _                     => AnalyzeTop.stopEmpty
        }
      }

      f.foldMap[AnalyzeTop](analyzeTop).value.written.collect {
        case one @ FetchOne(_, _)   => one
        case many @ FetchMany(_, _) => many
      }
    }

    class FetchRunner[M[_]] {
      def apply[A](
          fa: Fetch[A],
          cache: DataSourceCache = InMemoryCache.empty
      )(
          implicit MM: FetchMonadError[M],
          TR: RecursiveTailRecM[M]
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
          implicit MM: FetchMonadError[M],
          TR: RecursiveTailRecM[M]
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
          implicit MM: FetchMonadError[M],
          TR: RecursiveTailRecM[M]
      ): M[A] =
        fa.foldMap[FetchInterpreter[M]#f](interpreter).runA(FetchEnv(cache))
    }

    /**
      * Run a `Fetch` with the given cache, the result in the monad `M`.
      */
    def run[M[_]]: FetchRunnerA[M] = new FetchRunnerA[M]
  }

  private[fetch] implicit class DataSourceCast[A, B](ds: DataSource[A, B]) {
    def castDS[C, D]: DataSource[C, D] = ds.asInstanceOf[DataSource[C, D]]
  }
}
