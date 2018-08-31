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
import org.scalatest.{AsyncFreeSpec, Matchers}
import cats.data.NonEmptyList
import cats.instances.list._
import cats.syntax.semigroupal._
import cats.syntax.foldable._
import cats.syntax.apply._
import fetch._

class FetchBatchingTests extends AsyncFreeSpec with Matchers {
  import TestHelper._

  // implicit override def executionContext = ExecutionContext.Implicits.global

  // case class BatchedDataSeq(id: Int)
  // implicit object MaxBatchSourceSeq extends DataSource[BatchedDataSeq, Int] {
  //   override def name = "BatchSourceSeq"

  //   override def fetchOne(id: BatchedDataSeq): Query[Option[Int]] =
  //     Query.sync(Some(id.id))

  //   override def fetchMany(ids: NonEmptyList[BatchedDataSeq]): Query[Map[BatchedDataSeq, Int]] =
  //     Query.sync(ids.toList.map(one => (one, one.id)).toMap)

  //   override val maxBatchSize = Some(2)

  //   override val batchExecution = Sequential
  // }

  // case class BatchedDataPar(id: Int)
  // implicit object MaxBatchSourcePar extends DataSource[BatchedDataPar, Int] {
  //   override def name = "BatchSourcePar"

  //   override def fetchOne(id: BatchedDataPar): Query[Option[Int]] =
  //     Query.sync(Some(id.id))

  //   override def fetchMany(ids: NonEmptyList[BatchedDataPar]): Query[Map[BatchedDataPar, Int]] =
  //     Query.sync(ids.toList.map(one => (one, one.id)).toMap)

  //   override val maxBatchSize = Some(2)

  //   override val batchExecution = Parallel
  // }

  // def fetchBatchedDataSeq(id: Int): Fetch[Int] = Fetch(BatchedDataSeq(id))
  // def fetchBatchedDataPar(id: Int): Fetch[Int] = Fetch(BatchedDataPar(id))

  // "A large fetch to a datasource with a maximum batch size is split and executed in sequence" in {
  //   val fetch: Fetch[List[Int]] = Fetch.traverse(List.range(1, 6))(fetchBatchedDataSeq)
  //   Fetch.runFetch[Future](fetch).map {
  //     case (env, res) =>
  //       res shouldEqual List(1, 2, 3, 4, 5)
  //       totalFetched(env.rounds) shouldEqual 5
  //       totalBatches(env.rounds) shouldEqual 2
  //       env.rounds.size shouldEqual 3
  //   }
  // }

  // "A large fetch to a datasource with a maximum batch size is split and executed in parallel" in {
  //   val fetch: Fetch[List[Int]] = Fetch.traverse(List.range(1, 6))(fetchBatchedDataPar)
  //   Fetch.runFetch[Future](fetch).map {
  //     case (env, res) =>
  //       res shouldEqual List(1, 2, 3, 4, 5)
  //       totalFetched(env.rounds) shouldEqual 5
  //       totalBatches(env.rounds) shouldEqual 2
  //       env.rounds.size shouldEqual 1
  //   }
  // }

  // "Fetches to datasources with a maximum batch size should be split and executed in parallel and sequentially" in {
  //   val fetch: Fetch[List[Int]] =
  //     Fetch.traverse(List.range(1, 6))(fetchBatchedDataPar) *>
  //       Fetch.traverse(List.range(1, 6))(fetchBatchedDataSeq)

  //   Fetch.runFetch[Future](fetch).map {
  //     case (env, res) =>
  //       res shouldEqual List(1, 2, 3, 4, 5)
  //       totalFetched(env.rounds) shouldEqual 5 + 5
  //       totalBatches(env.rounds) shouldEqual 2 + 2
  //       env.rounds.size shouldEqual 3
  //   }
  // }

  // "A large (many) fetch to a datasource with a maximum batch size is split and executed in sequence" in {
  //   val fetch: Fetch[List[Int]] =
  //     Fetch.multiple(BatchedDataSeq(1), BatchedDataSeq(2), BatchedDataSeq(3))
  //   Fetch.runFetch[Future](fetch).map {
  //     case (env, res) =>
  //       res shouldEqual List(1, 2, 3)
  //       totalFetched(env.rounds) shouldEqual 3
  //       totalBatches(env.rounds) shouldEqual 2 // FetchMany(NEL(1, 2)) and FetchMany(NEL(3))
  //       env.rounds.size shouldEqual 2
  //   }
  // }

  // "A large (many) fetch to a datasource with a maximum batch size is split and executed in parallel" in {
  //   val fetch: Fetch[List[Int]] =
  //     Fetch.multiple(BatchedDataPar(1), BatchedDataPar(2), BatchedDataPar(3))
  //   Fetch.runFetch[Future](fetch).map {
  //     case (env, res) =>
  //       res shouldEqual List(1, 2, 3)
  //       totalFetched(env.rounds) shouldEqual 3
  //       totalBatches(env.rounds) shouldEqual 1
  //       env.rounds.size shouldEqual 1
  //   }
  // }

  // "Very deep fetches don't overflow stack or heap" in {
  //   import cats.syntax.traverse._
  //   import cats.syntax.flatMap._

  //   val depth = 200
  //   val list  = (1 to depth).toList
  //   val fetch: Fetch[List[Int]] = list
  //     .map(x => (0 until x).toList.traverse(fetchBatchedDataSeq))
  //     .foldLeft(Fetch.pure(List.empty[Int]))(_ >> _)

  //   Fetch.runFetch[Future](fetch).map {
  //     case (env, res) =>
  //       res shouldEqual (0 until depth).toList
  //       totalFetched(env.rounds) shouldEqual depth
  //       env.rounds.size shouldEqual depth
  //   }
  // }
}
