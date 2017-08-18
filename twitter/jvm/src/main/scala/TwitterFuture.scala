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

package fetch.twitterFuture

import fetch._
import scala.concurrent.duration.FiniteDuration

object implicits {

  import cats._
  import com.twitter.util.{Duration, Future, FuturePool, Promise, Timer}
  import io.catbird.util._

  def evalToRerunnable[A](e: Eval[A]): Rerunnable[A] = e match {
    case Now(x)       => Rerunnable.const(x)
    case l: Later[A]  => Rerunnable.fromFuture({ Future(l.value) })
    case a: Always[A] => Rerunnable({ a.value })
    case e            => Rerunnable.fromFuture(Future(e.value))
  }

  implicit def fetchRerunnableMonadError(
      implicit pool: FuturePool = FuturePool.interruptibleUnboundedPool
  ): FetchMonadError[Rerunnable] =
    new FetchMonadError.FromMonadError[Rerunnable] {
      override def runQuery[A](j: Query[A]): Rerunnable[A] = j match {
        case Sync(e) => evalToRerunnable(e)
        case Async(ac, timeout) =>
          Rerunnable.fromFuture {
            val p: Promise[A] = Promise()
            pool(ac(p setValue _, p setException _))
            timeout match {
              case _: FiniteDuration =>
                p.raiseWithin(Duration(timeout.length, timeout.unit))(Timer.Nil)
              case _ => p
            }
          }
        case Ap(qf, qx) =>
          runQuery(qf).product(runQuery(qx)) map { case (f, x) => f(x) }
      }
    }

  implicit def fetchTwFutureMonadError(
      implicit pool: FuturePool = FuturePool.interruptibleUnboundedPool
  ): FetchMonadError[Future] =
    new FetchMonadError.FromMonadError[Future] {
      override def runQuery[A](j: Query[A]): Future[A] = j match {
        case Sync(e) => Future(e.value)
        case Async(ac, timeout) =>
          val p: Promise[A] = Promise()
          pool(ac(p setValue _, p setException _))
          timeout match {
            case _: FiniteDuration =>
              p.raiseWithin(Duration(timeout.length, timeout.unit))(Timer.Nil)
            case _ => p
          }
        case Ap(qf, qx) =>
          ap(runQuery(qf))(runQuery(qx))
      }
    }

}
