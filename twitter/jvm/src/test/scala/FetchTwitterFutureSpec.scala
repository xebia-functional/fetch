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

import cats.Eval
import cats.syntax.cartesian._
import fetch._
import fetch.twitterFuture.implicits._
import io.catbird.util._
import org.scalatest.{FlatSpec, Matchers}
import com.twitter.util.{Await, Future}
import com.twitter.conversions.time._

class FetchTwitterFutureSpec extends FlatSpec with Matchers {
  import TestHelper._

  "TwFutureMonadError" should "execute an async fetch on a Future" in {
    val fetch: Fetch[Article]    = TestHelper.article(1)
    val article: Future[Article] = Fetch.run[Future](fetch)
    Await.result(article, 100.milliseconds) shouldEqual (Article(1, "An article with id 1"))
  }
  it should "allow for several async datasources to be combined" in {
    val fetch: Fetch[(Article, Author)] = for {
      art    <- article(1)
      author <- author(art)
    } yield (art, author)

    Await.result(Fetch.run[Future](fetch), 100.milliseconds) shouldEqual (Article(
      1,
      "An article with id 1"), Author(2, "@egg2"))
  }
  it should "execute a sync fetch" in {
    val fetch: Fetch[Int] = one(1)
    Await.result(Fetch.run[Future](fetch), 100.milliseconds) shouldEqual (1)
  }
  it should "be used as an applicative" in {
    import cats.syntax.cartesian._

    val fetch = (one(1) |@| one(2) |@| one(3)).map(_ + _ + _)
    val fut   = Fetch.run[Future](fetch)

    fut.map(_ shouldEqual 6)
  }

  "RerunnableMonadError" should "lift and execute an async fetch into a Rerunnable" in {
    val fetch: Fetch[Article]        = TestHelper.article(1)
    val article: Rerunnable[Article] = Fetch.run[Rerunnable](fetch)
    Await.result(article.run, 100.milliseconds) shouldEqual (Article(1, "An article with id 1"))
  }
  it should "run a sync fetch" in {
    val fetch: Fetch[Int]   = one(1)
    val rr: Rerunnable[Int] = Fetch.run[Rerunnable](fetch)
    Await.result(rr.run, 100.milliseconds) shouldEqual (1)
  }
  it should "allow for several async datasources to be combined" in {
    val fetch: Fetch[(Article, Author)] = for {
      art    <- article(1)
      author <- author(art)
    } yield (art, author)

    val rr: Rerunnable[(Article, Author)] = Fetch.run[Rerunnable](fetch)
    Await.result(rr.run, 100.milliseconds) shouldEqual (Article(1, "An article with id 1"), Author(
      2,
      "@egg2"))
    Await.result(rr.run, 100.milliseconds) shouldEqual (Article(1, "An article with id 1"), Author(
      2,
      "@egg2"))
  }
  it should "be used as an applicative" in {
    import cats.syntax.cartesian._

    val fetch: Fetch[Int] = (one(1) |@| one(2) |@| one(3)).map(_ + _ + _)
    val fut               = Fetch.run[Rerunnable](fetch)

    fut.map(_ shouldEqual 6)
  }
  it should "be usable as an applicative" in {
    import cats.syntax.cartesian._

    val fetch: Fetch[Int] = (one(1) |@| one(2) |@| one(3)).map(_ + _ + _)
    val fut               = Fetch.run[Rerunnable](fetch)

    fut.map(_ shouldEqual 6)
  }

  "evalToRunnable" should "convert an Eval into a Runnable" in {
    var dontDoThisAtHome: Int = 1000
    def evalFun: Int = {
      dontDoThisAtHome += 1
      dontDoThisAtHome
    }

    // this invokes evalFun as soon as the Now is created. subsequent
    // calls to the rerunnable return the memoized value
    val now       = Eval.now(evalFun)
    val rNow      = evalToRerunnable(now)
    val nowAnswer = dontDoThisAtHome
    Await.result(rNow.run) should be(nowAnswer)
    Await.result(rNow.run) should be(nowAnswer)

    // this invokes evalFun on first run. subsequent calls to the
    // run return the memoized value
    def laterValue: Int = evalFun
    val later           = Eval.later(laterValue)
    val rLater          = evalToRerunnable(later)
    val laterAnswer     = dontDoThisAtHome
    Await.result(rLater.run) should be(laterAnswer + 1)
    Await.result(rLater.run) should be(laterAnswer + 1)

    // each time rerunnable run is invoked evalFun is called
    val always       = Eval.always(evalFun)
    val rAlways      = evalToRerunnable(always)
    val alwaysAnswer = dontDoThisAtHome
    Await.result(rAlways.run) should be(alwaysAnswer + 1)
    Await.result(rAlways.run) should be(alwaysAnswer + 2)
  }
}
