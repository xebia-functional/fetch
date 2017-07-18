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
import cats.MonadError
import scala.concurrent.{ExecutionContext, Future, Promise}

object implicits {

  // Shared Timer object to schedule timeouts
  val timer: Timer = new Timer()


 implicit def fetchFutureFetchMonadError(implicit ec: ExecutionContext): FetchMonadError[Future] =
    new FetchMonadError.FromMonadError[Future] {
      override def runQuery[A](j: Query[A]): Future[A] = j match {

        case Sync(e) => {
          Future.successful({e.value})
        }

        case Async(ac, timeout) => {

          val p = Promise[A]()

          timeout match {
            case finite: FiniteDuration =>

              // Timer task that completes the future when the timeout occurs
              // if it didn't complete already
              val timerTask = new TimerTask() {
                def run() : Unit = {
                  p.synchronized {
                    if(!p.isCompleted) {
                      p.failure(new TimeoutException())
                    }
                  }
                }
              }

              // Callback on success, has no effect if the timeout occured
              val successComplete : Query.Callback[A] = {
                a =>
                p.synchronized {
                  if(!p.isCompleted) {
                    p.success(a)
                  }
                }
              }

              // Callback on failure, has no effect after timeout
              val failureComplete : Query.Errback = {
                err =>
                p.synchronized {
                  if(!p.isCompleted) {

                    p.failure(err)
                  }
                }
              }

              // Start the timeout Timer
              implicits.timer.schedule(timerTask, timeout.toMillis)

              // Execute the user's action
              ec.execute(new Runnable {
                def run() = {
                  ac(successComplete, failureComplete)
                }
              })

            // No timeout 
            case _ =>

              // Execute the user's action
              ec.execute(new Runnable {
                def run() = {
                  ac(p.trySuccess _, p.tryFailure _)
                }
              })

          }



          p.future
        }
        case Ap(qf, qx) =>
          runQuery(qf).zip(runQuery(qx)).map { case (f, x) => f(x) }
      }


    }
}
