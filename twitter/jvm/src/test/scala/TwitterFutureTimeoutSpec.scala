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

import cats.data.NonEmptyList
import com.twitter.util.{Await, Duration, Future, TimeoutException}
import com.twitter.conversions.time._
import fetch._
import fetch.implicits._
import fetch.twitterFuture.implicits._
import org.scalatest._
// import scala.concurrent.duration._

class TwiterFutureTimeoutSpac
    extends FlatSpec
    with Matchers
    with OptionValues
    with Inside
    with Inspectors {

  case class ArticleId(id: Int)
  case class Article(id: Int, content: String)

  def article(id: Int)(implicit DS: DataSource[ArticleId, Article]): Fetch[Article] =
    Fetch(ArticleId(id))

  // A sample datasource with configurable delay and timeout

  case class ConfigurableTimeoutDatasource(timeout: Duration, delay: Duration)
      extends DataSource[ArticleId, Article] {
    override def name = "ArticleFuture"
    override def fetchOne(id: ArticleId): Query[Option[Article]] =
      Query.async(
        (ok, fail) => {
          Thread.sleep(delay.inMillis)
          ok(Option(Article(id.id, "An article with id " + id.id)))
        },
        scala.concurrent.duration.Duration.fromNanos(timeout.inNanoseconds)
      )
    override def fetchMany(ids: NonEmptyList[ArticleId]): Query[Map[ArticleId, Article]] =
      batchingNotSupported(ids)
  }

  "FetchMonadError[Future]" should "fail with timeout when a datasource does not complete in time" in {

    implicit val dsWillTimeout = ConfigurableTimeoutDatasource(250 milliseconds, 750 milliseconds)

    val fetch: Fetch[Article] = article(1)
    val fut: Future[Article]  = Fetch.run[Future](fetch)

    assertThrows[TimeoutException] {
      Await.result(fut, 1 seconds)
    }

  }

  it should "not fail with timeout when a datasource does complete in time" in {

    implicit val dsWillTimeout = ConfigurableTimeoutDatasource(750 milliseconds, 250 milliseconds)

    val fetch: Fetch[Article] = article(1)
    val fut: Future[Article]  = Fetch.run[Future](fetch)

    fut.map { _ shouldEqual Article(1, "An article with id 1") }
  }

  it should "not fail with timeout when infinite timeout specified" in {

    implicit val dsWillTimeout = ConfigurableTimeoutDatasource(Duration.Top, 250 milliseconds)

    val fetch: Fetch[Article] = article(1)
    val fut: Future[Article]  = Fetch.run[Future](fetch)

    fut.map { _ shouldEqual Article(1, "An article with id 1") }
  }

}
