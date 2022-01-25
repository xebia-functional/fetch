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

import cats.syntax.all._
import cats.effect._

import fetch.syntax._
import fetch.Fetch

class FetchSyntaxTests extends FetchSpec {
  import TestHelper._

  "`fetch` syntax allows lifting of any value to the context of a fetch" in {
    Fetch.pure[IO, Int](42) shouldEqual 42.fetch[IO]
  }

  "`fetch` syntax allows lifting of any `Throwable` as a failure on a fetch" in {
    case object Ex extends RuntimeException

    def f1[F[_]: Concurrent] =
      Fetch.error[F, Int](Ex)

    def f2[F[_]: Concurrent] =
      Ex.fetch[F]

    val io1 = Fetch.run[IO](f1)
    val io2 = Fetch.run[IO](f2)

    val e1 = io1.handleError(err => 42)
    val e2 = io2.handleError(err => 42)

    (e1, e2).mapN(_ shouldEqual _).unsafeToFuture()
  }

  "`batchAll` syntax allows batching sequences of fetches and is equivalent to Fetch.batchAll" in {
    def fetches[F[_]: Concurrent] = List(1, 2, 3).map(one[IO])
    val fetchWithSyntax           = fetches[IO].batchAll
    val fetchWithOtherSyntax      = List(1, 2, 3).batchAllWith(one[IO])
    val fetchManual               = Fetch.batchAll(fetches[IO]: _*)

    val result1 = Fetch.runLog[IO](fetchWithSyntax)
    val result2 = Fetch.runLog[IO](fetchWithOtherSyntax)
    val result3 = Fetch.runLog[IO](fetchManual)

    (result1, result2, result3).tupled
      .map { case ((log1, r1), (log2, r2), (log3, r3)) =>
        Set(r1, r2, r3).size shouldBe 1

        log1.rounds.size shouldBe 1
        log2.rounds.size shouldBe 1
        log3.rounds.size shouldBe 1
      }
      .unsafeToFuture()
  }
}
