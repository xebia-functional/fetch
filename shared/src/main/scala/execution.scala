/*
 * Copyright 2016-2018 47 Degrees, LLC. <http://www.47deg.com>
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

import cats.data.NonEmptyList
import cats.effect._
import cats.syntax.all._

private object FetchExecution {
  def parallel[F[_], A](effects: NonEmptyList[F[A]])(
    implicit CF: ConcurrentEffect[F]
  ): F[NonEmptyList[A]] =
    effects.traverse(CF.start(_)).flatMap(fibers =>
      fibers.traverse(_.join).onError({ case _ => fibers.traverse_(_.cancel) })
    )
}

