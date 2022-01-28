/*
 * Copyright 2016-2022 47 Degrees Open Source <https://www.47deg.com>
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

import cats._
import cats.effect._
import fetch.Fetch

object syntax {

  /**
   * Implicit syntax to lift any value to the context of Fetch via pure
   */
  implicit class FetchIdSyntax[A](val a: A) extends AnyVal {

    def fetch[F[_]: Concurrent]: Fetch[F, A] =
      Fetch.pure[F, A](a)
  }

  /**
   * Implicit syntax to lift exception to Fetch errors
   */
  implicit class FetchExceptionSyntax[B](val a: Throwable) extends AnyVal {

    def fetch[F[_]: Concurrent]: Fetch[F, B] =
      Fetch.error[F, B](a)
  }

  implicit class FetchSeqBatchSyntax[F[_]: Monad, A](fetches: Seq[Fetch[F, A]]) {

    def batchAll: Fetch[F, List[A]] = Fetch.batchAll(fetches: _*)
  }

  implicit class SeqSyntax[A](val as: Seq[A]) extends AnyVal {

    def batchAllWith[F[_]: Monad, B](f: A => Fetch[F, B]) = Fetch.batchAll(as.map(f): _*)
  }
}
