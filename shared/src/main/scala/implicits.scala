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

import cats.MonadError
import cats.instances.future._
import scala.concurrent.{Promise, Future, ExecutionContext}

object implicits {

  implicit def fetchFutureFetchMonadError(
      implicit ec: ExecutionContext
  ): FetchMonadError[Future] =
    new FetchMonadError.FromMonadError[Future] {
      override def runQuery[A](j: Query[A]): Future[A] = j match {
        case Sync(e) => Future(e.value)
        case Async(ac, timeout) => {
          val p = Promise[A]()

          ec.execute(new Runnable {
            def run() = ac(p.trySuccess _, p.tryFailure _)
          })

          p.future
        }
        case Ap(qf, qx) =>
          runQuery(qf).zip(runQuery(qx)).map { case (f, x) => f(x) }
      }
    }
}
