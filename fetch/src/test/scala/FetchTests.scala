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

import scala.concurrent._
import java.util.concurrent._
import scala.concurrent.duration._

import cats._
import cats.effect._
import cats.instances.list._
import cats.instances.option._
import cats.data.NonEmptyList
import cats.syntax.all._

class FetchTests extends FetchSpec {
  import TestHelper._

  // Fetch ops

  "We can lift plain values to Fetch" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, Int] =
      Fetch.pure[F, Int](42)

    Fetch.run[IO](fetch).map(_ shouldEqual 42).unsafeToFuture()
  }

  "We can lift values which have a Data Source to Fetch" in {
    Fetch.run[IO](one(1)).map(_ shouldEqual 1).unsafeToFuture()
  }

  "We can map over Fetch values" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, (Int)] =
      one(1).map(_ + 1)

    Fetch.run[IO](fetch).map(_ shouldEqual 2).unsafeToFuture()
  }

  "We can use fetch inside a for comprehension" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, (Int, Int)] =
      for {
        o <- one(1)
        t <- one(2)
      } yield (o, t)

    Fetch.run[IO](fetch).map(_ shouldEqual (1, 2)).unsafeToFuture()
  }

  "We can mix data sources" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, (Int, List[Int])] =
      for {
        o <- one(1)
        m <- many(3)
      } yield (o, m)

    Fetch.run[IO](fetch).map(_ shouldEqual (1, List(0, 1, 2))).unsafeToFuture()
  }

  "We can use Fetch as a cartesian" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, (Int, List[Int])] = (one(1), many(3)).tupled

    val io = Fetch.run[IO](fetch)

    io.map(_ shouldEqual (1, List(0, 1, 2))).unsafeToFuture()
  }

  "We can use Fetch as an applicative" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, Int] = (one(1), one(2), one(3)).mapN(_ + _ + _)

    val io = Fetch.run[IO](fetch)

    io.map(_ shouldEqual 6).unsafeToFuture()
  }

  "We can traverse over a list with a Fetch for each element" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      for {
        manies <- many(3)
        ones   <- manies.traverse(one[F])
      } yield ones

    val io = Fetch.run[IO](fetch)

    io.map(_ shouldEqual List(0, 1, 2)).unsafeToFuture()
  }

  "We can depend on previous computations of Fetch values" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, Int] =
      for {
        o <- one(1)
        t <- one(o + 1)
      } yield o + t

    val io = Fetch.run[IO](fetch)

    io.map(_ shouldEqual 3).unsafeToFuture()
  }

  "We can collect a list of Fetch into one" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      List(one(1), one(2), one(3)).sequence

    val io = Fetch.run[IO](fetch)

    io.map(_ shouldEqual List(1, 2, 3)).unsafeToFuture()
  }

  "We can collect a list of Fetches with heterogeneous sources" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      List(one(1), one(2), one(3), anotherOne(4), anotherOne(5)).sequence

    val io = Fetch.run[IO](fetch)

    io.map(_ shouldEqual List(1, 2, 3, 4, 5)).unsafeToFuture()
  }

  "We can collect the results of a traversal" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      List(1, 2, 3).traverse(one[F])

    val io = Fetch.run[IO](fetch)

    io.map(_ shouldEqual List(1, 2, 3)).unsafeToFuture()
  }

  // Execution model

  "Monadic bind implies sequential execution" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, (Int, Int)] =
      for {
        o <- one(1)
        t <- one(2)
      } yield (o, t)

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual (1, 2)
      log.rounds.size shouldEqual 2
    }).unsafeToFuture()
  }

  "Traversals are implicitly batched" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      for {
        manies <- many(3)
        ones   <- manies.traverse(one[F])
      } yield ones

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual List(0, 1, 2)
      log.rounds.size shouldEqual 2
    }).unsafeToFuture()
  }

  "Sequencing is implicitly batched" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      List(one(1), one(2), one(3)).sequence

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual List(1, 2, 3)
      log.rounds.size shouldEqual 1
      totalFetched(log.rounds) shouldEqual 3
      totalBatches(log.rounds) shouldEqual 1
    }).unsafeToFuture()
  }

  "Identities are deduped when batched" in {
    val sources = List(1, 1, 2)
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      sources.traverse(one[F])

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual sources
      log.rounds.size shouldEqual 1
      log.rounds.head.queries.size shouldEqual 1
      log.rounds.head.queries.head.request should matchPattern {
        case Batch(NonEmptyList(1, List(2)), _) =>
      }
    }).unsafeToFuture()
  }

  "The product of two fetches implies parallel fetching" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, (Int, List[Int])] =
      (one(1), many(3)).tupled

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual (1, List(0, 1, 2))
      log.rounds.size shouldEqual 1
      log.rounds.head.queries.size shouldEqual 2
    }).unsafeToFuture()
  }

  "Concurrent fetching calls batches only when it can" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, (Int, List[Int])] =
      (one(1), many(3)).tupled

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual (1, List(0, 1, 2))
      log.rounds.size shouldEqual 1
      totalBatches(log.rounds) shouldEqual 0
    }).unsafeToFuture()
  }

  "Concurrent fetching performs requests to multiple data sources in parallel" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, ((Int, List[Int]), Int)] =
      ((one(1), many(2)).tupled, anotherOne(3)).tupled

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual ((1, List(0, 1)), 3)
      log.rounds.size shouldEqual 1
      totalBatches(log.rounds) shouldEqual 0
    }).unsafeToFuture()
  }

  "The product of concurrent fetches implies everything fetched concurrently" in {
    def fetch[F[_]: ConcurrentEffect] =
      (
        (
          one(1),
          (one(2), one(3)).tupled
        ).tupled,
        one(4)
      ).tupled

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual ((1, (2, 3)), 4)
      log.rounds.size shouldEqual 1
      totalBatches(log.rounds) shouldEqual 1
      totalFetched(log.rounds) shouldEqual 4
    }).unsafeToFuture()
  }

  "The product of concurrent fetches of the same type implies everything fetched in a single batch" in {
    def aFetch[F[_]: ConcurrentEffect] =
      for {
        a <- one(1) // round 1
        b <- many(1) // round 2
        c <- one(1)
      } yield c
    def anotherFetch[F[_]: ConcurrentEffect] =
      for {
        a <- one(2) // round 1
        m <- many(2) // round 2
        c <- one(2)
      } yield c

    def fetch[F[_]: ConcurrentEffect] =
      (
        (aFetch[F], anotherFetch[F]).tupled,
        one(3) // round 1
      ).tupled

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual ((1, 2), 3)
      log.rounds.size shouldEqual 2
      totalBatches(log.rounds) shouldEqual 2
      totalFetched(log.rounds) shouldEqual 5
    }).unsafeToFuture()
  }

  "Every level of joined concurrent fetches is combined and batched" in {
    def aFetch[F[_]: ConcurrentEffect] =
      for {
        a <- one(1) // round 1
        b <- many(1) // round 2
        c <- one(1)
      } yield c
    def anotherFetch[F[_]: ConcurrentEffect] =
      for {
        a <- one(2) // round 1
        m <- many(2) // round 2
        c <- one(2)
      } yield c

    def fetch[F[_]: ConcurrentEffect] = (aFetch[F], anotherFetch[F]).tupled

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual (1, 2)
      log.rounds.size shouldEqual 2
      totalBatches(log.rounds) shouldEqual 2
      totalFetched(log.rounds) shouldEqual 4
    }).unsafeToFuture()
  }

  "Every level of sequenced concurrent fetches is batched" in {
    def aFetch[F[_]: ConcurrentEffect] =
      for {
        a <- List(2, 3, 4).traverse(one[F]) // round 1
        b <- List(0, 1).traverse(many[F]) // round 2
        c <- List(9, 10, 11).traverse(one[F]) // round 3
      } yield c

    def anotherFetch[F[_]: ConcurrentEffect] =
      for {
        a <- List(5, 6, 7).traverse(one[F]) // round 1
        b <- List(2, 3).traverse(many[F]) // round 2
        c <- List(12, 13, 14).traverse(one[F]) // round 3
      } yield c

    def fetch[F[_]: ConcurrentEffect] =
      (
        (aFetch[F], anotherFetch[F]).tupled,
        List(15, 16, 17).traverse(one[F]) // round 1
      ).tupled

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual ((List(9, 10, 11), List(12, 13, 14)), List(15, 16, 17))
      log.rounds.size shouldEqual 3
      totalBatches(log.rounds) shouldEqual 3
      totalFetched(log.rounds) shouldEqual 9 + 4 + 6
    }).unsafeToFuture()
  }

  "The product of two fetches from the same data source implies batching" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, (Int, Int)] = (one(1), one(3)).tupled

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual (1, 3)
      log.rounds.size shouldEqual 1
      totalBatches(log.rounds) shouldEqual 1
      totalFetched(log.rounds) shouldEqual 2
    }).unsafeToFuture()
  }

  "Sequenced fetches are run concurrently" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      List(one(1), one(2), one(3), anotherOne(4), anotherOne(5)).sequence

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual List(1, 2, 3, 4, 5)
      log.rounds.size shouldEqual 1
      totalBatches(log.rounds) shouldEqual 2
    }).unsafeToFuture()
  }

  "Sequenced fetches are deduped" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      List(one(1), one(2), one(1)).sequence

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual List(1, 2, 1)
      log.rounds.size shouldEqual 1
      totalBatches(log.rounds) shouldEqual 1
      totalFetched(log.rounds) shouldEqual 2
    }).unsafeToFuture()
  }

  "Traversals are batched" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      List(1, 2, 3).traverse(one[F])

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual List(1, 2, 3)
      log.rounds.size shouldEqual 1
      totalBatches(log.rounds) shouldEqual 1
    }).unsafeToFuture()
  }

  "Duplicated sources are only fetched once" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      List(1, 2, 1).traverse(one[F])

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual List(1, 2, 1)
      log.rounds.size shouldEqual 1
      totalFetched(log.rounds) shouldEqual 2
    }).unsafeToFuture()
  }

  "Sources that can be fetched concurrently inside a for comprehension will be" in {
    def fetch[F[_]: ConcurrentEffect] =
      for {
        v      <- Fetch.pure[F, List[Int]](List(1, 2, 1))
        result <- v.traverse(one[F])
      } yield result

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual List(1, 2, 1)
      log.rounds.size shouldEqual 1
      totalFetched(log.rounds) shouldEqual 2
    }).unsafeToFuture()
  }

  "Pure Fetches allow to explore further in the Fetch" in {
    def aFetch[F[_]: ConcurrentEffect] =
      for {
        a <- Fetch.pure[F, Int](2)
        b <- one[F](3)
      } yield a + b

    def fetch[F[_]: ConcurrentEffect]: Fetch[F, (Int, Int)] =
      (one(1), aFetch[F]).tupled

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual (1, 5)
      log.rounds.size shouldEqual 1
      totalFetched(log.rounds) shouldEqual 2
    }).unsafeToFuture()
  }

  // Caching

  "Elements are cached and thus not fetched more than once" in {
    def fetch[F[_]: ConcurrentEffect] =
      for {
        aOne       <- one(1)
        anotherOne <- one(1)
        _          <- one(1)
        _          <- one(2)
        _          <- one(3)
        _          <- one(1)
        _          <- List(1, 2, 3).traverse(one[F])
        _          <- one(1)
      } yield aOne + anotherOne

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual 2
      totalFetched(log.rounds) shouldEqual 3
    }).unsafeToFuture()
  }

  "Batched elements are cached and thus not fetched more than once" in {
    def fetch[F[_]: ConcurrentEffect] =
      for {
        _          <- List(1, 2, 3).traverse(one[F])
        aOne       <- one(1)
        anotherOne <- one(1)
        _          <- one(1)
        _          <- one(2)
        _          <- one(3)
        _          <- one(1)
        _          <- one(1)
      } yield aOne + anotherOne

    val io = Fetch.runLog[IO](fetch)

    io.map({ case (log, result) =>
      result shouldEqual 2
      log.rounds.size shouldEqual 1
      totalFetched(log.rounds) shouldEqual 3
    }).unsafeToFuture()
  }

  "Elements that are cached won't be fetched" in {
    def fetch[F[_]: ConcurrentEffect] =
      for {
        aOne       <- one(1)
        anotherOne <- one(1)
        _          <- one(1)
        _          <- one(2)
        _          <- one(3)
        _          <- one(1)
        _          <- List(1, 2, 3).traverse(one[F])
        _          <- one(1)
      } yield aOne + anotherOne

    def cache[F[_]: ConcurrentEffect] =
      InMemoryCache.from[F, Int, Int](
        (One, 1) -> 1,
        (One, 2) -> 2,
        (One, 3) -> 3
      )

    val io = Fetch.runLog[IO](fetch, cache)

    io.map({ case (log, result) =>
      result shouldEqual 2
      totalFetched(log.rounds) shouldEqual 0
      log.rounds.size shouldEqual 0
    }).unsafeToFuture()
  }

  "Fetch#run accepts a cache as the second (optional) parameter" in {
    def fetch[F[_]: ConcurrentEffect] =
      for {
        aOne       <- one(1)
        anotherOne <- one(1)
        _          <- one(1)
        _          <- one(2)
        _          <- one(3)
        _          <- one(1)
        _          <- List(1, 2, 3).traverse(one[F])
        _          <- one(1)
      } yield aOne + anotherOne

    def cache[F[_]: ConcurrentEffect] =
      InMemoryCache.from[F, Int, Int](
        (One, 1) -> 1,
        (One, 2) -> 2,
        (One, 3) -> 3
      )

    val io = Fetch.run[IO](fetch, cache)

    io.map(_ shouldEqual 2).unsafeToFuture()
  }

  "Fetch#runCache accepts a cache as the second (optional) parameter" in {
    def fetch[F[_]: ConcurrentEffect] =
      for {
        aOne       <- one(1)
        anotherOne <- one(1)
        _          <- one(1)
        _          <- one(2)
        _          <- one(3)
        _          <- one(1)
        _          <- List(1, 2, 3).traverse(one[F])
        _          <- one(1)
      } yield aOne + anotherOne

    def cache[F[_]: ConcurrentEffect] =
      InMemoryCache.from[F, Int, Int](
        (One, 1) -> 1,
        (One, 2) -> 2,
        (One, 3) -> 3
      )

    val io = Fetch.runCache[IO](fetch, cache)

    io.map({ case (c, result) =>
      result shouldEqual 2
    }).unsafeToFuture()
  }

  "Fetch#runCache works without the optional cache parameter" in {
    def fetch[F[_]: ConcurrentEffect] =
      for {
        aOne       <- one(1)
        anotherOne <- one(1)
        _          <- one(1)
        _          <- one(2)
        _          <- one(3)
        _          <- one(1)
        _          <- List(1, 2, 3).traverse(one[F])
        _          <- one(1)
      } yield aOne + anotherOne

    val io = Fetch.runCache[IO](fetch)

    io.map({ case (c, result) =>
      result shouldEqual 2
    }).unsafeToFuture()
  }

  case class ForgetfulCache[F[_]: Monad]() extends DataCache[F] {
    def insert[I, A](i: I, v: A, d: Data[I, A]): F[DataCache[F]] =
      Applicative[F].pure(this)

    def lookup[I, A](i: I, d: Data[I, A]): F[Option[A]] =
      Applicative[F].pure(None)
  }

  def forgetfulCache[F[_]: ConcurrentEffect] = ForgetfulCache[F]()

  "We can use a custom cache that discards elements" in {
    def fetch[F[_]: ConcurrentEffect] =
      for {
        aOne       <- one(1)
        anotherOne <- one(1)
        _          <- one(1)
        _          <- one(2)
        _          <- one(3)
        _          <- one(1)
        _          <- one(1)
      } yield aOne + anotherOne

    val io = Fetch.runLog[IO](fetch, forgetfulCache)

    io.map({ case (log, result) =>
      result shouldEqual 2
      log.rounds.size shouldEqual 7
      totalFetched(log.rounds) shouldEqual 7
    }).unsafeToFuture()
  }

  "We can use a custom cache that discards elements together with concurrent fetches" in {
    def fetch[F[_]: ConcurrentEffect] =
      for {
        aOne       <- one(1)
        anotherOne <- one(1)
        _          <- one(1)
        _          <- one(2)
        _          <- List(1, 2, 3).traverse(one[F])
        _          <- one(3)
        _          <- one(1)
        _          <- one(1)
      } yield aOne + anotherOne

    val io = Fetch.runLog[IO](fetch, forgetfulCache)

    io.map({ case (log, result) =>
      result shouldEqual 2
      log.rounds.size shouldEqual 8
      totalFetched(log.rounds) shouldEqual 10
    }).unsafeToFuture()
  }

  // Errors

  "Data sources with errors throw fetch failures" in {
    val io = Fetch.run[IO](never)

    io.attempt
      .map(_ should matchPattern { case Left(MissingIdentity(Never(), _, _)) =>
      })
      .unsafeToFuture()
  }

  "Data sources with errors throw fetch failures that can be handled" in {
    val io = Fetch.run[IO](never)

    io.handleErrorWith(err => IO.pure(42))
      .map(_ shouldEqual 42)
      .unsafeToFuture()
  }

  "Data sources with errors won't fail if they're cached" in {
    def cache[F[_]: ConcurrentEffect] =
      InMemoryCache.from[F, Never, Int](
        (Never, Never()) -> 1
      )
    val io = Fetch.run[IO](never, cache)

    io.map(_ shouldEqual 1).unsafeToFuture()
  }

  def fetchError[F[_]: ConcurrentEffect]: Fetch[F, Int] =
    Fetch.error(AnException())

  "We can lift errors to Fetch" in {
    val io = Fetch.run[IO](fetchError)

    io.attempt
      .map(_ should matchPattern { case Left(UnhandledException(AnException(), _)) =>
      })
      .unsafeToFuture()
  }

  "We can lift handle and recover from errors in Fetch" in {
    val io = Fetch.run[IO](fetchError)

    io.handleErrorWith(err => IO.pure(42))
      .map(_ shouldEqual 42)
      .unsafeToFuture()
  }

  "If a fetch fails in the left hand of a product the product will fail" in {
    def fetch[F[_]: ConcurrentEffect] =
      (fetchError, many(3)).tupled

    val io = Fetch.run[IO](fetch)

    io.attempt
      .map(_ should matchPattern { case Left(UnhandledException(AnException(), _)) =>
      })
      .unsafeToFuture()
  }

  "If a fetch fails in the right hand of a product the product will fail" in {
    def fetch[F[_]: ConcurrentEffect] =
      (many(3), fetchError).tupled

    val io = Fetch.run[IO](fetch)

    io.attempt
      .map(_ should matchPattern { case Left(UnhandledException(AnException(), _)) =>
      })
      .unsafeToFuture()
  }

  "If there is a missing identity in the left hand of a product the product will fail" in {
    def fetch[F[_]: ConcurrentEffect] =
      (never, many(3)).tupled

    val io = Fetch.run[IO](fetch)

    io.attempt
      .map(_ should matchPattern { case Left(MissingIdentity(Never(), _, _)) =>
      })
      .unsafeToFuture()
  }

  "If there is a missing identity in the right hand of a product the product will fail" in {
    def fetch[F[_]: ConcurrentEffect] =
      (many(3), never).tupled

    val io = Fetch.run[IO](fetch)

    io.attempt
      .map(_ should matchPattern { case Left(MissingIdentity(Never(), _, _)) =>
      })
      .unsafeToFuture()
  }

  "If there are multiple failing identities the fetch will fail" in {
    def fetch[F[_]: ConcurrentEffect] =
      (never, never).tupled

    val io = Fetch.run[IO](fetch)

    io.attempt
      .map(_ should matchPattern { case Left(MissingIdentity(Never(), _, _)) =>
      })
      .unsafeToFuture()
  }

  // Optional fetches

  case class MaybeMissing(id: Int)

  object MaybeMissing extends Data[MaybeMissing, Int] {
    def name = "Maybe Missing"

    implicit def source[F[_]: ConcurrentEffect]: DataSource[F, MaybeMissing, Int] =
      new DataSource[F, MaybeMissing, Int] {
        override def data = MaybeMissing

        override def CF = ConcurrentEffect[F]

        override def fetch(id: MaybeMissing): F[Option[Int]] =
          if (id.id % 2 == 0)
            CF.pure(None)
          else
            CF.pure(Option(id.id))
      }

  }

  def maybeOpt[F[_]: ConcurrentEffect](id: Int): Fetch[F, Option[Int]] =
    Fetch.optional(MaybeMissing(id), MaybeMissing.source)

  "We can run optional fetches" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, Option[Int]] =
      maybeOpt(1)

    Fetch.run[IO](fetch).map(_ shouldEqual Some(1)).unsafeToFuture()
  }

  "We can run optional fetches with traverse" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      List(1, 2, 3).traverse(maybeOpt[F]).map(_.flatten)

    Fetch.run[IO](fetch).map(_ shouldEqual List(1, 3)).unsafeToFuture()
  }

  "We can run optional fetches with other data sources" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] = {
      val ones   = List(1, 2, 3).traverse(one[F])
      val maybes = List(1, 2, 3).traverse(maybeOpt[F])
      (ones, maybes).mapN { case (os, ms) => os ++ ms.flatten }
    }

    Fetch.run[IO](fetch).map(_ shouldEqual List(1, 2, 3, 1, 3)).unsafeToFuture()
  }

  "We can make fetches that depend on optional fetch results when they aren't defined" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, Int] =
      for {
        maybe  <- maybeOpt(2)
        result <- maybe.fold(Fetch.pure[F, Int](42))(i => one(i))
      } yield result

    Fetch.run[IO](fetch).map(_ shouldEqual 42).unsafeToFuture()
  }

  "We can make fetches that depend on optional fetch results when they are defined" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, Int] =
      for {
        maybe  <- maybeOpt(1)
        result <- maybe.fold(Fetch.pure[F, Int](42))(i => one(i))
      } yield result

    Fetch.run[IO](fetch).map(_ shouldEqual 1).unsafeToFuture()
  }

  // IO in Fetch

  "We can lift IO actions into Fetch" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, Int] =
      Fetch.liftIO(IO(42))

    Fetch.run[IO](fetch).map(_ shouldEqual 42).unsafeToFuture()
  }

  "A failed IO action lifted into Fetch will cause a Fetch to fail" in {
    def fetch[F[_]: ConcurrentEffect] =
      Fetch.liftIO(IO.raiseError(AnException()))

    val io = Fetch.run[IO](fetch)

    io.attempt
      .map(_ should matchPattern { case Left(UnhandledException(AnException(), _)) =>
      })
      .unsafeToFuture()
  }

  "A IO action can be combined with data fetches" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      for {
        x        <- Fetch.liftIO(IO(3))
        manies   <- many(x)
        onesAndY <- (manies.traverse(one[F]), Fetch.liftIO(IO(42))).tupled
        (ones, y) = onesAndY
      } yield ones :+ y

    val io = Fetch.run[IO](fetch)

    io.map(_ shouldEqual List(0, 1, 2, 42)).unsafeToFuture()
  }

  // Concurrent[_] in Fetch

  "We can lift Concurrent actions into Fetch" in {
    def fortyTwo[F[_]: Concurrent]: F[Int] =
      Concurrent[F].pure(42)

    def fetch[F[_]: ConcurrentEffect]: Fetch[F, Int] =
      Fetch.liftF(fortyTwo)

    Fetch.run[IO](fetch).map(_ shouldEqual 42).unsafeToFuture()
  }

  "A failed Concurrent action lifted into Fetch will cause a Fetch to fail" in {
    def fail[F[_]: Concurrent]: F[Int] =
      Concurrent[F].raiseError(AnException())

    def fetch[F[_]: ConcurrentEffect]: Fetch[F, Int] =
      Fetch.liftF(fail)

    val io = Fetch.run[IO](fetch)

    io.attempt
      .map(_ should matchPattern { case Left(UnhandledException(AnException(), _)) =>
      })
      .unsafeToFuture()
  }

  "A Concurrent action can be combined with data fetches" in {
    def concurrently[F[_]: Concurrent, A](x: A): F[A] =
      Concurrent[F].pure(x)

    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      for {
        x        <- Fetch.liftF(concurrently(3))
        manies   <- many(x)
        onesAndY <- (manies.traverse(one[F]), Fetch.liftF(concurrently(42))).tupled
        (ones, y) = onesAndY
      } yield ones :+ y

    val io = Fetch.run[IO](fetch)

    io.map(_ shouldEqual List(0, 1, 2, 42)).unsafeToFuture()
  }
}
