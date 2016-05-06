package fetch

import scala.collection.immutable.Map

import cats.{Applicative, Monad, MonadError, ~>}
import cats.data.{StateT, Const}
import cats.free.{Free}

import implicits._

/**
 * Primitive operations in the Fetch Free monad.
 */
sealed abstract class FetchOp[A] extends Product with Serializable

final case class Cached[A](a: A) extends FetchOp[A]
final case class FetchOne[I, A, M[_]](a: I, ds: DataSource[I, A, M]) extends FetchOp[A]
final case class FetchMany[I, A, M[_]](as: List[I], ds: DataSource[I, A, M]) extends FetchOp[List[A]]
final case class Concurrent[C <: DataSourceCache, E <: Env[C], M[_]](as: List[FetchMany[_, _, M]]) extends FetchOp[E]
final case class FetchError[A, E <: Throwable](err: E) extends FetchOp[A]

object `package` {

  // Types

  type DataSourceName = String

  type DataSourceIdentity = (DataSourceName, Any)

  type Fetch[A] = Free[FetchOp, A]

  type FetchInterpreter[M[_], C <: DataSourceCache] = {
    type f[x] = StateT[M, FetchEnv[C], x]
  }

  // Cache

 
  object Fetch extends FetchInstances with FetchInterpreters {
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
    def apply[I, A, M[_]](i: I)(
      implicit
      DS: DataSource[I, A, M]
    ): Fetch[A] =
      Free.liftF(FetchOne[I, A, M](i, DS))

    def deps[A, M[_]](f: Fetch[_]): List[FetchOp[_]] = {
      type FM = List[FetchOp[_]]

      f.foldMap[Const[FM, ?]](new (FetchOp ~> Const[FM, ?]) {
        def apply[X](x: FetchOp[X]): Const[FM, X] = x match {
          case one @ FetchOne(id, ds) => Const(List(FetchMany(List(id), ds.asInstanceOf[DataSource[Any, A, M]])))
          case conc @ Concurrent(as) => Const(as.asInstanceOf[FM])
          case cach @ Cached(a) => Const(List(cach))
          case _ => Const(List())
        }
      })(new Monad[Const[FM, ?]] {
        def pure[A](x: A): Const[FM, A] = Const(List())

        def flatMap[A, B](fa: Const[FM, A])(f: A => Const[FM, B]): Const[FM, B] = fa match {
          case Const(List(Cached(a))) => f(a.asInstanceOf[A])
          case other => fa.asInstanceOf[Const[FM, B]]
        }

      }).getConst
    }

    def combineDeps[M[_]](ds: List[FetchOp[_]]): List[FetchMany[_, _, M]] = {
      ds.foldLeft(Map.empty[Any, List[_]])((acc, op) => op match {
        case one @ FetchOne(id, ds) => acc.updated(ds, acc.get(ds).fold(List(id))(accids => accids :+ id))
        case many @ FetchMany(ids, ds) => acc.updated(ds, acc.get(ds).fold(ids)(accids => accids ++ ids))
        case _ => acc
      }).toList.map({
        case (ds, ids) => FetchMany[Any, Any, M](ids, ds.asInstanceOf[DataSource[Any, Any, M]])
      })
    }

    private[this] def concurrently[C <: DataSourceCache, E <: Env[C], M[_]](fa: Fetch[_], fb: Fetch[_]): Fetch[E] = {
      val fetches: List[FetchMany[_, _, M]] = combineDeps(deps(fa) ++ deps(fb))
      Free.liftF(Concurrent[C, E, M](fetches))
    }

    /**
     * Collect a list of fetches into a fetch of a list. It implies concurrent execution of fetches.
     */
    def collect[I, A](ids: List[Fetch[A]]): Fetch[List[A]] = {
      ids.foldLeft(Fetch.pure(List(): List[A]))((f, newF) =>
        Fetch.join(f, newF).map(t => t._1 :+ t._2))
    }

    /**
     * Apply a fetch-returning function to every element in a list and return a Fetch of the list of
     * results. It implies concurrent execution of fetches.
     */
    def traverse[A, B](ids: List[A])(f: A => Fetch[B]): Fetch[List[B]] =
      collect(ids.map(f))

