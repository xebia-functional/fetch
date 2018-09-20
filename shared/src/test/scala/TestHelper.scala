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
import cats.data.NonEmptyList
import cats.effect._
import cats.instances.int._
import cats.temp.par._

import scala.collection.immutable.Map


object TestHelper {
  case class AnException() extends Throwable

  case class One(id: Int)

  def OneSource[F[_]: Applicative] = new DataSource[F, One, Int] {
    val name = "OneSource"

    def fetch(id: One): F[Option[Int]] =
      Applicative[F].pure(Option(id.id))

    def batch(ids: NonEmptyList[One]): F[Map[One, Int]] =
      Applicative[F].pure(
        ids.toList.map((v) => (v, v.id)).toMap
      )
  }

  def one[F[_] : Concurrent](id: Int): Fetch[F, Int] =
    Fetch(One(id), OneSource)

  case class Many(n: Int)

  def ManySource[F[_]: Monad: Par] = new BatchedDataSource[F, Many, List[Int]] {
    val name = "ManySource"

    def fetch(id: Many): F[Option[List[Int]]] =
      Applicative[F].pure(Option(0 until id.n toList))
  }

  def many[F[_]: Concurrent: Par](id: Int): Fetch[F, List[Int]] =
    Fetch(Many(id), ManySource)

  case class AnotherOne(id: Int)

  def AnotheroneSource[F[_]: Monad: Par] = new DataSource[F, AnotherOne, Int] {
    val name = "AnotherOneSource"

    def fetch(id: AnotherOne): F[Option[Int]] =
      Applicative[F].pure(Option(id.id))

    def batch(ids: NonEmptyList[AnotherOne]): F[Map[AnotherOne, Int]] =
      Applicative[F].pure(
        ids.toList.map((v) => (v, v.id)).toMap
      )
  }

  def anotherOne[F[_] : Concurrent: Par](id: Int): Fetch[F, Int] =
    Fetch(AnotherOne(id), AnotheroneSource)

  case class Never()

  def NeverSource[F[_]: Monad: Par] = new BatchedDataSource[F, Never, Int] {
    def name = "NeverSource"

    def fetch(id: Never): F[Option[Int]] =
      Applicative[F].pure(Option.empty[Int])
  }

  def never[F[_] : Concurrent: Par]: Fetch[F, Int] =
    Fetch(Never(), NeverSource)

  // Check Env

  def countFetches(r: Request): Int =
    r.request match {
      case FetchOne(_, _)       => 1
      case Batch(ids, _)    => ids.toList.size
    }

  def totalFetched(rs: Seq[Round]): Int =
    rs.map((round: Round) => round.queries.map(countFetches).reduce).toList.sum

  def countBatches(r: Request): Int =
    r.request match {
      case FetchOne(_, _)    => 0
      case Batch(_, _) => 1
    }

  def totalBatches(rs: Seq[Round]): Int =
    rs.map((round: Round) => round.queries.map(countBatches).reduce).toList.sum
}
