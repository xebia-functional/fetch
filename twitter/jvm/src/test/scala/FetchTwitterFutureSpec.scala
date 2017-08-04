/*
 * Copyright 2016-2017 47 Degrees, LLC. <http://www.47deg.com>
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

package fetch.twitterFuture

import cats._, data._
import cats.implicits._
import fetch._
import io.catbird.util._
import org.scalatest._
import com.twitter.util.{Await, Future}
import com.twitter.conversions.time._

class FetchTwitterFutureSpec extends FlatSpec with Matchers {

  import fetch.twitterFuture.implicits._

  case class ArticleId(id: Int)
  case class Article(id: Int, content: String) {
    def author: Int = id + 1
  }

  implicit object ArticleFuture extends DataSource[ArticleId, Article] {
    override def name = "ArticleFuture"
    override def fetchOne(id: ArticleId): Query[Option[Article]] =
      Query.async((ok, fail) ⇒ {
        ok(Option(Article(id.id, "An article with id " + id.id)))
      })
    override def fetchMany(ids: NonEmptyList[ArticleId]): Query[Map[ArticleId, Article]] =
      batchingNotSupported(ids)
  }

  def article(id: Int): Fetch[Article] = Fetch(ArticleId(id))

  case class AuthorId(id: Int)
  case class Author(id: Int, name: String)

  implicit object AuthorFuture extends DataSource[AuthorId, Author] {
    override def name = "AuthorFuture"
    override def fetchOne(id: AuthorId): Query[Option[Author]] =
      Query.async((ok, fail) ⇒ {
        ok(Option(Author(id.id, "@egg" + id.id)))
      })
    override def fetchMany(ids: NonEmptyList[AuthorId]): Query[Map[AuthorId, Author]] =
      batchingNotSupported(ids)
  }

  def author(a: Article): Fetch[Author] = Fetch(AuthorId(a.author))

  "TwFutureMonadError" should "execute an async fetch on a Future" in {
    val fetch: Fetch[Article]    = Fetch(ArticleId(1))
    val article: Future[Article] = Fetch.run[Future](fetch)
    Await.result(article, 100.milliseconds) shouldEqual (Article(1, "An article with id 1"))
  }
  it should "allow for several async datasources to be combined" in {
    val fetch: Fetch[(Article, Author)] = for {
      art    ← article(1)
      author ← author(art)
    } yield (art, author)

    Await.result(Fetch.run[Future](fetch), 100.milliseconds) shouldEqual (Article(
      1,
      "An article with id 1"), Author(2, "@egg2"))
  }

  "RerunnableMonadError" should "lift and execute an async fetch into a Rerunnable" in {
    val fetch: Fetch[Article]        = Fetch(ArticleId(1))
    val article: Rerunnable[Article] = Fetch.run[Rerunnable](fetch)
    Await.result(article.run, 100.milliseconds) shouldEqual (Article(1, "An article with id 1"))
  }
  it should "allow for several async datasources to be combined" in {
    val fetch: Fetch[(Article, Author)] = for {
      art    ← article(1)
      author ← author(art)
    } yield (art, author)

    val rr: Rerunnable[(Article, Author)] = Fetch.run[Rerunnable](fetch)
    Await.result(rr.run, 100.milliseconds) shouldEqual (Article(1, "An article with id 1"), Author(
      2,
      "@egg2"))
    Await.result(rr.run, 100.milliseconds) shouldEqual (Article(1, "An article with id 1"), Author(
      2,
      "@egg2"))
  }

  "evalToRunnable" should "convert an Eval into a Runnable" in {
    var dontDoThisAtHome: Int = 1000
    def evalFun: Int = {
      dontDoThisAtHome += 1
      dontDoThisAtHome
    }

    // this invokes evalFun as soon as the Now is created. subsequent
    // calls to the rerunnable return the memoized value
    val now       = Now(evalFun)
    val rNow      = evalToRerunnable(now)
    val nowAnswer = dontDoThisAtHome
    Await.result(rNow.run) should be(nowAnswer)
    Await.result(rNow.run) should be(nowAnswer)

    // this invokes evalFun on first run. subsequent calls to the
    // run return the memoized value
    def laterValue: Int = evalFun
    val later           = Later(laterValue)
    val rLater          = evalToRerunnable(later)
    val laterAnswer     = dontDoThisAtHome
    Await.result(rLater.run) should be(laterAnswer + 1)
    Await.result(rLater.run) should be(laterAnswer + 1)

    // each time rerunnable run is invoked evalFun is called
    val always       = Always(evalFun)
    val rAlways      = evalToRerunnable(always)
    val alwaysAnswer = dontDoThisAtHome
    Await.result(rAlways.run) should be(alwaysAnswer + 1)
    Await.result(rAlways.run) should be(alwaysAnswer + 2)
  }
}
