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

import org.scalatest.DoNotDiscover

import scala.concurrent._
import java.util.concurrent._
import scala.concurrent.duration._

import cats.effect._
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

@DoNotDiscover
class FetchSpec extends AsyncFreeSpec with Matchers {
  override val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO]                   = IO.timer(executionContext)
  implicit val cs: ContextShift[IO]               = IO.contextShift(executionContext)

  def countFetches(r: Request): Int =
    r.request match {
      case FetchOne(_, _) => 1
      case Batch(ids, _)  => ids.toList.size
    }

  def totalFetched(rs: Seq[Round]): Int =
    rs.map((round: Round) => round.queries.map(countFetches).sum).toList.sum

  def countBatches(r: Request): Int =
    r.request match {
      case FetchOne(_, _) => 0
      case Batch(_, _)    => 1
    }

  def totalBatches(rs: Seq[Round]): Int =
    rs.map((round: Round) => round.queries.map(countBatches).sum).toList.sum
}
