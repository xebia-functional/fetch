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

import scala.concurrent.{ExecutionContext, Future}

import org.scalatest.{AsyncFreeSpec, Matchers}

import cats.instances.list._
import cats.effect._
import cats.syntax.all._
import cats.temp.par._

import fetch._

class FetchAsyncQueryTests extends AsyncFreeSpec with Matchers {
  import DataSources._

  implicit override val executionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  "We can interpret an async fetch into an IO" in {
    def fetch[F[_] : ConcurrentEffect]: Fetch[F, Article] =
      article(1)

    val io = Fetch.run[IO](fetch)

    io.map(_ shouldEqual Article(1, "An article with id 1")).unsafeToFuture
  }

  "We can combine several async data sources and interpret a fetch into an IO" in {
    def fetch[F[_] : ConcurrentEffect]: Fetch[F, (Article, Author)] = for {
      art    <- article(1)
      author <- author(art)
    } yield (art, author)

    val io = Fetch.run[IO](fetch)

    io.map(_ shouldEqual (Article(1, "An article with id 1"), Author(2, "@egg2"))).unsafeToFuture
  }

  "We can use combinators in a for comprehension and interpret a fetch from async sources into an IO" in {
    def fetch[F[_] : ConcurrentEffect]: Fetch[F, List[Article]] = for {
      articles <- List(1, 1, 2).traverse(article[F])
    } yield articles

    val io = Fetch.run[IO](fetch)

    io.map(_ shouldEqual List(
      Article(1, "An article with id 1"),
      Article(1, "An article with id 1"),
      Article(2, "An article with id 2")
    )).unsafeToFuture
  }

  "We can use combinators and multiple sources in a for comprehension and interpret a fetch from async sources into an IO" in {
    def fetch[F[_] : ConcurrentEffect] = for {
      articles <- List(1, 1, 2).traverse(article[F])
      authors  <- articles.traverse(author[F])
    } yield (articles, authors)

    val io = Fetch.run[IO](fetch)

    io.map(_ shouldEqual (
      List(
        Article(1, "An article with id 1"),
        Article(1, "An article with id 1"),
        Article(2, "An article with id 2")
      ),
      List(
        Author(2, "@egg2"),
        Author(2, "@egg2"),
        Author(3, "@egg3")
      )
    )).unsafeToFuture
  }
}

object DataSources {
  case class ArticleId(id: Int)
  case class Article(id: Int, content: String) {
    def author: Int = id + 1
  }

  object ArticleAsync extends DataSource[ArticleId, Article] {
    override def name = "ArticleAsync"

    override def fetch[F[_] : ConcurrentEffect : Par](id: ArticleId): F[Option[Article]] =
      Async[F].async[Option[Article]]((cb) => {
        cb(
          Right(
            Option(Article(id.id, "An article with id " + id.id))
          )
        )
      })
  }

  def article[F[_] : ConcurrentEffect](id: Int): Fetch[F, Article] =
    Fetch(ArticleId(id), ArticleAsync)

  case class AuthorId(id: Int)
  case class Author(id: Int, name: String)

  object AuthorAsync extends DataSource[AuthorId, Author] {
    override def name = "AuthorAsync"

    override def fetch[F[_] : ConcurrentEffect : Par](id: AuthorId): F[Option[Author]] =
      Async[F].async((cb => {
        cb(
          Right(
            Option(Author(id.id, "@egg" + id.id))
          )
        )
      }))
  }

  def author[F[_] : ConcurrentEffect](a: Article): Fetch[F, Author] =
    Fetch(AuthorId(a.author), AuthorAsync)
}
