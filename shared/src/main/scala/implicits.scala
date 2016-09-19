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

import cats.{Eval, MonadError, FlatMap}
import cats.instances.FutureInstances
import scala.concurrent.{Promise, Future, ExecutionContext}

object implicits extends FutureInstances {
  implicit def fetchFutureFetchMonadError(
      implicit ec: ExecutionContext,
      ME: MonadError[Future, Throwable],
      FM: FlatMap[Future]
  ): FetchMonadError[Future] = new FetchMonadError[Future] {
    override def tailRecM[A, B](a: A)(
        f: A => scala.concurrent.Future[Either[A, B]]): scala.concurrent.Future[B] =
      FM.tailRecM(a)(f)
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
        runQuery(qf)
          .zip(runQuery(qx))
          .map({
            case (f, x) => f(x)
          })
    }
    def pure[A](x: A): Future[A] = Future.successful(x)
    def handleErrorWith[A](fa: Future[A])(f: FetchException => Future[A]): Future[A] =
      fa.recoverWith({ case t: FetchException => f(t) })
    def raiseError[A](e: FetchException): Future[A]                = Future.failed(e)
    def flatMap[A, B](fa: Future[A])(f: A => Future[B]): Future[B] = fa.flatMap(f)
  }
}
