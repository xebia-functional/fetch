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

import cats.effect.Effect
import cats.MonadError

trait FetchMonadError[M[_]] extends Effect[M] {
  def runQuery[A](q: Query[A]): M[A]
}

object FetchMonadError {
  def apply[M[_]](implicit ME: FetchMonadError[M]): FetchMonadError[M] = ME

  // abstract class FromMonadError[M[_]](implicit ME: MonadError[M, Throwable])
  //     extends FetchMonadError[M] {

  //   def pure[A](x: A): M[A] =
  //     ME.pure(x)

  //   def flatMap[A, B](fa: M[A])(f: A => M[B]): M[B] =
  //     ME.flatMap(fa)(f)

  //   def tailRecM[A, B](a: A)(f: A => M[Either[A, B]]): M[B] =
  //     ME.tailRecM(a)(f)

  //   def handleErrorWith[A](fa: M[A])(f: FetchException => M[A]): M[A] =
  //     ME.handleErrorWith(fa)({ case fe: FetchException => f(fe) })

  //   def raiseError[A](e: FetchException): M[A] =
  //     ME.raiseError(e)
  // }
}
