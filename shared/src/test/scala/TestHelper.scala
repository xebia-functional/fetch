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

import scala.language.implicitConversions

import cats.effect._
import cats.temp.par._

import cats.data.NonEmptyList

import scala.collection.immutable.Map


object TestHelper {
  case class AnException() extends Throwable

  case class One(id: Int)

  object OneSource extends DataSource[One, Int] {
    override def name = "OneSource"

    override def fetch[F[_]](id: One)(
      implicit CF: ConcurrentEffect[F]
    ): F[Option[Int]] =
      CF.pure(Option(id.id))

    override def batch[F[_]](ids: NonEmptyList[One])(
      implicit CF: ConcurrentEffect[F], P: Par[F]
    ): F[Map[One, Int]] =
      CF.pure(
        ids.toList.map((v) => (v, v.id)).toMap
      )
  }

  def one[F[_] : ConcurrentEffect : ContextShift](id: Int): Fetch[F, Int] =
    Fetch(One(id), OneSource)

  case class Many(n: Int)

  implicit object ManySource extends DataSource[Many, List[Int]] {
    override def name = "ManySource"

    override def fetch[F[_] : ConcurrentEffect](id: Many): F[Option[List[Int]]] =
      ConcurrentEffect[F].pure(Option(0 until id.n toList))
  }

  def many[F[_] : ConcurrentEffect : ContextShift](id: Int): Fetch[F, List[Int]] =
    Fetch(Many(id), ManySource)

  case class AnotherOne(id: Int)

  implicit object AnotheroneSource extends DataSource[AnotherOne, Int] {
    override def name = "AnotherOneSource"

    override def fetch[F[_] : ConcurrentEffect](id: AnotherOne): F[Option[Int]] =
     ConcurrentEffect[F].pure(Option(id.id))
  }

  def anotherOne[F[_] : ConcurrentEffect : ContextShift](id: Int): Fetch[F, Int] =
    Fetch(AnotherOne(id), AnotheroneSource)

  // case class Never()
  // implicit object NeverSource extends DataSource[Never, Int] {
  //   override def name = "NeverSource"

  //   override def fetch(id: Never): IO[Option[Int]] =
  //     IO.pure(None : Option[Int])
  // }

  // def never(
  //   implicit C: ContextShift[IO]
  // ): Fetch[Int] = Fetch(Never(), NeverSource)

  // // Async DataSources

  // case class ArticleId(id: Int)
  // case class Article(id: Int, content: String) {
  //   def author: Int = id + 1
  // }

  // implicit object ArticleAsync extends DataSource[ArticleId, Article] {
  //   override def name = "ArticleAsync"

  //   override def fetch(id: ArticleId): IO[Option[Article]] =
  //     IO.async[Option[Article]]((cb) => {
  //       cb(
  //         Right(
  //           Option(Article(id.id, "An article with id " + id.id))
  //         )
  //       )
  //     })
  // }

  // def article(id: Int)(
  //   implicit C: ContextShift[IO]
  // ): Fetch[Article] = Fetch(ArticleId(id), ArticleAsync)

  // case class AuthorId(id: Int)
  // case class Author(id: Int, name: String)

  // implicit object AuthorAsync extends DataSource[AuthorId, Author] {
  //   override def name = "AuthorAsync"

  //   override def fetch(id: AuthorId): IO[Option[Author]] =
  //     IO.async((cb => {
  //       cb(
  //         Right(
  //           Option(Author(id.id, "@egg" + id.id))
  //         )
  //       )
  //     }))
  // }

  // def author(a: Article)(
  //   implicit C: ContextShift[IO]
  // ): Fetch[Author] = Fetch(AuthorId(a.author), AuthorAsync)

  // // Check Env

  // def countFetches(r: Request): Int =
  //   r.request match {
  //     case FetchOne(_, _)       => 1
  //     case Batch(ids, _)    => ids.toList.size
  //   }

  // def totalFetched(rs: Seq[Round]): Int =
  //   rs.map((round: Round) => round.queries.map(countFetches).sum).toList.sum

  // def countBatches(r: Request): Int =
  //   r.request match {
  //     case FetchOne(_, _)    => 0
  //     case Batch(_, _) => 1
  //   }

  // def totalBatches(rs: Seq[Round]): Int =
  //   rs.map((round: Round) => round.queries.map(countBatches).sum).toList.sum
}
