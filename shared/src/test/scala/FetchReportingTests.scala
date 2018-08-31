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

import scala.concurrent.{ExecutionContext, Future}
import org.scalatest.{FreeSpec, Matchers}
import fetch._
import cats.effect._
import cats.instances.list._
import cats.syntax.all._

class FetchReportingTests extends FreeSpec with Matchers {
  import TestHelper._

  implicit val executionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  "Plain values have no rounds of execution" in {
    val fetch: Fetch[Int] = Fetch.pure(42)
    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync
    env.rounds.size shouldEqual 0
  }

  "Single fetches are executed in one round" in {
    val fetch = one(1)
    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync
    env.rounds.size shouldEqual 1
  }

  "Single fetches are executed in one round per binding in a for comprehension" in {
    val fetch = for {
      o <- one(1)
      t <- one(2)
    } yield (o, t)
    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync
    env.rounds.size shouldEqual 2
  }

  "Single fetches for different data sources are executed in multiple rounds if they are in a for comprehension" in {
    val fetch: Fetch[(Int, List[Int])] = for {
      o <- one(1)
      m <- many(3)
    } yield (o, m)

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync
    env.rounds.size shouldEqual 2
  }

  "Single fetches combined with cartesian are run in one round" in {
    import cats.syntax.apply._

    val fetch: Fetch[(Int, List[Int])] = (one(1), many(3)).tupled

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync
    env.rounds.size shouldEqual 1
  }

  "Single fetches combined with traverse are run in one round" in {
    import cats.syntax.traverse._

    val fetch: Fetch[List[Int]] = for {
      manies <- many(3)              // round 1
      ones   <- manies.traverse(one) // round 2
    } yield ones

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync
    env.rounds.size shouldEqual 2
  }

  "The product of two fetches from the same data source implies batching" in {
    import cats.syntax.apply._

    val fetch: Fetch[(Int, Int)] = (one(1), one(3)).tupled

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync
    env.rounds.size shouldEqual 1
  }

  "The product of concurrent fetches of the same type implies everything fetched in batches" in {
    import cats.syntax.apply._

    val aFetch = for {
      a <- one(1)  // round 1
      b <- one(2)  // round 2
      c <- one(3)  // round 3
    } yield c
    val anotherFetch = for {
      a <- one(2)  // round 1
      m <- many(4) // round 2
      c <- one(3)  // round 3
    } yield c
    val fetch = (
      (
        aFetch,
        anotherFetch
      ).tupled,
      one(3)       // round 1
    ).tupled

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync
    env.rounds.size shouldEqual 3
  }
}
