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

import cats._
import cats.effect._
import cats.data.NonEmptyList

import scala.collection.immutable.Map


object TestHelper {
  case class AnException() extends Throwable

  case class One(id: Int)

  object One extends Data {
    implicit def source[F[_] : ConcurrentEffect]: DataSource[F, One, Int] = new DataSource[F, One, Int] {
      override def name = "OneSource"

      override def fetch(id: One)(
        implicit CF: ConcurrentEffect[F]
      ): F[Option[Int]] =
        CF.pure(Option(id.id))

      override def batch(ids: NonEmptyList[One])(
        implicit CF: ConcurrentEffect[F]
      ): F[Map[One, Int]] =
        CF.pure(
          ids.toList.map((v) => (v, v.id)).toMap
        )
    }
  }

  def one[F[_] : ConcurrentEffect](id: Int): Fetch[F, Int] =
    Fetch(One(id), One, One.source)

  case class Many(n: Int)

  object Many extends Data {
    implicit def source[F[_] : ConcurrentEffect]: DataSource[F, Many, List[Int]] = new DataSource[F, Many, List[Int]] {
      override def name = "ManySource"

      override def fetch(id: Many)(implicit C: ConcurrentEffect[F]): F[Option[List[Int]]] =
        C.pure(Option(0 until id.n toList))
    }
  }

  def many[F[_] : ConcurrentEffect](id: Int): Fetch[F, List[Int]] =
    Fetch(Many(id), Many, Many.source)

  case class AnotherOne(id: Int)

  object AnotherOne extends Data {
    implicit def source[F[_] : ConcurrentEffect]: DataSource[F, AnotherOne, Int] = new DataSource[F, AnotherOne, Int] {
      override def name = "AnotherOneSource"

      override def fetch(id: AnotherOne)(implicit C: ConcurrentEffect[F]): F[Option[Int]] =
        C.pure(Option(id.id))

      override def batch(ids: NonEmptyList[AnotherOne])(implicit C: ConcurrentEffect[F]): F[Map[AnotherOne, Int]] =
        C.pure(
          ids.toList.map((v) => (v, v.id)).toMap
        )
    }
  }

  def anotherOne[F[_] : ConcurrentEffect](id: Int): Fetch[F, Int] =
    Fetch(AnotherOne(id), AnotherOne, AnotherOne.source)

  case class Never()

  object Never extends Data {
    implicit def source[F[_] : ConcurrentEffect]: DataSource[F, Never, Int] = new DataSource[F, Never, Int] {
      override def name = "NeverSource"

      override def fetch(id: Never)(implicit C: ConcurrentEffect[F]): F[Option[Int]] =
        C.pure(None : Option[Int])
    }
  }

  def never[F[_] : ConcurrentEffect]: Fetch[F, Int] =
    Fetch(Never(), Never, Never.source)

}
