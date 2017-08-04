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

import cats.{Applicative, Eval}
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

sealed trait Query[A] extends Product with Serializable

/** A query that can be satisfied synchronously. **/
final case class Sync[A](action: Eval[A]) extends Query[A]

/** A query that can only be satisfied asynchronously. **/
final case class Async[A](action: (Query.Callback[A], Query.Errback) => Unit, timeout: Duration)
    extends Query[A]

final case class Ap[A, B](ff: Query[A => B], fa: Query[A]) extends Query[B]

object Query {
  type Callback[A] = A => Unit
  type Errback     = Throwable => Unit

  def eval[A](e: Eval[A]): Query[A] = Sync(e)

  def sync[A](th: => A): Query[A] = Sync(Eval.later(th))

  def async[A](
      action: (Callback[A], Errback) => Unit,
      timeout: Duration = Duration.Inf
  ): Query[A] = Async(action, timeout)

  def fromFuture[A](fa: Future[A])(implicit ec: ExecutionContext): Query[A] =
    async { (ok, fail) =>
      fa.onComplete {
        case scala.util.Success(a) => ok(a)
        case scala.util.Failure(e) => fail(e)
      }
    }

  implicit val fetchQueryApplicative: Applicative[Query] = new Applicative[Query] {
    def pure[A](x: A): Query[A] = Sync(Eval.now(x))
    def ap[A, B](ff: Query[A => B])(fa: Query[A]): Query[B] =
      Ap(ff, fa)
  }
}
