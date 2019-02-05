/*
 * Copyright 2016-2019 47 Degrees, LLC. <http://www.47deg.com>
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

    implicit def source[F[_] : ConcurrentEffect]: DataSource[F, BatchedDataSeq, Int] = new DataSource[F, BatchedDataSeq, Int] {
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

    implicit def source[F[_] : ConcurrentEffect]: DataSource[F, BatchedDataPar, Int] = new DataSource[F, BatchedDataPar, Int] {
      override def data = ParBatch

      override def CF = ConcurrentEffect[F]

      override def fetch(id: BatchedDataPar): F[Option[Int]] =
        CF.pure(Some(id.id))

      override val maxBatchSize = Some(2)

      override val batchExecution = InParallel
    }
  }

  def fetchBatchedDataSeq[F[_] : ConcurrentEffect](id: Int): Fetch[F, Int] =
    Fetch(BatchedDataSeq(id), SeqBatch.source)

  def fetchBatchedDataPar[F[_] : ConcurrentEffect](id: Int): Fetch[F, Int] =
    Fetch(BatchedDataPar(id), ParBatch.source)

  "A large fetch to a datasource with a maximum batch size is split and executed in sequence" in {
    def fetch[F[_] : ConcurrentEffect]: Fetch[F, List[Int]] =
      List.range(1, 6).traverse(fetchBatchedDataSeq[F])

    val io = Fetch.runLog[IO](fetch)

    io.map({
      case (log, result) => {
        result shouldEqual List(1, 2, 3, 4, 5)
        log.rounds.size shouldEqual 1
        totalFetched(log.rounds) shouldEqual 5
        totalBatches(log.rounds) shouldEqual 3
      }
    }).unsafeToFuture
  }

  "A large fetch to a datasource with a maximum batch size is split and executed in parallel" in {
    def fetch[F[_] : ConcurrentEffect]: Fetch[F, List[Int]] =
      List.range(1, 6).traverse(fetchBatchedDataPar[F])

    val io = Fetch.runLog[IO](fetch)

    io.map({
      case (log, result) => {
        result shouldEqual List(1, 2, 3, 4, 5)
        log.rounds.size shouldEqual 1
        totalFetched(log.rounds) shouldEqual 5
        totalBatches(log.rounds) shouldEqual 3
      }
    }).unsafeToFuture
  }

  "Fetches to datasources with a maximum batch size should be split and executed in parallel and sequentially" in {
    def fetch[F[_] : ConcurrentEffect]: Fetch[F, List[Int]] =
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
    }).unsafeToFuture
  }

  "A large (many) fetch to a datasource with a maximum batch size is split and executed in sequence" in {
    def fetch[F[_] : ConcurrentEffect]: Fetch[F, List[Int]] =
      List(fetchBatchedDataSeq[F](1), fetchBatchedDataSeq[F](2), fetchBatchedDataSeq[F](3)).sequence

    val io = Fetch.runLog[IO](fetch)

    io.map({
      case (log, result) => {
        result shouldEqual List(1, 2, 3)
        log.rounds.size shouldEqual 1
        totalFetched(log.rounds) shouldEqual 3
        totalBatches(log.rounds) shouldEqual 2
      }
    }).unsafeToFuture
  }

  "A large (many) fetch to a datasource with a maximum batch size is split and executed in parallel" in {
    def fetch[F[_] : ConcurrentEffect]: Fetch[F, List[Int]] =
      List(fetchBatchedDataPar[F](1), fetchBatchedDataPar[F](2), fetchBatchedDataPar[F](3)).sequence

    val io = Fetch.runLog[IO](fetch)

    io.map({
      case (log, result) => {
        result shouldEqual List(1, 2, 3)
        log.rounds.size shouldEqual 1
        totalFetched(log.rounds) shouldEqual 3
        totalBatches(log.rounds) shouldEqual 2
      }
    }).unsafeToFuture
  }

  "Very deep fetches don't overflow stack or heap" in {
    val depth = 200
    val list  = (1 to depth).toList
    def fetch[F[_] : ConcurrentEffect]: Fetch[F, List[Int]] =
      list.map(x => (0 until x).toList.traverse(fetchBatchedDataSeq[F]))
        .foldLeft(
          Fetch.pure[F, List[Int]](List.empty[Int])
        )(_ >> _)

    val io = Fetch.runLog[IO](fetch)

    io.map({
      case (log, result) => {
        result shouldEqual (0 until depth).toList
        log.rounds.size shouldEqual depth
      }
    }).unsafeToFuture
  }
}
