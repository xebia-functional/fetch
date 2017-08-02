
package fetch.twitterFuture

import fetch._

object implicits {

  import com.twitter.util.{Future,FuturePool,Promise}
  import io.catbird.util._

  implicit def fetchRerunableMonadError: FetchMonadError[Rerunnable] =
    new FetchMonadError.FromMonadError[Rerunnable] {
      override def runQuery[A](j: Query[A]): Rerunnable[A] = j match {
        case Sync(e) ⇒ Rerunnable { e.value }
        case Async(ac, timeout) ⇒
          Rerunnable.fromFuture {
            val p: Promise[A] = Promise()
            FuturePool.unboundedPool(ac(p setValue _, p setException _))
            p
          }
        case Ap(qf, qx) ⇒
          runQuery(qf).product(Rerunnable(qx)) map { case (f, x) ⇒ f(x) }
      }
    }

  implicit def fetchTwFutureMonadError: FetchMonadError[Future] =
    new FetchMonadError.FromMonadError[Future] {
      override def runQuery[A](j: Query[A]): Future[A] = j match {
        case Sync(e) ⇒ Future(e.value)
        case Async(ac, timeout) ⇒
          val p: Promise[A] = Promise()
          FuturePool.unboundedPool(ac(p setValue _, p setException _))
          p
        case Ap(qf, qx) ⇒
          runQuery(qf).join(runQuery(qx)).map { case (f, x) ⇒ f(x) }
      }
    }

}
