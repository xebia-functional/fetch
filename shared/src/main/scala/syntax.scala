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

import cats.effect._

object syntax {

  // /** Implicit syntax to lift any value to the context of Fetch via pure */
  // implicit class FetchIdSyntax[F[_], A](val a: A) extends AnyVal {

  //   def fetch: Fetch[F, A] =
  //     Fetch.pure(a)
  // }


  // /** Implicit syntax to lift exception to Fetch errors */
  // implicit class FetchExceptionSyntax[F[_]](val a: Throwable) extends AnyVal {

  //   def fetch[B]: Fetch[F, B] =
  //     Fetch.error[F, B](a)
  // }

  // /** Implicit syntax for Fetch ops */
  // implicit class FetchSyntax[F[_], A](val fa: Fetch[F, A]) extends AnyVal {
  //   def runFetch(
  //     implicit
  //       CS: ContextShift[IO],
  //       T: Timer[IO]
  //   ): F[A] =
  //     Fetch.run[F, A](fa)

  //   def runEnv[F[_]](
  //     implicit
  //       CS: ContextShift[IO],
  //       T: Timer[IO]
  //   ): F[(Env[F], A)] =
  //     Fetch.runEnv[F, A](fa)

  //   def runCache[F[_]](
  //     implicit
  //       CS: ContextShift[IO],
  //       T: Timer[IO]
  //   ): F[(DataSourceCache, A)] =
  //     Fetch.runCache[F, A](fa)
  // }
}

