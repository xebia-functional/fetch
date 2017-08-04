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

package fetch

import java.util.{Timer, TimerTask}
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import cats.instances.future._
import scala.concurrent.{ExecutionContext, Future, Promise}

object implicits {

  // Shared Timer object to schedule timeouts
  val timer: Timer = new Timer()

  implicit def fetchFutureFetchMonadError(implicit ec: ExecutionContext): FetchMonadError[Future] =
    new FetchMonadError.FromMonadError[Future] {
      override def runQuery[A](j: Query[A]): Future[A] = j match {

        case Sync(e) =>
          Future({e.value})

        case Async(ac, timeout) =>

          val p = Promise[A]()

          timeout match {

            // Handle the case where there is a finite timeout requested
            case finite: FiniteDuration =>

              // Timer task that completes the future when the timeout occurs
              // if it didn't complete already
              val timerTask = new TimerTask() {
                def run() : Unit = {
                  p.tryFailure(new TimeoutException())
                }
              }

              // Start the timeout Timer
              timer.schedule(timerTask, timeout.toMillis)

              // Execute the user's action
              ec.execute(new Runnable {
                def run() : Unit = {
                  ac(p.trySuccess, p.tryFailure)
                }
              })

            // No timeout 
            case _ =>

              // Execute the user's action
              ec.execute(new Runnable {
                def run() : Unit = {
                  ac(p.trySuccess, p.tryFailure)
                }
              })

          }

          p.future

        case Ap(qf, qx) =>
          runQuery(qf).zip(runQuery(qx)).map { case (f, x) => f(x) }
      }


    }
}
