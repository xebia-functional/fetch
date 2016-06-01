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

package fetch.monix

import fetch._

import _root_.monix.eval.Task
import _root_.monix.execution.{Scheduler, Cancelable}

object implicits {
  implicit val fetchTaskFetchMonadError: FetchMonadError[Task] = new FetchMonadError[Task] {
    override def runQuery[A](j: Query[A]): Task[A] = j match {
      case Sync(x) => pureEval(x)
      case Async(ac, timeout) =>
        Task.create(
            (scheduler, callback) => {

          scheduler.execute(new Runnable {
            def run() = ac(callback.onSuccess, callback.onError)
          })

          Cancelable.empty
        })
      case Ap(qf, qx) =>
        Task
          .zip2(runQuery(qf), runQuery(qx))
          .map({
            case (f, x) => f(x)
          })
    }

    def pure[A](x: A): Task[A] = Task.now(x)
    def handleErrorWith[A](fa: Task[A])(f: Throwable => Task[A]): Task[A] =
      fa.onErrorHandleWith(f)
    override def ap[A, B](f: Task[A => B])(x: Task[A]): Task[B] =
      Task.mapBoth(f, x)((f, x) => f(x))
    def raiseError[A](e: Throwable): Task[A] = Task.raiseError(e)
    def flatMap[A, B](fa: Task[A])(f: A => Task[B]): Task[B] =
      fa.flatMap(f)
  }
}
