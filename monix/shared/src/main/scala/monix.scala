/*
 * Copyright 2016-2017 47 Degrees, LLC. <http://www.47deg.com>
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

package fetch.monixTask

import fetch._

import cats.{Eval, Now, Later, Always, Monad}

import monix.cats._
import monix.eval.Task
import monix.execution.{Scheduler, Cancelable}

import scala.concurrent.duration._

object implicits {
  def evalToTask[A](e: Eval[A]): Task[A] = e match {
    case Now(x)       => Task.now(x)
    case l: Later[A]  => Task.evalOnce({ l.value })
    case a: Always[A] => Task.eval({ a.value })
    case other        => Task.evalOnce({ other.value })
  }

  implicit val fetchTaskFetchMonadError: FetchMonadError[Task] =
    new FetchMonadError.FromMonadError[Task] {
      override def runQuery[A](q: Query[A]): Task[A] = q match {
        case Sync(x) => evalToTask(x)
        case Async(ac, timeout) => {
          val task: Task[A] = Task.create((scheduler, callback) => {

            scheduler.execute(new Runnable {
              def run() = ac(callback.onSuccess, callback.onError)
            })

            Cancelable.empty
          })

          timeout match {
            case finite: FiniteDuration => task.timeout(finite)
            case _                      => task
          }
        }
        case Ap(qf, qx) =>
          Task.zip2(runQuery(qf), runQuery(qx)).map { case (f, x) => f(x) }
      }

      override def map[A, B](fa: Task[A])(f: A => B): Task[B] =
        fa.map(f)

      override def product[A, B](fa: Task[A], fb: Task[B]): Task[(A, B)] =
        Task.zip2(Task.fork(fa), Task.fork(fb))
    }
}
