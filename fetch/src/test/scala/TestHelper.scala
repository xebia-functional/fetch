/*
 * Copyright 2016-2020 47 Degrees Open Source <https://www.47deg.com>
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

  object One extends Data[Int, Int] {
    def name = "One"

    def source[F[_]: ConcurrentEffect]: DataSource[F, Int, Int] =
      new DataSource[F, Int, Int] {
        override def data = One

        override def CF = ConcurrentEffect[F]

        override def fetch(id: Int): F[Option[Int]] =
          CF.pure(Option(id))

        override def batch(ids: NonEmptyList[Int]): F[Map[Int, Int]] =
          CF.pure(
            ids.toList.map((v) => (v, v)).toMap
          )
      }
  }

  def one[F[_]: ConcurrentEffect](id: Int): Fetch[F, Int] =
    Fetch(id, One.source)

  object Many extends Data[Int, List[Int]] {
    def name = "Many"

    def source[F[_]: ConcurrentEffect]: DataSource[F, Int, List[Int]] =
      new DataSource[F, Int, List[Int]] {
        override def data = Many

        override def CF = ConcurrentEffect[F]

        override def fetch(id: Int): F[Option[List[Int]]] =
          CF.pure(Option(0 until id toList))
      }
  }

  def many[F[_]: ConcurrentEffect](id: Int): Fetch[F, List[Int]] =
    Fetch(id, Many.source)

  object AnotherOne extends Data[Int, Int] {
    def name = "Another one"

    def source[F[_]: ConcurrentEffect]: DataSource[F, Int, Int] =
      new DataSource[F, Int, Int] {
        override def data = AnotherOne

        override def CF = ConcurrentEffect[F]

        override def fetch(id: Int): F[Option[Int]] =
          CF.pure(Option(id))

        override def batch(ids: NonEmptyList[Int]): F[Map[Int, Int]] =
          CF.pure(
            ids.toList.map((v) => (v, v)).toMap
          )
      }
  }

  def anotherOne[F[_]: ConcurrentEffect](id: Int): Fetch[F, Int] =
    Fetch(id, AnotherOne.source)

  case class Never()

  object Never extends Data[Never, Int] {
    def name = "Never"

    def source[F[_]: ConcurrentEffect]: DataSource[F, Never, Int] =
      new DataSource[F, Never, Int] {
        override def data = Never

        override def CF = ConcurrentEffect[F]

        override def fetch(id: Never): F[Option[Int]] =
          CF.pure(None: Option[Int])
      }
  }

  def never[F[_]: ConcurrentEffect]: Fetch[F, Int] =
    Fetch(Never(), Never.source)

}
