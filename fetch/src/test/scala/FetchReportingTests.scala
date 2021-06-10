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

package fetch

import cats.effect._
import cats.instances.list._
import cats.syntax.all._

class FetchReportingTests extends FetchSpec {
  import TestHelper._

  "Plain values have no rounds of execution" in {
    def fetch[F[_]: Concurrent] =
      Fetch.pure[F, Int](42)

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      log.rounds.size shouldEqual 0
    }).unsafeToFuture()
  }

  "Single fetches are executed in one round" in {
    def fetch[F[_]: Concurrent] =
      one(1)

    val io = Fetch.runLog[IO](fetch[IO])

    io.map({ case (log, result) =>
      log.rounds.size shouldEqual 1
    }).unsafeToFuture()
  }

  "Single fetches are executed in one round per binding in a for comprehension" in {
    def fetch[F[_]: Concurrent] =
      for {
        o <- one(1)
        t <- one(2)
      } yield (o, t)

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      log.rounds.size shouldEqual 2
    }).unsafeToFuture()
  }

  "Single fetches for different data sources are executed in multiple rounds if they are in a for comprehension" in {
    def fetch[F[_]: Concurrent] =
      for {
        o <- one(1)
        m <- many(3)
      } yield (o, m)

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      log.rounds.size shouldEqual 2
    }).unsafeToFuture()
  }

  "Single fetches combined with cartesian are run in one round" in {
    def fetch[F[_]: Concurrent] =
      (one(1), many(3)).tupled

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      log.rounds.size shouldEqual 1
    }).unsafeToFuture()
  }

  "Single fetches combined with traverse are run in one round" in {
    def fetch[F[_]: Concurrent] =
      for {
        manies <- many(3) // round 1
        ones   <- manies.traverse(one[F]) // round 2
      } yield ones

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      log.rounds.size shouldEqual 2
    }).unsafeToFuture()
  }

  "The product of two fetches from the same data source implies batching" in {
    def fetch[F[_]: Concurrent] =
      (one(1), one(3)).tupled

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      log.rounds.size shouldEqual 1
    }).unsafeToFuture()
  }

  "The product of concurrent fetches of the same type implies everything fetched in batches" in {
    def aFetch[F[_]: Concurrent] =
      for {
        a <- one(1) // round 1 (batched)
        b <- one(2) // round 2 (cached)
        c <- one(3) // round 3 (deduplicated)
      } yield c

    def anotherFetch[F[_]: Concurrent] =
      for {
        a <- one(2) // round 1 (batched)
        m <- many(4) // round 2
        c <- one(3) // round 3 (deduplicated)
      } yield c

    def fetch[F[_]: Concurrent] =
      ((aFetch, anotherFetch).tupled, one(3)).tupled

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      log.rounds.size shouldEqual 2
      totalBatches(log.rounds) shouldEqual 1
      totalFetched(log.rounds) shouldEqual 3 + 1
    }).unsafeToFuture()
  }
}
