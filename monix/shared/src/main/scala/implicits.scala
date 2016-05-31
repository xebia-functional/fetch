package fetch

import monix.eval.Task
import monix.execution.Cancelable
import monix.execution.Scheduler

object monix {
  implicit val fetchTaskFetchMonadError: FetchMonadError[Task] = new FetchMonadError[Task] {
    override def runQuery[A](j: Query[A]): Task[A] = j match {
      case Now(x)   => Task.now(x)
      case Later(x) => Task.evalAlways({ x() })
      case Async(ac) =>
        Task.create(
            (scheduler, callback) => {

          scheduler.execute(new Runnable {
            def run() = ac(callback.onSuccess, callback.onError)
          })

          Cancelable.empty
        })
    }

    def pure[A](x: A): Task[A] = Task.now(x)
    def handleErrorWith[A](fa: monix.eval.Task[A])(
        f: Throwable => monix.eval.Task[A]): monix.eval.Task[A] = fa.onErrorHandleWith(f)
    override def ap[A, B](f: Task[A => B])(x: Task[A]): Task[B] =
      Task.mapBoth(f, x)((f, x) => f(x))
    def raiseError[A](e: Throwable): monix.eval.Task[A] = Task.raiseError(e)
    def flatMap[A, B](fa: monix.eval.Task[A])(f: A => monix.eval.Task[B]): monix.eval.Task[B] =
      fa.flatMap(f)
  }
}
