/*
 * Copyright 2016-2017 47 Degrees, LLC. <http://www.47deg.com>
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
import cats.instances.list._
import fetch._
import fetch.implicits._

class FetchSyntaxTests extends AsyncFreeSpec with Matchers {
  import fetch.syntax._
  import TestHelper._

  val ME = FetchMonadError[Future]

  implicit override def executionContext = ExecutionContext.Implicits.global

  "Cartesian syntax is implicitly concurrent" in {
    import cats.syntax.cartesian._

    val fetch: Fetch[(Int, List[Int])] = (one(1) |@| many(3)).tupled

    val fut = Fetch.runEnv[Future](fetch)

    fut.map(env => {
      env.rounds.size shouldEqual 1
    })
  }

  "Apply syntax is implicitly concurrent" in {
    import cats.syntax.apply._

    val fetch: Fetch[Int] = Fetch.pure((x: Int, y: Int) => x + y).ap2(one(1), one(2))

    val fut = Fetch.runEnv[Future](fetch)

    fut.map(env => {
      val rounds = env.rounds

      rounds.size shouldEqual 1
      totalBatches(rounds) shouldEqual 1
      totalFetched(rounds) shouldEqual 2
    })
  }

  "`fetch` syntax allows lifting of any value to the context of a fetch" in {
    Fetch.pure(42) shouldEqual 42.fetch
  }

  "`fetch` syntax allows lifting of any `Throwable` as a failure on a fetch" in {
    case object Ex extends RuntimeException

    val fut1 = Fetch.run[Future](Fetch.error(Ex): Fetch[Int])
    val fut2 = Fetch.run[Future](Ex.fetch: Fetch[Int])

    val e1 = ME.handleErrorWith(fut1)(err => Future.successful(42))
    val e2 = ME.handleErrorWith(fut2)(err => Future.successful(42))

    ME.map2(e1, e2)(_ shouldEqual _)
  }

  "`join` syntax is equivalent to `Fetch#join`" in {
    val join1 = Fetch.join(one(1), many(3))
    val join2 = one(1).join(many(3))

    ME.map2(Fetch.run[Future](join1), Fetch.run[Future](join2))(_ shouldEqual _)
  }

  "`runF` syntax is equivalent to `Fetch#runFetch`" in {

    val rf1 = Fetch.runFetch[Future](1.fetch)
    val rf2 = 1.fetch.runF[Future]

    ME.map2(rf1, rf2)(_ shouldEqual _)
  }

  "`runE` syntax is equivalent to `Fetch#runEnv`" in {

    val rf1 = Fetch.runEnv[Future](1.fetch)
    val rf2 = 1.fetch.runE[Future]

    ME.map2(rf1, rf2)(_ shouldEqual _)
  }

  "`runA` syntax is equivalent to `Fetch#run`" in {

    val rf1 = Fetch.run[Future](1.fetch)
    val rf2 = 1.fetch.runA[Future]

    ME.map2(rf1, rf2)(_ shouldEqual _)
  }
}
