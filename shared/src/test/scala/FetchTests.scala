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

import org.scalatest.{AsyncFreeSpec, Matchers}

import fetch._

import scala.language.implicitConversions
import scala.concurrent._
import scala.concurrent.duration._

import cats._
import cats.effect._
import cats.instances.list._
import cats.data.NonEmptyList
import cats.syntax.cartesian._
import cats.syntax.all._

class FetchTests extends AsyncFreeSpec with Matchers {
  import TestHelper._

  override val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  implicit def ioToFuture[A](io: IO[A]): Future[A] = io.unsafeToFuture()

  // Fetch ops

  "We can lift plain values to Fetch" in {
    val fetch: Fetch[Int] = Fetch.pure(42)
    Fetch.run(fetch).map(_ shouldEqual 42)
  }

  "We can lift values which have a Data Source to Fetch" in {
    Fetch.run(one(1)).map(_ shouldEqual 1)
  }

  "We can map over Fetch values" in {
    val fetch = one(1).map(_ + 1)
    Fetch.run(fetch).map(_ shouldEqual 2)
  }

  "We can use fetch inside a for comprehension" in {
    val fetch = for {
      o <- one(1)
      t <- one(2)
    } yield (o, t)

    Fetch.run(fetch).map(_ shouldEqual (1, 2))
  }

  "We can mix data sources" in {
    val fetch: Fetch[(Int, List[Int])] = for {
      o <- one(1)
      m <- many(3)
    } yield (o, m)

    Fetch.run(fetch).map(_ shouldEqual (1, List(0, 1, 2)))
  }

  "We can use Fetch as a cartesian" in {
    val fetch: Fetch[(Int, List[Int])] = (one(1), many(3)).tupled
    val io                             = Fetch.run(fetch)

    io.map(_ shouldEqual (1, List(0, 1, 2)))
  }

  "We can use Fetch as an applicative" in {
    val fetch: Fetch[Int] = (one(1), one(2), one(3)).mapN(_ + _ + _)
    val io                = Fetch.run(fetch)

    io.map(_ shouldEqual 6)
  }

  "We can traverse over a list with a Fetch for each element" in {
    val fetch: Fetch[List[Int]] = for {
      manies <- many(3)
      ones   <- manies.traverse(one)
    } yield ones
    val io = Fetch.run(fetch)

    io.map(_ shouldEqual List(0, 1, 2))
  }

  "We can depend on previous computations of Fetch values" in {
    val fetch: Fetch[Int] = for {
      o <- one(1)
      t <- one(o + 1)
    } yield o + t

    val io = Fetch.run(fetch)

    io.map(_ shouldEqual 3)
  }

