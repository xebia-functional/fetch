package fetch

import cats.{ MonadError, ~>, Eval }
import cats.data.{ State, StateT }
import cats.std.option._
import cats.std.list._
import cats.syntax.cartesian._
import cats.syntax.traverse._
import cats.free.{ Free }

// Errors

case class FetchFailure() extends FetchError {
  override def throwable: Throwable = new Exception("Fetch failed")
}

// Data source

trait DataSource[I, A, M[_]] {
  def fetchMany(ids: List[I]): M[Map[I, A]]
}

// Fetch algebra

trait FetchError {
//  def ids: List[I]
  def throwable: Throwable
}

sealed abstract class FetchOp[A] extends Product with Serializable
final case class One[I, A, M[_]](a: I, ds: DataSource[I, A, M]) extends FetchOp[A]
final case class Collect[I, A, M[_]](as: List[I], ds: DataSource[I, A, M]) extends FetchOp[List[A]]
final case class Result[A](a: A) extends FetchOp[A]
final case class Errored[A](e: FetchError) extends FetchOp[A]

object FetchType {
  type Fetch[A] = Free[FetchOp, A]
}

object Fetch {
  import FetchType._

  def pure[A](a: A): Fetch[A] =
    Free.liftF(Result(a))

  def error[A](e: FetchError): Fetch[A] =
    Free.liftF(Errored(e))

  def apply[I, A, M[_]](i: I)(
    implicit DS: DataSource[I, A, M]
  ): Fetch[A] =
    Free.liftF(One[I, A, M](i, DS))

  def collect[I, A, M[_]](ids: List[I])(
    implicit DS: DataSource[I, A, M]
  ): Fetch[List[A]] =
    Free.liftF(Collect[I, A, M](ids, DS))

  def traverse[I, A, B, M[_]](ids: List[B])(f: B => I)(
    implicit DS: DataSource[I, A, M]
  ): Fetch[List[A]] =
    collect(ids.map(f))

  def coalesce[I, A, M[_]](fl: I, fr: I)(
    implicit
      DS: DataSource[I, A, M]
  ): Fetch[(A, A)] =
    Free.liftF(Collect[I, A, M](List(fl, fr), DS)).map(l => (l(0), l(1)))

  def join[I, R, A, B, M[_]](fl: I, fr: R)(
    implicit
      DS: DataSource[I, A, M],
    DSS: DataSource[R, B, M]
  ): Fetch[(A, B)] =
    (Fetch(fl) |@| Fetch(fr)).tupled

  def interpreter[I, A, M[_]](
    implicit
      MM: MonadError[M, FetchError]
  ): FetchOp ~> M = {
    new (FetchOp ~> M) {
      def apply[A](fa: FetchOp[A]): M[A] = fa match {
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

  object Cache {
    def empty: Cache = Map.empty[Any, Any]

    def apply(tuples: (Any, Any)*): Cache =
      tuples.foldLeft(Cache.empty)({
        case (c, (k, v)) => c.updated(k, v)
      })
  }

  type FetchOpST[M[_]] = {
    type f[x] = StateT[M, Cache, x]
  }

  def cachedInterpreter[I, M[_]](
    implicit
      MM: MonadError[M, FetchError]
  ): FetchOp ~> FetchOpST[M]#f = {
    new (FetchOp ~> FetchOpST[M]#f) {
      def apply[A](fa: FetchOp[A]): FetchOpST[M]#f[A] = {
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

  def run[I, A, M[_]](fa: Fetch[A])(
    implicit
    MM: MonadError[M, FetchError]
  ): M[A] = fa foldMap interpreter

  def runCached[I, A, M[_]](
    fa: Fetch[A],
    cache: Cache = Cache.empty
  )(
    implicit
    MM: MonadError[M, FetchError]
  ): M[A] = fa.foldMap[FetchOpST[M]#f](cachedInterpreter).runA(cache)

  def runWith[I, A, M[_]](
    fa: Fetch[A],
    i: FetchOp ~> M
  )(
    implicit
    MM: MonadError[M, FetchError]
  ): M[A] = fa foldMap i
}
