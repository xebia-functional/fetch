package fetch

import cats.{ MonadError, ~>, Eval }
import cats.data.{ State, StateT }
import cats.std.option._
import cats.std.list._
import cats.syntax.cartesian._
import cats.syntax.traverse._
import cats.free.{ Free }

trait DataSource[I, A, M[_]] {
  def identity: String = this.toString
  def fetchMany(ids: List[I]): M[Map[I, A]]
}

trait Cache[T]{
  def makeKey[M[_]](k: Any, ds: DataSource[_, _, M]): (String, Any) =
    (ds.identity, k)
  def update[I, A](c: T, k: (String, I), v: A): T
  def get[I](c: T, k: (String, I)): Option[Any]
}

case class FetchFailure[C, I](
  cache: Option[C],
  ids: List[I]
)(implicit CC: Cache[C]) extends Throwable

object algebra {
  sealed abstract class FetchOp[A] extends Product with Serializable
  final case class FetchOne[I, A, M[_]](a: I, ds: DataSource[I, A, M]) extends FetchOp[A]
  final case class FetchMany[I, A, M[_]](as: List[I], ds: DataSource[I, A, M]) extends FetchOp[List[A]]
  final case class Result[A](a: A) extends FetchOp[A]
  final case class FetchError[A, E <: Throwable](err: E)() extends FetchOp[A]
}


object types {
  import algebra.FetchOp

  type Fetch[A] = Free[FetchOp, A]

  type FetchOpSTC[M[_], C] = {
    type f[x] = StateT[M, C, x]
  }
}

object cache {
  // no cache
  case class NoCache()

  implicit object NoCacheImpl extends Cache[NoCache]{
    override def get[I](c: NoCache, k: (String, I)): Option[Any] = None
    override def update[I, A](c: NoCache, k: (String, I), v: A): NoCache = c
  }

  // in-memory cache
  type InMemoryCache = Map[Any, Any]

  object InMemoryCache {
    def empty: InMemoryCache = Map.empty[Any, Any]

    def apply(results: (Any, Any)*): InMemoryCache =
      results.foldLeft(empty)({
        case (c, (k, v)) => c.updated(k, v)
      })
  }

  implicit object InMemoryCacheImpl extends Cache[InMemoryCache]{
    override def get[I](c: InMemoryCache, k: (String, I)): Option[Any] = c.get(k)
    override def update[I, A](c: InMemoryCache, k: (String, I), v: A): InMemoryCache = c.updated(k, v)
  }
}

object Fetch {
  import algebra._
  import types._
  import cache._
  import interpreters._

  def pure[A](a: A): Fetch[A] =
    Free.liftF(Result(a))

  def error[A](e: Throwable): Fetch[A] =
    Free.liftF(FetchError(e))

  def apply[I, A, M[_]](i: I)(
    implicit DS: DataSource[I, A, M]
  ): Fetch[A] =
    Free.liftF(FetchOne[I, A, M](i, DS))

  def collect[I, A, M[_]](ids: List[I])(
    implicit DS: DataSource[I, A, M]
  ): Fetch[List[A]] =
    Free.liftF(FetchMany[I, A, M](ids, DS))

  /// xxx: List[B] -> (B -> Fetch[A]) -> Fetch[List[A]]
  def traverse[I, A, B, M[_]](ids: List[B])(f: B => I)(
    implicit DS: DataSource[I, A, M]
  ): Fetch[List[A]] =
    collect(ids.map(f))

  def coalesce[I, A, M[_]](fl: I, fr: I)(
    implicit
      DS: DataSource[I, A, M]
  ): Fetch[(A, A)] =
    Free.liftF(FetchMany[I, A, M](List(fl, fr), DS)).map(l => (l(0), l(1)))

  def join[I, R, A, B, M[_]](fl: I, fr: R)(
    implicit
      DS: DataSource[I, A, M],
    DSS: DataSource[R, B, M]
  ): Fetch[(A, B)] =
    (Fetch(fl) |@| Fetch(fr)).tupled

  def run[I, A, M[_]](fa: Fetch[A])(
    implicit
    MM: MonadError[M, Throwable]
  ): M[A] = runCached[I, A, NoCache, M](fa, NoCache())(MM, NoCacheImpl)

  def runCached[I, A, C, M[_]](
    fa: Fetch[A],
    cache: C
  )(
    implicit
      MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): M[A] = fa.foldMap[FetchOpSTC[M, C]#f](cached).runA(cache)

  def runWith[I, A, M[_]](
    fa: Fetch[A],
    i: FetchOp ~> M
  )(
    implicit
    MM: MonadError[M, Throwable]
  ): M[A] = fa foldMap i
}

object interpreters {
  import algebra._
  import types._
  import cache._

  def cached[I, C, M[_]](
    implicit
      MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): FetchOp ~> FetchOpSTC[M, C]#f = {
    new (FetchOp ~> FetchOpSTC[M, C]#f) {
      def apply[A](fa: FetchOp[A]): FetchOpSTC[M, C]#f[A] = {
        StateT[M, C, A] { cache => fa match {
          case Result(a) => MM.pure((cache, a))
          case FetchError(e) => MM.raiseError(e)
          case FetchOne(id: I, ds) => {
            CC.get(cache, CC.makeKey[Any](id, ds)).fold[M[(C, A)]](
              MM.flatMap(ds.fetchMany(List(id)).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
                res.get(id).fold[M[(C, A)]](MM.raiseError(FetchFailure(Option(cache), List(id))))(result => {
                  MM.pure((CC.update(cache, CC.makeKey[Any](id, ds), result), result))
                })
              })
            )(cached => {
              MM.pure((cache, cached.asInstanceOf[A]))
            })
          }
          case FetchMany(ids: List[I], ds) => {
            val newIds = ids.distinct.filterNot(i => CC.get(cache, CC.makeKey[Any](i, ds)).isDefined)
            if (newIds.isEmpty)
              MM.pureEval(Eval.later((cache, ids.flatMap(id => CC.get(cache, CC.makeKey[Any](id, ds))))))
            else {
              MM.flatMap(ds.fetchMany(newIds).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
                ids.map(res.get(_)).sequence.fold[M[(C, A)]](
                  MM.raiseError(FetchFailure(Option(cache), newIds))
                )(results => {
                  val newCache = res.foldLeft(cache)({
                    case (c, (k, v)) => CC.update(c, CC.makeKey[Any](k, ds), v)
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
}
