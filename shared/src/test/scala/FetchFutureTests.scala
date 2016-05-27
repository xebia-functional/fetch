/*
 * Copyright 2016 47 Degrees, LLC. <http://www.47deg.com>
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

import scala.concurrent._
import scala.concurrent.duration._

import org.scalatest._

import monix.eval._
import monix.execution.Scheduler
import cats.data.NonEmptyList
import cats.std.list._
import fetch._

class FetchFutureTests extends AsyncFreeSpec with Matchers {
  implicit def executionContext = Scheduler.Implicits.global
  override def newInstance      = new FetchFutureTests

  case class ArticleId(id: Int)
  case class Article(id: Int, content: String) {
    def author: Int = id + 1
  }

  implicit object ArticleFuture extends DataSource[ArticleId, Article] {
    override def name = "ArticleFuture"
    override def fetchOne(id: ArticleId): Task[Option[Article]] =
      Task.pure(Option(Article(id.id, "An article with id " + id.id)))
    override def fetchMany(ids: NonEmptyList[ArticleId]): Task[Map[ArticleId, Article]] = {
      Task.now({
        ids.unwrap.map(tid => (tid, Article(tid.id, "An article with id " + tid.id))).toMap
      })
    }
  }

  def article(id: Int): Fetch[Article] = Fetch(ArticleId(id))

  case class AuthorId(id: Int)
  case class Author(id: Int, name: String)

  implicit object AuthorFuture extends DataSource[AuthorId, Author] {
    override def name = "AuthorFuture"
    override def fetchOne(id: AuthorId): Task[Option[Author]] =
      Task.now(Option(Author(id.id, "@egg" + id.id)))
    override def fetchMany(ids: NonEmptyList[AuthorId]): Task[Map[AuthorId, Author]] = {
      Task.now({
        ids.unwrap.map(tid => (tid, Author(tid.id, "@egg" + tid.id))).toMap
      })
    }
  }

  def author(a: Article): Fetch[Author] = Fetch(AuthorId(a.author))

  def toFuture[A](task: Task[A]): Future[A] = {
    val promise: Promise[A] = Promise()
    task.runAsync(
        new Callback[A] {
      def onSuccess(value: A): Unit    = { promise.success(value); () }
      def onError(ex: Throwable): Unit = { promise.failure(ex); () }
    })
    promise.future
  }

  "We can interpret a fetch into a future" in {
    val fetch: Fetch[Article] = article(1)

    val task: Task[Article]  = Fetch.run(fetch)
    val fut: Future[Article] = toFuture(task)

    fut.map(_ shouldEqual Article(1, "An article with id 1"))
  }

  "We can combine several data sources and interpret a fetch into a future" in {
    val fetch: Fetch[(Article, Author)] = for {
      art    <- article(1)
      author <- author(art)
    } yield (art, author)

    val fut: Future[(Article, Author)] = toFuture(Fetch.run(fetch))

    fut.map(_ shouldEqual (Article(1, "An article with id 1"), Author(2, "@egg2")))
  }

  "We can use combinators in a for comprehension and interpret a fetch into a future" in {
    val fetch: Fetch[List[Article]] = for {
      articles <- Fetch.traverse(List(1, 1, 2))(article)
    } yield articles

    val fut: Future[List[Article]] = toFuture(Fetch.run(fetch))

    fut.map(
        _ shouldEqual List(
            Article(1, "An article with id 1"),
            Article(1, "An article with id 1"),
            Article(2, "An article with id 2")
        )
    )
  }

  "We can use combinators and multiple sources in a for comprehension and interpret a fetch into a future" in {
    val fetch = for {
      articles <- Fetch.traverse(List(1, 1, 2))(article)
      authors  <- Fetch.traverse(articles)(author)
    } yield (articles, authors)

    val fut: Future[(List[Article], List[Author])] =
      toFuture(Fetch.run(fetch, InMemoryCache.empty))

    fut.map(
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
    )
  }
}
