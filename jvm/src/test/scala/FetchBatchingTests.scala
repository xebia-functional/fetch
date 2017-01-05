/*
 * Copyright 2016 47 Degrees, LLC. <http://www.47deg.com>
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
import scala.concurrent.duration._

import org.scalatest._

import cats.MonadError
import cats.data.NonEmptyList
import cats.instances.list._

import fetch._
import fetch.implicits._

class FetchBatchingTests extends AsyncFreeSpec with Matchers {
  import TestHelper._

  implicit override def executionContext = ExecutionContext.Implicits.global

  case class BatchedData(id: Int)
  implicit object MaxBatchSource extends DataSource[BatchedData, Int] {
    override def name = "BatchSource"
    override def fetchOne(id: BatchedData): Query[Option[Int]] = {
      Query.sync(Option(id.id))
    }
    override def fetchMany(ids: NonEmptyList[BatchedData]): Query[Map[BatchedData, Int]] =
      Query.sync(ids.toList.map(one => (one, one.id)).toMap)

    override val maxBatchSize = Some(2)
  }
  def fetchBatchedData(id: Int): Fetch[Int] = Fetch(BatchedData(id))

  "A large fetch to a datasource with a maximum batch size is split and executed in sequence" in {
    val fetch: Fetch[List[Int]] = Fetch.traverse(List.range(1, 6))(fetchBatchedData)
    Fetch.runFetch[Future](fetch).map {
      case (env, res) =>
        res shouldEqual List(1, 2, 3, 4, 5)
        totalFetched(env.rounds) shouldEqual 5
        env.rounds.size shouldEqual 3
    }
  }

  "Very deep fetches don't overflow stack or heap" in {
    import cats.syntax.traverse._

    val depth = 20
    val fetch: Fetch[List[Int]] = (1 to depth).toList
      .map((x) => (0 until 2).toList.traverse(fetchBatchedData))
      .foldLeft(
        Fetch.pure(List.empty[Int])
      )((acc, f) => acc.flatMap((x) => f))

    Fetch.runFetch[Future](fetch).map {
      case (env, res) =>
        res shouldEqual List(0, 1)
        totalFetched(env.rounds) shouldEqual 2
        env.rounds.size shouldEqual 1
    }
  }
}