  "We can collect a list of Fetch into one" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3))
    val fetch: Fetch[List[Int]]   = sources.sequence
    val io                        = Fetch.run(fetch)

    io.map(_ shouldEqual List(1, 2, 3))
  }

  "We can collect a list of Fetches with heterogeneous sources" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), anotherOne(4), anotherOne(5))
    val fetch: Fetch[List[Int]]   = sources.sequence
    val io                        = Fetch.run(fetch)

    io.map(_ shouldEqual List(1, 2, 3, 4, 5))
  }

  "We can collect the results of a traversal" in {
    val fetch = List(1, 2, 3).traverse(one)
    val io    = Fetch.run(fetch)

    io.map(_ shouldEqual List(1, 2, 3))
  }

  // Execution model

  "Monadic bind implies sequential execution" in {
    val fetch = for {
      o <- one(1)
      t <- one(2)
    } yield (o, t)

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual (1, 2)
        env.rounds.size shouldEqual 2
      }
    })
  }

  "Traversals are implicitly batched" in {
    val fetch: Fetch[List[Int]] = for {
      manies <- many(3)
      ones   <- manies.traverse(one)
    } yield ones

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual List(0, 1, 2)
        env.rounds.size shouldEqual 2
      }
    })
  }

  "Sequencing is implicitly batched" in {
    val fetch: Fetch[List[Int]] = List(one(1), one(2), one(3)).sequence

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual List(1, 2, 3)
        env.rounds.size shouldEqual 1
        totalFetched(env.rounds) shouldEqual 3
        totalBatches(env.rounds) shouldEqual 1
      }
    })
  }

  "Identities are deduped when batched" in {
    val manies = List(1, 1, 2)
    val fetch: Fetch[List[Int]] = for {
      ones <- manies.traverse(one)
    } yield ones

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual manies
        env.rounds.size shouldEqual 1
        env.rounds.head.queries.size shouldEqual 1
        env.rounds.head.queries.head.request should matchPattern {
          case Batch(NonEmptyList(One(1), List(One(2))), _) =>
        }
      }
    })
  }

  "The product of two fetches implies parallel fetching" in {
    val fetch: Fetch[(Int, List[Int])] = (one(1), many(3)).tupled

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual (1, List(0, 1, 2))
        env.rounds.size shouldEqual 1
        env.rounds.head.queries.size shouldEqual 2
      }
    })
  }

  "Concurrent fetching calls batches only when it can" in {
    val fetch: Fetch[(Int, List[Int])] = (one(1), many(3)).tupled

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual (1, List(0, 1, 2))
        env.rounds.size shouldEqual 1
        totalBatches(env.rounds) shouldEqual 0
      }
    })
  }

  "Concurrent fetching performs requests to multiple data sources in parallel" in {
    val fetch: Fetch[((Int, List[Int]), Int)] = ((one(1), many(2)).tupled, anotherOne(3)).tupled

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual ((1, List(0, 1)), 3)
        env.rounds.size shouldEqual 1
        totalBatches(env.rounds) shouldEqual 0
      }
    })
  }

  "The product of concurrent fetches implies everything fetched concurrently" in {
    val fetch = (
      (
        one(1),
        (one(2), one(3)).tupled
      ).tupled,
      one(4)
    ).tupled

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual ((1, (2, 3)), 4)
        env.rounds.size shouldEqual 1
        totalBatches(env.rounds) shouldEqual 1
        totalFetched(env.rounds) shouldEqual 4
      }
    })
  }

  "The product of concurrent fetches of the same type implies everything fetched in a single batch" in {
    val aFetch = for {
      a <- one(1)  // round 1
      b <- many(1) // round 2
      c <- one(1)
    } yield c
    val anotherFetch = for {
      a <- one(2)  // round 1
      m <- many(2) // round 2
      c <- one(2)
    } yield c

    val fetch = (
      (aFetch, anotherFetch).tupled,
      one(3)       // round 1
    ).tupled

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual ((1, 2), 3)
        env.rounds.size shouldEqual 2
        totalBatches(env.rounds) shouldEqual 2
        totalFetched(env.rounds) shouldEqual 5
      }
    })
  }

  "Every level of joined concurrent fetches is combined and batched" in {
    val aFetch = for {
      a <- one(1)  // round 1
      b <- many(1) // round 2
      c <- one(1)
    } yield c
    val anotherFetch = for {
      a <- one(2)  // round 1
      m <- many(2) // round 2
      c <- one(2)
    } yield c

    val fetch = (aFetch, anotherFetch).tupled

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual (1, 2)
        env.rounds.size shouldEqual 2
        totalBatches(env.rounds) shouldEqual 2
        totalFetched(env.rounds) shouldEqual 4
      }
    })
  }

  "Every level of sequenced concurrent fetches is batched" in {
    val aFetch =
      for {
        a <- List(2, 3, 4).traverse(one)   // round 1
        b <- List(0, 1).traverse(many)     // round 2
        c <- List(9, 10, 11).traverse(one) // round 3
      } yield c

    val anotherFetch =
      for {
        a <- List(5, 6, 7).traverse(one)    // round 1
        b <- List(2, 3).traverse(many)      // round 2
        c <- List(12, 13, 14).traverse(one) // round 3
      } yield c

    val fetch = (
       (aFetch, anotherFetch).tupled,
       List(15, 16, 17).traverse(one)      // round 1
    ).tupled

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual ((List(9, 10, 11), List(12, 13, 14)), List(15, 16, 17))
        env.rounds.size shouldEqual 3
        totalBatches(env.rounds) shouldEqual 3
        totalFetched(env.rounds) shouldEqual 9 + 4 + 6
      }
    })
  }

  "The product of two fetches from the same data source implies batching" in {
    val fetch: Fetch[(Int, Int)] = (one(1), one(3)).tupled

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual (1, 3)
        env.rounds.size shouldEqual 1
        totalBatches(env.rounds) shouldEqual 1
        totalFetched(env.rounds) shouldEqual 2
      }
    })
  }

  "Sequenced fetches are run concurrently" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), anotherOne(4), anotherOne(5))
    val fetch: Fetch[List[Int]]   = sources.sequence

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual List(1, 2, 3, 4, 5)
        env.rounds.size shouldEqual 1
        totalBatches(env.rounds) shouldEqual 2
      }
    })
  }

  "Sequenced fetches are deduped" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(1))
    val fetch: Fetch[List[Int]]   = sources.sequence

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual List(1, 2, 1)
        env.rounds.size shouldEqual 1
        totalBatches(env.rounds) shouldEqual 1
        totalFetched(env.rounds) shouldEqual 2
      }
    })
  }

  "Traversals are batched" in {
    val fetch = List(1, 2, 3).traverse(one)

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual List(1, 2, 3)
        env.rounds.size shouldEqual 1
        totalBatches(env.rounds) shouldEqual 1
      }
    })
  }

  "Duplicated sources are only fetched once" in {
    val fetch = List(1, 2, 1).traverse(one)

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual List(1, 2, 1)
        env.rounds.size shouldEqual 1
        totalFetched(env.rounds) shouldEqual 2
      }
    })
  }

  "Sources that can be fetched concurrently inside a for comprehension will be" in {
    val fetch = for {
      v      <- Fetch.pure(List(1, 2, 1))
      result <- v.traverse(one)
    } yield result

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual List(1, 2, 1)
        env.rounds.size shouldEqual 1
        totalFetched(env.rounds) shouldEqual 2
      }
    })
  }

  "Pure Fetches allow to explore further in the Fetch" in {
    val aFetch = for {
      a <- Fetch.pure(2)
      b <- one(3)
    } yield a + b

    val fetch: Fetch[(Int, Int)] = (one(1), aFetch).tupled

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual (1, 5)
        env.rounds.size shouldEqual 1
        totalFetched(env.rounds) shouldEqual 2
      }
    })
  }

  // Caching

  "Elements are cached and thus not fetched more than once" in {
    val fetch = for {
      aOne       <- one(1)
      anotherOne <- one(1)
      _          <- one(1)
      _          <- one(2)
      _          <- one(3)
      _          <- one(1)
      _          <- List(1, 2, 3).traverse(one)
      _          <- one(1)
    } yield aOne + anotherOne

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual 2
        totalFetched(env.rounds) shouldEqual 3
      }
    })
  }

  "Batched elements are cached and thus not fetched more than once" in {
    val fetch = for {
      _          <- List(1, 2, 3).traverse(one)
      aOne       <- one(1)
      anotherOne <- one(1)
      _          <- one(1)
      _          <- one(2)
      _          <- one(3)
      _          <- one(1)
      _          <- one(1)
    } yield aOne + anotherOne

    val io = Fetch.runEnv(fetch)

    io.map({
      case (env, result) => {
        result shouldEqual 2
        env.rounds.size shouldEqual 1
        totalFetched(env.rounds) shouldEqual 3
      }
    })
  }

  "Elements that are cached won't be fetched" in {
    val fetch = for {
      aOne       <- one(1)
      anotherOne <- one(1)
      _          <- one(1)
      _          <- one(2)
      _          <- one(3)
      _          <- one(1)
      _          <- List(1, 2, 3).traverse(one)
      _          <- one(1)
    } yield aOne + anotherOne

    val cache = InMemoryCache.from(
      (OneSource.name, One(1)) -> 1,
      (OneSource.name, One(2)) -> 2,
      (OneSource.name, One(3)) -> 3
    )

    val io = Fetch.runEnv(fetch, cache)

    io.map({
      case (env, result) => {
        result shouldEqual 2
        totalFetched(env.rounds) shouldEqual 0
        env.rounds.size shouldEqual 0
      }
    })
  }

  case class ForgetfulCache() extends DataSourceCache {
    def insert[I, A](i: I, v: A, ds: DataSource[I, A]): IO[ForgetfulCache] = IO.pure(this)
    def lookup[I, A](i: I, ds: DataSource[I, A]): IO[Option[A]] = IO.pure(None)
  }

  "We can use a custom cache that discards elements" in {
    val fetch = for {
      aOne       <- one(1)
      anotherOne <- one(1)
      _          <- one(1)
      _          <- one(2)
      _          <- one(3)
      _          <- one(1)
      _          <- one(1)
    } yield aOne + anotherOne

    val cache = ForgetfulCache()
    val io = Fetch.runEnv(fetch, cache)

    io.map({
      case (env, result) => {
        result shouldEqual 2
        env.rounds.size shouldEqual 7
        totalFetched(env.rounds) shouldEqual 7
      }
    })
  }

  "We can use a custom cache that discards elements together with concurrent fetches" in {
    val fetch = for {
      aOne       <- one(1)
      anotherOne <- one(1)
      _          <- one(1)
      _          <- one(2)
      _          <- List(1, 2, 3).traverse(one)
      _          <- one(3)
      _          <- one(1)
      _          <- one(1)
    } yield aOne + anotherOne

    val cache = ForgetfulCache()
    val io = Fetch.runEnv(fetch, cache)

    io.map({
      case (env, result) => {
        result shouldEqual 2
        env.rounds.size shouldEqual 8
        totalFetched(env.rounds) shouldEqual 10
      }
    })
  }

  // Errors

  "Data sources with errors throw fetch failures" in {
    val fetch: Fetch[Int] = never
    val io                = Fetch.run(fetch)

    io.attempt
      .map(_ should matchPattern {
        case Left(MissingIdentity(Never(), _, _)) =>
      })
  }

  "Data sources with errors throw fetch failures that can be handled" in {
    val fetch: Fetch[Int] = never
    val io                = Fetch.run(fetch)

    io.handleErrorWith(err => IO.pure(42))
      .map(_ shouldEqual 42)
  }

  "Data sources with errors won't fail if they're cached" in {
    val fetch: Fetch[Int] = never

    val cache = InMemoryCache.from(
      (NeverSource.name, Never()) -> 1
    )
    val io = Fetch.run(fetch, cache)

    io.map(_ shouldEqual 1)
  }

  "We can lift errors to Fetch" in {
    val fetch: Fetch[Int] = Fetch.error(AnException())

    val io = Fetch.run(fetch)

    io.attempt
      .map(_ should matchPattern {
        case Left(UnhandledException(AnException(), _)) =>
      })
  }

  "We can lift handle and recover from errors in Fetch" in {
    val fetch: Fetch[Int] = Fetch.error(AnException())

    val io = Fetch.run(fetch)

    io.handleErrorWith(err => IO.pure(42))
      .map(_ shouldEqual 42)
  }

  "If a fetch fails in the left hand of a product the product will fail" in {
    val error: Fetch[Int] = Fetch.error(AnException())
    val fetch: Fetch[(Int, List[Int])] = (error, many(3)).tupled

    val io = Fetch.run(fetch)

    io.attempt
      .map(_ should matchPattern {
        case Left(UnhandledException(AnException(), _)) =>
      })
  }

  "If a fetch fails in the right hand of a product the product will fail" in {
    val error: Fetch[Int] = Fetch.error(AnException())
    val fetch: Fetch[(List[Int], Int)] = (many(3), error).tupled

    val io = Fetch.run(fetch)

    io.attempt
      .map(_ should matchPattern {
        case Left(UnhandledException(AnException(), _)) =>
      })
  }

  "If there is a missing identity in the left hand of a product the product will fail" in {
    val fetch: Fetch[(Int, List[Int])] = (never,  many(3)).tupled

    val io = Fetch.run(fetch)

    io.attempt
      .map(_ should matchPattern {
        case Left(MissingIdentity(Never(), _, _)) =>
      })
  }

  "If there is a missing identity in the right hand of a product the product will fail" in {
    val fetch: Fetch[(List[Int], Int)] = (many(3),  never).tupled

    val io = Fetch.run(fetch)

    io.attempt
      .map(_ should matchPattern {
        case Left(MissingIdentity(Never(), _, _)) =>
      })
  }

  "If there are multiple failing identities the fetch will fail" in {
    val fetch: Fetch[(Int, Int)] = (never,  never).tupled

    val io = Fetch.run(fetch)

    io.attempt
      .map(_ should matchPattern {
        case Left(MissingIdentity(Never(), _, _)) =>
      })
  }
}
