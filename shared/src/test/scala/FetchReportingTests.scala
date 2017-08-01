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

package fetch

import scala.concurrent._
import scala.concurrent.duration._

import org.scalatest._

import cats.MonadError
import cats.data.NonEmptyList
import cats.instances.list._

import fetch._
import fetch.implicits._

class FetchReportingTests extends AsyncFreeSpec with Matchers {
  import TestHelper._

  val ME = implicitly[FetchMonadError[Future]]

  implicit override def executionContext = ExecutionContext.Implicits.global

  "Plain values have no rounds of execution" in {
    val fetch: Fetch[Int] = Fetch.pure(42)
    Fetch.runEnv[Future](fetch).map(_.rounds.size shouldEqual 0)
  }

  "Single fetches are executed in one round" in {
    val fetch = one(1)
    Fetch.runEnv[Future](fetch).map(_.rounds.size shouldEqual 1)
  }

  "Single fetches are executed in one round per binding in a for comprehension" in {
    val fetch = for {
      o <- one(1)
      t <- one(2)
    } yield (o, t)

    Fetch.runEnv[Future](fetch).map(_.rounds.size shouldEqual 2)
  }

  "Single fetches for different data sources are executed in multiple rounds if they are in a for comprehension" in {
    val fetch: Fetch[(Int, List[Int])] = for {
      o <- one(1)
      m <- many(3)
    } yield (o, m)

    Fetch.runEnv[Future](fetch).map(_.rounds.size shouldEqual 2)
  }

  "Single fetches combined with cartesian are run in one round" in {
    import cats.syntax.apply._

    val fetch: Fetch[(Int, List[Int])] = (one(1), many(3)).tupled
    val fut                            = Fetch.runEnv[Future](fetch)

    fut.map(_.rounds.size shouldEqual 1)
  }

  "Single fetches combined with traverse are run in one round" in {
    import cats.syntax.traverse._

    val fetch: Fetch[List[Int]] = for {
      manies <- many(3)
      ones   <- manies.traverse(one)
    } yield ones

    val fut = Fetch.runEnv[Future](fetch)
    fut.map(_.rounds.size shouldEqual 2)
  }

  "The product of two fetches from the same data source implies batching" in {
    val fetch: Fetch[(Int, Int)] = Fetch.join(one(1), one(3))

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        env.rounds.size shouldEqual 1
      })
  }

  "The product of concurrent fetches of the same type implies everything fetched in batches" in {
    val fetch = Fetch.join(
      Fetch.join(
        for {
          a <- one(1)
          b <- one(2)
          c <- one(3)
        } yield c,
        for {
          a <- one(2)
          m <- many(4)
          c <- one(3)
        } yield c
      ),
      one(3)
    )

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        env.rounds.size shouldEqual 2
      })
  }

  "Every level of sequenced concurrent of concurrent fetches is batched" in {
    val fetch = Fetch.join(
      Fetch.join(
        for {
          a <- Fetch.sequence(List(one(2), one(3), one(4)))
          b <- Fetch.sequence(List(many(0), many(1)))
          c <- Fetch.sequence(List(one(9), one(10), one(11)))
        } yield c,
        for {
          a <- Fetch.sequence(List(one(5), one(6), one(7)))
          b <- Fetch.sequence(List(many(2), many(3)))
          c <- Fetch.sequence(List(one(12), one(13), one(14)))
        } yield c
      ),
      Fetch.sequence(List(one(15), one(16), one(17)))
    )

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        env.rounds.size shouldEqual 3
      })
  }
}
