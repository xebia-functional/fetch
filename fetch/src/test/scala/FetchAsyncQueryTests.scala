/*
 * Copyright 2016-2021 47 Degrees Open Source <https://www.47deg.com>
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

import cats.instances.list._
import cats.effect._
import cats.syntax.all._

import fetch._

class FetchAsyncQueryTests extends FetchSpec {
  import DataSources._

  "We can interpret an async fetch into an IO" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, Article] =
      article(1)

    val io = Fetch.run[IO](fetch)

    io.map(_ shouldEqual Article(1, "An article with id 1")).unsafeToFuture
  }

  "We can combine several async data sources and interpret a fetch into an IO" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, (Article, Author)] =
      for {
        art    <- article(1)
        author <- author(art)
      } yield (art, author)

    val io = Fetch.run[IO](fetch)

    io.map(_ shouldEqual (Article(1, "An article with id 1"), Author(2, "@egg2"))).unsafeToFuture
  }

  "We can use combinators in a for comprehension and interpret a fetch from async sources into an IO" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Article]] =
      for {
        articles <- List(1, 1, 2).traverse(article[F])
      } yield articles

    val io = Fetch.run[IO](fetch)

    io.map(
      _ shouldEqual List(
        Article(1, "An article with id 1"),
        Article(1, "An article with id 1"),
        Article(2, "An article with id 2")
      )
    ).unsafeToFuture
  }

  "We can use combinators and multiple sources in a for comprehension and interpret a fetch from async sources into an IO" in {
    def fetch[F[_]: ConcurrentEffect] =
      for {
        articles <- List(1, 1, 2).traverse(article[F])
        authors  <- articles.traverse(author[F])
      } yield (articles, authors)

    val io = Fetch.run[IO](fetch)

    io.map(
      _ shouldEqual (
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
      )
    ).unsafeToFuture
  }
}

object DataSources {
  case class ArticleId(id: Int)
  case class Article(id: Int, content: String) {
    def author: Int = id + 1
  }

  object Article extends Data[ArticleId, Article] {
    def name = "Articles"

    implicit def async[F[_]: ConcurrentEffect]: DataSource[F, ArticleId, Article] =
      new DataSource[F, ArticleId, Article] {
        override def CF = ConcurrentEffect[F]

        override def data = Article

        override def fetch(id: ArticleId): F[Option[Article]] =
          CF.async_[Option[Article]] { (cb) =>
            cb(
              Right(
                Option(Article(id.id, "An article with id " + id.id))
              )
            )
          }
      }
  }

  def article[F[_]: ConcurrentEffect](id: Int): Fetch[F, Article] =
    Fetch(ArticleId(id), Article.async)

  case class AuthorId(id: Int)
  case class Author(id: Int, name: String)

  object Author extends Data[AuthorId, Author] {
    def name = "Authors"

    implicit def async[F[_]: ConcurrentEffect]: DataSource[F, AuthorId, Author] =
      new DataSource[F, AuthorId, Author] {
        override def CF = ConcurrentEffect[F]

        override def data = Author

        override def fetch(id: AuthorId): F[Option[Author]] =
          CF.async_((cb => {
            cb(
              Right(
                Option(Author(id.id, "@egg" + id.id))
              )
            )
          }))
      }
  }

  def author[F[_]: ConcurrentEffect](a: Article): Fetch[F, Author] =
    Fetch(AuthorId(a.author), Author.async)
}
