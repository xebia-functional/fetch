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
import cats.data.NonEmptyList
import cats.instances.list._
import cats.syntax.all._
import cats.effect._
import fetch._

class FetchBatchingTests extends FreeSpec with Matchers {
  import TestHelper._

  val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  case class BatchedDataSeq(id: Int)
  implicit object MaxBatchSourceSeq extends DataSource[BatchedDataSeq, Int] {
    override def name = "BatchSourceSeq"

    override def fetch[F[_] : ConcurrentEffect](id: BatchedDataSeq): F[Option[Int]] =
      ConcurrentEffect[F].delay(Some(id.id))

    // override def batch(ids: NonEmptyList[BatchedDataSeq]): Query[Map[BatchedDataSeq, Int]] =
    //   Query.sync(ids.toList.map(one => (one, one.id)).toMap)

    override val maxBatchSize = Some(2)

    override val batchExecution = Sequential
  }

  case class BatchedDataPar(id: Int)
  implicit object MaxBatchSourcePar extends DataSource[BatchedDataPar, Int] {
    override def name = "BatchSourcePar"

    override def fetch[F[_] : ConcurrentEffect](id: BatchedDataPar): F[Option[Int]] =
      ConcurrentEffect[F].delay(Some(id.id))

    // override def fetchMany(ids: NonEmptyList[BatchedDataPar]): Query[Map[BatchedDataPar, Int]] =
    //   Query.sync(ids.toList.map(one => (one, one.id)).toMap)

    override val maxBatchSize = Some(2)

    override val batchExecution = Parallel
  }

  def fetchBatchedDataSeq(id: Int): Fetch[Int] = Fetch(BatchedDataSeq(id))
  def fetchBatchedDataPar(id: Int): Fetch[Int] = Fetch(BatchedDataPar(id))

  "A large fetch to a datasource with a maximum batch size is split and executed in sequence" in {
    val fetch: Fetch[List[Int]] = List.range(1, 6).traverse(fetchBatchedDataSeq)

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    result shouldEqual List(1, 2, 3, 4, 5)

    env.rounds.size shouldEqual 1
    totalFetched(env.rounds) shouldEqual 5
    totalBatches(env.rounds) shouldEqual 3
  }

  "A large fetch to a datasource with a maximum batch size is split and executed in parallel" in {
    val fetch: Fetch[List[Int]] = List.range(1, 6).traverse(fetchBatchedDataPar)

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    result shouldEqual List(1, 2, 3, 4, 5)

    env.rounds.size shouldEqual 1
    totalFetched(env.rounds) shouldEqual 5
    totalBatches(env.rounds) shouldEqual 3
  }

  "Fetches to datasources with a maximum batch size should be split and executed in parallel and sequentially" in {
    val fetch: Fetch[List[Int]] =
      List.range(1, 6).traverse(fetchBatchedDataPar) *>
        List.range(1, 6).traverse(fetchBatchedDataSeq)

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    result shouldEqual List(1, 2, 3, 4, 5)

    env.rounds.size shouldEqual 1
    totalFetched(env.rounds) shouldEqual 5 + 5
    totalBatches(env.rounds) shouldEqual 3 + 3
  }

  "A large (many) fetch to a datasource with a maximum batch size is split and executed in sequence" in {
    val fetch: Fetch[List[Int]] =
      List(fetchBatchedDataSeq(1), fetchBatchedDataSeq(2), fetchBatchedDataSeq(3)).sequence

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    result shouldEqual List(1, 2, 3)
    env.rounds.size shouldEqual 1
    totalFetched(env.rounds) shouldEqual 3
    totalBatches(env.rounds) shouldEqual 2
  }

  "A large (many) fetch to a datasource with a maximum batch size is split and executed in parallel" in {
    val fetch: Fetch[List[Int]] =
      List(fetchBatchedDataPar(1), fetchBatchedDataPar(2), fetchBatchedDataPar(3)).sequence

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    result shouldEqual List(1, 2, 3)
    env.rounds.size shouldEqual 1
    totalFetched(env.rounds) shouldEqual 3
    totalBatches(env.rounds) shouldEqual 2
  }

  "Very deep fetches don't overflow stack or heap" in {
    val depth = 200
    val list  = (1 to depth).toList
    val fetch: Fetch[List[Int]] = list
      .map(x => (0 until x).toList.traverse(fetchBatchedDataSeq))
      .foldLeft(
        Fetch.pure(List.empty[Int])
      )(_ >> _)

    val io = Fetch.runEnv(fetch)
    val (env, result) = io.unsafeRunSync

    result shouldEqual (0 until depth).toList
    env.rounds.size shouldEqual depth
  }
}