    /**
     * Apply the given function to the result of the two fetches. It implies concurrent execution of fetches.
     */
    def map2[A, B, C](f: (A, B) => C)(fa: Fetch[A], fb: Fetch[B]): Fetch[C] =
      Fetch.join(fa, fb).map({ case (a, b) => f(a, b) })

    /**
     * Join two fetches from any data sources and return a Fetch that returns a tuple with the two
     * results. It implies concurrent execution of fetches.
     */
    def join[A, B, C <: DataSourceCache, E <: Env[C], M[_]](fl: Fetch[A], fr: Fetch[B])(
      implicit
      CC: Cache[C]
    ): Fetch[(A, B)] = {
      for {
        env <- concurrently[C, E, M](fl, fr)

        result <- {

          val simplify: FetchOp ~> FetchOp = new (FetchOp ~> FetchOp) {
            def apply[B](f: FetchOp[B]): FetchOp[B] = f match {
              case one @ FetchOne(id, ds) => {
                CC.get(env.cache, ds.identity(id)).fold(one: FetchOp[B])(b => Cached(b).asInstanceOf[FetchOp[B]])
              }
              case many @ FetchMany(ids, ds) => {
                val results = ids.flatMap(id =>
                  CC.get(env.cache, ds.identity(id)))

                if (results.size == ids.size) {
                  Cached(results)
                } else {
                  many
                }
              }
              case conc @ Concurrent(manies) => {
                val newManies = manies.filterNot({ fm =>
                  val ids: List[Any] = fm.as
                  val ds: DataSource[Any, _, M] = fm.ds.asInstanceOf[DataSource[Any, _, M]]

                  val results = ids.flatMap(id => {
                    CC.get(env.cache, ds.identity(id))
                  })

                  results.size == ids.size
                }).asInstanceOf[List[FetchMany[_, _, M]]]

                if (newManies.isEmpty)
                  Cached(env).asInstanceOf[FetchOp[B]]
                else
                  Concurrent(newManies).asInstanceOf[FetchOp[B]]
              }
              case other => other
            }
          }

          val sfl = fl.compile(simplify)
          val sfr = fr.compile(simplify)
          val remainingDeps = combineDeps(deps(sfl) ++ deps(sfr))

          if (remainingDeps.isEmpty) {
            for {
              a <- sfl
              b <- sfr
            } yield (a, b)
          } else {
            join[A, B, C, E, M](sfl, sfr)
          }
        }
      } yield result
    }

    /**
     * Run a `Fetch` with the given cache, returning a pair of the final environment and result
     * in the monad `M`.
     */
    def runFetch[A, C <: DataSourceCache, M[_]](
      fa: Fetch[A],
      cache: C
    )(
      implicit
      MM: MonadError[M, Throwable],
      CC: Cache[C]
    ): M[(FetchEnv[C], A)] = fa.foldMap[FetchInterpreter[M, C]#f](interpreter).run(FetchEnv(cache))

    /**
     * Run a `Fetch` with the given cache, returning the final environment in the monad `M`.
     */
    def runEnv[A, C <: DataSourceCache, M[_]](
      fa: Fetch[A],
      cache: C = InMemoryCache.empty
    )(
      implicit
      MM: MonadError[M, Throwable],
      CC: Cache[C]
    ): M[FetchEnv[C]] = MM.map(runFetch[A, C, M](fa, cache)(MM, CC))(_._1)

    /**
     * Run a `Fetch` with the given cache, the result in the monad `M`.
     */
    def run[A, C <: DataSourceCache, M[_]](
      fa: Fetch[A],
      cache: C = InMemoryCache.empty
    )(
      implicit
      MM: MonadError[M, Throwable],
      CC: Cache[C]
    ): M[A] = MM.map(runFetch[A, C, M](fa, cache)(MM, CC))(_._2)
  }

}

trait FetchInstances {

  implicit val fetchApplicative: Applicative[Fetch] = new Applicative[Fetch] {
    def pure[A](a: A): Fetch[A] = Fetch.pure(a)

    def ap[A, B](ff: Fetch[A => B])(fa: Fetch[A]): Fetch[B] =
      Fetch.join(ff, fa).map({ case (f, a) => f(a) })
  }

}

