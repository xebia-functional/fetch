/*
 * Copyright 2016-2020 47 Degrees Open Source <https://www.47deg.com>
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

import cats._
import cats.data.NonEmptyList
import cats.instances.list._
import cats.syntax.all._
import cats.effect._

import fetch._

class FetchBatchingTests extends FetchSpec {
  import TestHelper._

  case class BatchedDataSeq(id: Int)

  object SeqBatch extends Data[BatchedDataSeq, Int] {
    def name = "Sequential batching"

    implicit def source[F[_]: ConcurrentEffect]: DataSource[F, BatchedDataSeq, Int] =
      new DataSource[F, BatchedDataSeq, Int] {
        override def data = SeqBatch

        override def CF = ConcurrentEffect[F]

        override def fetch(id: BatchedDataSeq): F[Option[Int]] =
          CF.pure(Some(id.id))

        override val maxBatchSize = Some(2)

        override val batchExecution = Sequentially
      }
  }

  case class BatchedDataPar(id: Int)

  object ParBatch extends Data[BatchedDataPar, Int] {
    def name = "Parallel batching"

    implicit def source[F[_]: ConcurrentEffect]: DataSource[F, BatchedDataPar, Int] =
      new DataSource[F, BatchedDataPar, Int] {
        override def data = ParBatch

        override def CF = ConcurrentEffect[F]

        override def fetch(id: BatchedDataPar): F[Option[Int]] =
          CF.pure(Some(id.id))

        override val maxBatchSize = Some(2)

        override val batchExecution = InParallel
      }
  }

  case class BatchedDataBigId(
      str1: String,
      str2: String,
      str3: String
  )

  object BigIdData extends Data[BatchedDataBigId, String] {
    def name = "Big id batching"

    implicit def source[F[_]: ConcurrentEffect]: DataSource[F, BatchedDataBigId, String] =
      new DataSource[F, BatchedDataBigId, String] {
        override def data = BigIdData

        override def CF = ConcurrentEffect[F]

        override def fetch(request: BatchedDataBigId): F[Option[String]] =
          batch(NonEmptyList.one(request)).map(_.get(request))

        override def batch(ids: NonEmptyList[BatchedDataBigId]): F[Map[BatchedDataBigId, String]] =
          CF.pure(
            ids.map(id => id -> id.toString).toList.toMap
          )

        override val batchExecution = InParallel
      }
  }

  def fetchBatchedDataSeq[F[_]: ConcurrentEffect](id: Int): Fetch[F, Int] =
    Fetch(BatchedDataSeq(id), SeqBatch.source)

  def fetchBatchedDataPar[F[_]: ConcurrentEffect](id: Int): Fetch[F, Int] =
    Fetch(BatchedDataPar(id), ParBatch.source)

  def fetchBatchedDataBigId[F[_]: ConcurrentEffect](id: BatchedDataBigId): Fetch[F, String] =
    Fetch(id, BigIdData.source)

  "A large fetch to a datasource with a maximum batch size is split and executed in sequence" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      List.range(1, 6).traverse(fetchBatchedDataSeq[F])

    val io = Fetch.runLog[IO](fetch)

    io.map({
        case (log, result) => {
          result shouldEqual List(1, 2, 3, 4, 5)
          log.rounds.size shouldEqual 1
          totalFetched(log.rounds) shouldEqual 5
          totalBatches(log.rounds) shouldEqual 3
        }
      })
      .unsafeToFuture
  }

  "A large fetch to a datasource with a maximum batch size is split and executed in parallel" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      List.range(1, 6).traverse(fetchBatchedDataPar[F])

    val io = Fetch.runLog[IO](fetch)

    io.map({
        case (log, result) => {
          result shouldEqual List(1, 2, 3, 4, 5)
          log.rounds.size shouldEqual 1
          totalFetched(log.rounds) shouldEqual 5
          totalBatches(log.rounds) shouldEqual 3
        }
      })
      .unsafeToFuture
  }

  "Fetches to datasources with a maximum batch size should be split and executed in parallel and sequentially when using productR" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      List.range(1, 6).traverse(fetchBatchedDataPar[F]) *>
        List.range(1, 6).traverse(fetchBatchedDataSeq[F])

    val io = Fetch.runLog[IO](fetch)

    io.map({
        case (log, result) => {
          result shouldEqual List(1, 2, 3, 4, 5)
          log.rounds.size shouldEqual 1
          totalFetched(log.rounds) shouldEqual 5 + 5
          totalBatches(log.rounds) shouldEqual 3 + 3
        }
      })
      .unsafeToFuture
  }

  "Fetches to datasources with a maximum batch size should be split and executed in parallel and sequentially when using productL" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      List.range(1, 6).traverse(fetchBatchedDataPar[F]) <*
        List.range(1, 6).traverse(fetchBatchedDataSeq[F])

    val io = Fetch.runLog[IO](fetch)

    io.map({
        case (log, result) => {
          result shouldEqual List(1, 2, 3, 4, 5)
          log.rounds.size shouldEqual 1
          totalFetched(log.rounds) shouldEqual 5 + 5
          totalBatches(log.rounds) shouldEqual 3 + 3
        }
      })
      .unsafeToFuture
  }

  "A large (many) fetch to a datasource with a maximum batch size is split and executed in sequence" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      List(fetchBatchedDataSeq[F](1), fetchBatchedDataSeq[F](2), fetchBatchedDataSeq[F](3)).sequence

    val io = Fetch.runLog[IO](fetch)

    io.map({
        case (log, result) => {
          result shouldEqual List(1, 2, 3)
          log.rounds.size shouldEqual 1
          totalFetched(log.rounds) shouldEqual 3
          totalBatches(log.rounds) shouldEqual 2
        }
      })
      .unsafeToFuture
  }

  "A large (many) fetch to a datasource with a maximum batch size is split and executed in parallel" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Int]] =
      List(fetchBatchedDataPar[F](1), fetchBatchedDataPar[F](2), fetchBatchedDataPar[F](3)).sequence

    val io = Fetch.runLog[IO](fetch)

    io.map({
        case (log, result) => {
          result shouldEqual List(1, 2, 3)
          log.rounds.size shouldEqual 1
          totalFetched(log.rounds) shouldEqual 3
          totalBatches(log.rounds) shouldEqual 2
        }
      })
      .unsafeToFuture
  }

  "Very deep fetches don't overflow stack or heap" in {
    val depth = 5000
    val ids = for {
      id <- 0 to depth
    } yield BatchedDataBigId(
      str1 = "longString" + id,
      str2 = "longString" + (id + 1),
      str3 = "longString" + (id + 2)
    )

    val io = Fetch.runLog[IO](
      ids.toList.traverse(fetchBatchedDataBigId[IO])
    )

    io.map({
        case (log, result) => {
          result shouldEqual ids.map(_.toString)
        }
      })
      .unsafeToFuture
  }
}
