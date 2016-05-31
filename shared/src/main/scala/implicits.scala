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

import monix.eval.Task
import monix.execution.Cancelable
import monix.execution.Scheduler
import cats.ApplicativeError
import scala.concurrent.{Promise, Future, ExecutionContext}

object implicits {
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

  implicit def fetchFutureFetchMonadError(
      implicit ec: ExecutionContext
  ): FetchMonadError[Future] = new FetchMonadError[Future] {
    override def runQuery[A](j: Query[A]): Future[A] = j match {
      case Now(x)   => Future.successful(x)
      case Later(x) => Future({ x() })
      case Async(ac) => {
          val p = Promise[A]()

          ec.execute(new Runnable {
            def run() = ac(p.trySuccess _, p.tryFailure _)
          })

          p.future
        }
    }
    def pure[A](x: A): Future[A] = Future.successful(x)
    def handleErrorWith[A](fa: Future[A])(f: Throwable => Future[A]): Future[A] =
      fa.recoverWith({ case t => f(t) })
    def raiseError[A](e: Throwable): Future[A]                     = Future.failed(e)
    def flatMap[A, B](fa: Future[A])(f: A => Future[B]): Future[B] = fa.flatMap(f)
  }
}
