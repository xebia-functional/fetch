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
import scala.language.implicitConversions

import org.scalatest.{AsyncFreeSpec, Matchers}

import cats.syntax.all._
import cats.effect._

import fetch._
import fetch.syntax._

class FetchSyntaxTests extends AsyncFreeSpec with Matchers {
  import TestHelper._

  override val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  implicit def ioToFuture[A](io: IO[A]): Future[A] = io.unsafeToFuture()

  // "Cartesian syntax is implicitly concurrent" in {
  //   val fetch: Fetch[(Int, List[Int])] = (one(1), many(3)).tupled

  //   val io = Fetch.runEnv(fetch)

  //   io.map({
  //     case (env, result) =>
  //       env.rounds.size shouldEqual 1
  //   })
  // }

  // "Apply syntax is implicitly concurrent" in {
  //   val fetch: Fetch[Int] = Fetch.pure((x: Int, y: Int) => x + y).ap2(one(1), one(2))

  //   val io = Fetch.runEnv(fetch)

  //   io.map({
  //     case (env, result) => {
  //       env.rounds.size shouldEqual 1
  //       totalBatches(env.rounds) shouldEqual 1
  //       totalFetched(env.rounds) shouldEqual 2
  //     }
  //   })
  // }

  // "`fetch` syntax allows lifting of any value to the context of a fetch" in {
  //   Fetch.pure(42) shouldEqual 42.fetch
  // }

  // "`fetch` syntax allows lifting of any `Throwable` as a failure on a fetch" in {
  //   case object Ex extends RuntimeException

  //   val f1: Fetch[Int] = Fetch.error(Ex)
  //   val f2: Fetch[Int] = Ex.fetch

  //   val io1 = Fetch.run(f1)
  //   val io2 = Fetch.run(f2)

  //   val e1 = io1.handleError(err => 42)
  //   val e2 = io2.handleError(err => 42)

  //   (e1, e2).mapN(_ shouldEqual _)
  // }

  // "`runFetch` syntax is equivalent to `Fetch#run`" in {
  //   val rf1 = Fetch.run(1.fetch)
  //   val rf2 = 1.fetch.runFetch

  //   (rf1, rf2).mapN(_ shouldEqual _)
  // }

  // "`runEnv` syntax is equivalent to `Fetch#runEnv`" in {
  //   val rf1: IO[(Env, Int)] = Fetch.runEnv(1.fetch)
  //   val rf2: IO[(Env, Int)] = 1.fetch.runEnv

  //   (rf1, rf2).mapN(_ shouldEqual _)
  // }

  // "`runCache` syntax is equivalent to `Fetch#runCache`" in {
  //   val rf1: IO[(DataSourceCache, Int)] = Fetch.runCache(1.fetch)
  //   val rf2: IO[(DataSourceCache, Int)] = 1.fetch.runCache

  //   (rf1, rf2).mapN(_ shouldEqual _)
  // }
}
