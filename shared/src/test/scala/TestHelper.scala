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
import cats.implicits._
import cats.data.NonEmptyList

object TestHelper {

  case class One(id: Int)
  implicit object OneSource extends DataSource[One, Int] {
    override def name = "OneSource"
    override def fetchOne[F[_] : Effect](id: One): F[Option[Int]] =
      Effect[F].delay(Option(id.id))
    override def fetchMany[F[_] : Effect](ids: NonEmptyList[One]): F[Map[One, Int]] =
      Effect[F].delay(ids.toList.map(one => (one, one.id)).toMap)
  }
  def one(id: Int): Fetch[Int] = Fetch(One(id))

  // case class AnotherOne(id: Int)
  // implicit object AnotheroneSource extends DataSource[AnotherOne, Int] {
  //   override def name = "AnotherOneSource"
  //   override def fetchOne(id: AnotherOne): Query[Option[Int]] =
  //     Query.sync(Option(id.id))
  //   override def fetchMany(ids: NonEmptyList[AnotherOne]): Query[Map[AnotherOne, Int]] =
  //     Query.sync(ids.toList.map(anotherone => (anotherone, anotherone.id)).toMap)
  // }
  // def anotherOne(id: Int): Fetch[Int] = Fetch(AnotherOne(id))

  case class Many(n: Int)
  implicit object ManySource extends DataSource[Many, List[Int]] {
    override def name = "ManySource"
    override def fetchOne[F[_] : Effect](id: Many): F[Option[List[Int]]] =
      Effect[F].delay(Option(0 until id.n toList))

    override def fetchMany[F[_] : Effect](ids: NonEmptyList[Many]): F[Map[Many, List[Int]]] =
      Effect[F].delay(ids.toList.map(m => (m, 0 until m.n toList)).toMap)
  }
  def many(id: Int): Fetch[List[Int]] = Fetch(Many(id))

  case class Never()
  implicit object NeverSource extends DataSource[Never, Int] {
    override def name = "NeverSource"

    override def fetchOne[F[_] : Effect](id: Never): F[Option[Int]] =
      Effect[F].pure(None : Option[Int])

    override def fetchMany[F[_] : Effect](ids: NonEmptyList[Never]): F[Map[Never, Int]] =
      Effect[F].pure(Map.empty[Never, Int])
  }

  // Async DataSources

  // case class ArticleId(id: Int)
  // case class Article(id: Int, content: String) {
  //   def author: Int = id + 1
  // }

  // implicit object ArticleAsync extends DataSource[ArticleId, Article] {
  //   override def name = "ArticleAsync"
  //   override def fetchOne(id: ArticleId): Query[Option[Article]] =
  //     Query.async((ok, fail) => {
  //       ok(Option(Article(id.id, "An article with id " + id.id)))
  //     })
  //   override def fetchMany(ids: NonEmptyList[ArticleId]): Query[Map[ArticleId, Article]] =
  //     batchingNotSupported(ids)
  // }

  // def article(id: Int): Fetch[Article] = Fetch(ArticleId(id))

  // case class AuthorId(id: Int)
  // case class Author(id: Int, name: String)

  // implicit object AuthorAsync extends DataSource[AuthorId, Author] {
  //   override def name = "AuthorAsync"
  //   override def fetchOne(id: AuthorId): Query[Option[Author]] =
  //     Query.async((ok, fail) => {
  //       ok(Option(Author(id.id, "@egg" + id.id)))
  //     })
  //   override def fetchMany(ids: NonEmptyList[AuthorId]): Query[Map[AuthorId, Author]] =
  //     batchingNotSupported(ids)
  // }

  // def author(a: Article): Fetch[Author] = Fetch(AuthorId(a.author))

  // // check Env

  // def requestFetches(r: FetchRequest): Int =
  //   r match {
  //     case FetchOne(_, _)       => 1
  //     case FetchMany(ids, _)    => ids.toList.size
  //     case Concurrent(requests) => requests.toList.map(requestFetches).sum
  //   }

  // def totalFetched(rs: Seq[Round]): Int =
  //   rs.map((round: Round) => requestFetches(round.request)).toList.sum

  // def requestBatches(r: FetchRequest): Int =
  //   r match {
  //     case FetchOne(_, _)    => 0
  //     case FetchMany(ids, _) => 1
  //     case Concurrent(requests) =>
  //       requests.toList.count {
  //         case FetchMany(_, _) => true
  //         case _               => false
  //       }
  //   }

  // def totalBatches(rs: Seq[Round]): Int =
  //   rs.map((round: Round) => requestBatches(round.request)).toList.sum
}
