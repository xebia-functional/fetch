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

package cats.free.fetch

import cats.{~>, Id}
import cats.data.EitherK
import cats.free.Free

import scala.annotation.tailrec

object FreeExt {

  @tailrec
  def getSuspend[F[_]](free: Free[F, _]): Option[F[_]] = free match {
    case Free.Pure(p)              => None
    case Free.Suspend(fa)          => Some(fa)
    case Free.FlatMapped(free2, _) => getSuspend(free2)
  }

  /**
   * Is the first step a `Pure` ?
   *
   * Could be implemented using `resume` as :
   * {{{
   * val liftCoyoneda: F ~> Coyoneda[F, ?] = λ[(F ~> Coyoneda[F, ?])](Coyoneda.lift(_))
   * free.compile[Coyoneda[F, ?]](liftCoyoneda).resume.toOption
   * }}}
   */
  def getPure[F[_], A](free: Free[F, A]): Option[A] = free match {
    case Free.Pure(p)              => Some(p)
    case Free.Suspend(fa)          => None
    case Free.FlatMapped(free2, _) => None
  }

  def modifySuspend[F[_], A](free: Free[F, A])(f: F ~> EitherK[F, Id, ?]): Free[F, A] =
    free match {
      case pure @ Free.Pure(_)          => pure
      case Free.Suspend(fa)             => f(fa).run.fold(Free.liftF(_), Free.Pure(_))
      case Free.FlatMapped(free2, cont) => Free.FlatMapped(modifySuspend(free2)(f), cont).step
    }

  def print[F[_], A](free: Free[F, A]): String = free match {
    case Free.Pure(p)     => s"Pure($p)"
    case Free.Suspend(fa) => s"Suspend($fa)"
    case Free.FlatMapped(free2, _) =>
      s"""FlatMapped(
      |  ${print(free2)},
      |  <fb => Free>)""".stripMargin
  }

}
