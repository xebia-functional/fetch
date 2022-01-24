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

import cats._
import cats.data.NonEmptyList
import cats.instances.list._
import cats.syntax.all._
import cats.effect._
import fetch._

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class FetchBatchingTests extends FetchSpec {
  import TestHelper._

  case class BatchedDataSeq(id: Int)

  object SeqBatch extends Data[BatchedDataSeq, Int] {
    def name = "Sequential batching"

    implicit def source[F[_]: Concurrent]: DataSource[F, BatchedDataSeq, Int] =
      new DataSource[F, BatchedDataSeq, Int] {
        override def data = SeqBatch

        override def CF = Concurrent[F]

        override def fetch(id: BatchedDataSeq): F[Option[Int]] =
          CF.pure(Some(id.id))

        override val maxBatchSize = Some(2)

        override val batchExecution = Sequentially
      }
  }

  case class BatchedDataPar(id: Int)

  object ParBatch extends Data[BatchedDataPar, Int] {
    def name = "Parallel batching"

    implicit def source[F[_]: Concurrent]: DataSource[F, BatchedDataPar, Int] =
      new DataSource[F, BatchedDataPar, Int] {
        override def data = ParBatch

        override def CF = Concurrent[F]

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

    implicit def source[F[_]: Concurrent]: DataSource[F, BatchedDataBigId, String] =
      new DataSource[F, BatchedDataBigId, String] {
        override def data = BigIdData

        override def CF = Concurrent[F]

        override def fetch(request: BatchedDataBigId): F[Option[String]] =
          batch(NonEmptyList.one(request)).map(_.get(request))

        override def batch(ids: NonEmptyList[BatchedDataBigId]): F[Map[BatchedDataBigId, String]] =
          CF.pure(
            ids.map(id => id -> id.toString).toList.toMap
          )

        override val batchExecution = InParallel
      }
  }

  case class BatchAcrossFetchData(id: Int)

  object BatchAcrossFetches extends Data[BatchAcrossFetchData, String] {
    def name = "Batch across Fetches"

    private val batchesCounter = new AtomicInteger(0)
    private val fetchesCounter = new AtomicInteger(0)

    def reset(): Unit = {
      batchesCounter.set(0)
      fetchesCounter.set(0)
    }

    def counters: (Int, Int) =
      (fetchesCounter.get(), batchesCounter.get())

    def unBatchedSource[F[_]: Concurrent]: DataSource[F, BatchAcrossFetchData, String] =
      new DataSource[F, BatchAcrossFetchData, String] {
        override def data = BatchAcrossFetches

        override def CF = Concurrent[F]

        override def fetch(request: BatchAcrossFetchData): F[Option[String]] = {
          fetchesCounter.incrementAndGet()
          CF.pure(Some(request.toString))
        }

        override def batch(
            ids: NonEmptyList[BatchAcrossFetchData]
        ): F[Map[BatchAcrossFetchData, String]] = {
          batchesCounter.incrementAndGet()
          CF.pure(
            ids.map(id => id -> id.toString).toList.toMap
          )
        }

        override val batchExecution = InParallel
      }

    def batchedSource[F[_]: Async](
        interval: FiniteDuration
    ): Resource[F, DataSource[F, BatchAcrossFetchData, String]] =
      DataSource.batchAcrossFetches(unBatchedSource, interval)
  }

  def fetchBatchedDataSeq[F[_]: Concurrent](id: Int): Fetch[F, Int] =
    Fetch(BatchedDataSeq(id), SeqBatch.source)

  def fetchBatchedDataPar[F[_]: Concurrent](id: Int): Fetch[F, Int] =
    Fetch(BatchedDataPar(id), ParBatch.source)

  def fetchBatchedDataBigId[F[_]: Concurrent](id: BatchedDataBigId): Fetch[F, String] =
    Fetch(id, BigIdData.source)

  "A large fetch to a datasource with a maximum batch size is split and executed in sequence" in {
    def fetch[F[_]: Concurrent]: Fetch[F, List[Int]] =
      Fetch.batchAll(List.range(1, 6).map(fetchBatchedDataSeq[F]): _*)

    val io = Fetch.runLog[IO](fetch)

    io.map { case (log, result) =>
      result shouldEqual List(1, 2, 3, 4, 5)
      log.rounds.size shouldEqual 1
      totalFetched(log.rounds) shouldEqual 5
      totalBatches(log.rounds) shouldEqual 3
    }.unsafeToFuture()
  }

  "A large fetch to a datasource with a maximum batch size is split and executed in parallel" in {
    def fetch[F[_]: Concurrent]: Fetch[F, List[Int]] =
      Fetch.batchAll(List.range(1, 6).map(fetchBatchedDataPar[F]): _*)

    val io = Fetch.runLog[IO](fetch)

    io.map { case (log, result) =>
      result shouldEqual List(1, 2, 3, 4, 5)
      log.rounds.size shouldEqual 1
      totalFetched(log.rounds) shouldEqual 5
      totalBatches(log.rounds) shouldEqual 3
    }.unsafeToFuture()
  }

  "Fetches to datasources with a maximum batch size should be split and executed in parallel and sequentially when using productR" in {
    def fetch[F[_]: Concurrent]: Fetch[F, List[Int]] =
      Fetch.batchAll(List.range(1, 6).map(fetchBatchedDataPar[F]): _*) *>
        Fetch.batchAll(List.range(1, 6).map(fetchBatchedDataSeq[F]): _*)

    val io = Fetch.runLog[IO](fetch)

    io.map { case (log, result) =>
      result shouldEqual List(1, 2, 3, 4, 5)
      log.rounds.size shouldEqual 1
      totalFetched(log.rounds) shouldEqual 5 + 5
      totalBatches(log.rounds) shouldEqual 3 + 3
    }.unsafeToFuture()
  }

  "Fetches to datasources with a maximum batch size should be split and executed in parallel and sequentially when using productL" in {
    def fetch[F[_]: Concurrent]: Fetch[F, List[Int]] =
      Fetch.batchAll(List.range(1, 6).map(fetchBatchedDataPar[F]): _*) <*
        Fetch.batchAll(List.range(1, 6).map(fetchBatchedDataSeq[F]): _*)

    val io = Fetch.runLog[IO](fetch)

    io.map { case (log, result) =>
      result shouldEqual List(1, 2, 3, 4, 5)
      log.rounds.size shouldEqual 1
      totalFetched(log.rounds) shouldEqual 5 + 5
      totalBatches(log.rounds) shouldEqual 3 + 3
    }.unsafeToFuture()
  }

  "A large (many) fetch to a datasource with a maximum batch size is split and executed in sequence" in {
    def fetch[F[_]: Concurrent]: Fetch[F, List[Int]] =
      Fetch.batchAll(List(1, 2, 3).map(fetchBatchedDataSeq[F]): _*)

    val io = Fetch.runLog[IO](fetch)

    io.map { case (log, result) =>
      result shouldEqual List(1, 2, 3)
      log.rounds.size shouldEqual 1
      totalFetched(log.rounds) shouldEqual 3
      totalBatches(log.rounds) shouldEqual 2
    }.unsafeToFuture()
  }

  "A large (many) fetch to a datasource with a maximum batch size is split and executed in parallel" in {
    def fetch[F[_]: Concurrent]: Fetch[F, List[Int]] =
      Fetch.batchAll(List(1, 2, 3).map(fetchBatchedDataPar[F]): _*)

    val io = Fetch.runLog[IO](fetch)

    io.map { case (log, result) =>
      result shouldEqual List(1, 2, 3)
      log.rounds.size shouldEqual 1
      totalFetched(log.rounds) shouldEqual 3
      totalBatches(log.rounds) shouldEqual 2
    }.unsafeToFuture()
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
      Fetch.batchAll(ids.toList.map(fetchBatchedDataBigId[IO]): _*)
    )

    io.map { case (log, result) =>
      result shouldEqual ids.map(_.toString)
    }.unsafeToFuture()
  }

  "Fetches produced across unrelated fetches to a DataSource that is NOT batched across fetch executions should NOT be bundled together" in {
    BatchAcrossFetches.reset()
    val dataSource = BatchAcrossFetches.unBatchedSource[IO]
    val id1        = BatchAcrossFetchData(1)
    val id2        = BatchAcrossFetchData(2)
    val execution1 = Fetch.run[IO](Fetch(id1, dataSource))
    val execution2 = Fetch.run[IO](Fetch(id2, dataSource))
    val singleExecution = (execution1, execution2).parMapN { (_, _) =>
      val (fetchRequests, batchRequests) = BatchAcrossFetches.counters
      fetchRequests shouldEqual 2
      batchRequests shouldEqual 0
    }
    singleExecution.unsafeToFuture()
  }

  "Fetches produced across unrelated fetches to a DataSource that is batched across fetch executions should be bundled together" in {
    BatchAcrossFetches.reset()
    val dataSource = BatchAcrossFetches.batchedSource[IO](500.millis)
    val id1        = BatchAcrossFetchData(1)
    val id2        = BatchAcrossFetchData(2)
    dataSource
      .use { dataSource =>
        val execution1 = Fetch.run[IO](Fetch(id1, dataSource))
        val execution2 = Fetch.run[IO](Fetch(id2, dataSource))
        val singleExecution = (execution1, execution2).parMapN { (_, _) =>
          val (fetchRequests, batchRequests) = BatchAcrossFetches.counters
          fetchRequests shouldEqual 0
          batchRequests shouldEqual 1
        }
        singleExecution
      }
      .unsafeToFuture()
  }
}
