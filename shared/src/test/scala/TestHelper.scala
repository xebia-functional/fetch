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
import cats.implicits._
import cats.data.NonEmptyList

object TestHelper {

  case class AnException() extends Throwable

  case class One(id: Int)
  implicit object OneSource extends DataSource[One, Int] {
    override def name = "OneSource"

    override def fetch[F[_] : ConcurrentEffect](id: One): F[Option[Int]] =
      ConcurrentEffect[F].delay(Option(id.id))
  }

  def one(id: Int)(
    implicit C: Concurrent[IO]
  ): Fetch[Int] = Fetch(One(id))

  case class Many(n: Int)
  implicit object ManySource extends DataSource[Many, List[Int]] {
    override def name = "ManySource"

    override def fetch[F[_] : ConcurrentEffect](id: Many): F[Option[List[Int]]] =
      ConcurrentEffect[F].delay(Option(0 until id.n toList))
  }
  def many(id: Int)(
    implicit C: Concurrent[IO]
  ): Fetch[List[Int]] = Fetch(Many(id))

  case class AnotherOne(id: Int)
  implicit object AnotheroneSource extends DataSource[AnotherOne, Int] {
    override def name = "AnotherOneSource"

    override def fetch[F[_] : ConcurrentEffect](id: AnotherOne): F[Option[Int]] =
      ConcurrentEffect[F].delay(Option(id.id))
  }

  def anotherOne(id: Int)(
    implicit C: Concurrent[IO]
  ): Fetch[Int] = Fetch(AnotherOne(id))

  case class Never()
  implicit object NeverSource extends DataSource[Never, Int] {
    override def name = "NeverSource"

    override def fetch[F[_] : ConcurrentEffect](id: Never): F[Option[Int]] =
      ConcurrentEffect[F].delay(None : Option[Int])
  }

  // Async DataSources

  case class ArticleId(id: Int)
  case class Article(id: Int, content: String) {
    def author: Int = id + 1
  }

  implicit object ArticleAsync extends DataSource[ArticleId, Article] {
    override def name = "ArticleAsync"

    override def fetch[F[_] : ConcurrentEffect](id: ArticleId): F[Option[Article]] =
      ConcurrentEffect[F].async[Option[Article]]((cb) => {
        cb(
          Right(
            Option(Article(id.id, "An article with id " + id.id))
          )
        )
      })
  }

  def article(id: Int)(
    implicit C: Concurrent[IO]
  ): Fetch[Article] = Fetch(ArticleId(id))

  case class AuthorId(id: Int)
  case class Author(id: Int, name: String)

  implicit object AuthorAsync extends DataSource[AuthorId, Author] {
    override def name = "AuthorAsync"

    override def fetch[F[_] : ConcurrentEffect](id: AuthorId): F[Option[Author]] =
      ConcurrentEffect[F].async((cb => {
        cb(
          Right(
            Option(Author(id.id, "@egg" + id.id))
          )
        )
      }))
  }

  def author(a: Article)(
    implicit C: Concurrent[IO]
  ): Fetch[Author] = Fetch(AuthorId(a.author))

  // Check Env

  def countFetches(r: Request): Int =
    r.request match {
      case FetchOne(_, _)       => 1
      case Batch(ids, _)    => ids.toList.size
    }

  def totalFetched(rs: Seq[Round]): Int =
    rs.map((round: Round) => round.queries.map(countFetches).sum).toList.sum

  def countBatches(r: Request): Int =
    r.request match {
      case FetchOne(_, _)    => 0
      case Batch(_, _) => 1
    }

  def totalBatches(rs: Seq[Round]): Int =
    rs.map((round: Round) => round.queries.map(countBatches).sum).toList.sum
}
