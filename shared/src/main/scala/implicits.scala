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

import cats.ApplicativeError
import monix.eval.Task

object implicits {
  implicit val fetchTaskApplicativeError: ApplicativeError[Task, Throwable] =
    new ApplicativeError[Task, Throwable] {
      def pure[A](x: A): monix.eval.Task[A] =
        Task.pure(x)
      def ap[A, B](ff: monix.eval.Task[A => B])(fa: monix.eval.Task[A]): monix.eval.Task[B] =
        Task.mapBoth(ff, fa)((f, x) => f(x))
      def handleErrorWith[A](fa: monix.eval.Task[A])(
          f: Throwable => monix.eval.Task[A]): monix.eval.Task[A] =
        fa.onErrorHandleWith(f)
      def raiseError[A](e: Throwable): monix.eval.Task[A] =
        Task.raiseError(e)
    }
}
