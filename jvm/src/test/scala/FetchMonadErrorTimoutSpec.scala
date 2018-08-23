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

import java.util.concurrent.TimeoutException
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import org.scalatest.{AsyncFlatSpecLike, Matchers}

// Note that this test cannot run on Scala.js

trait FetchMonadErrorTimeoutSpec[F[_]] { self: AsyncFlatSpecLike with Matchers =>

  // def runAsFuture[A](fa: F[A]): Future[A]

  // def fetchMonadError: FetchMonadError[F]

  // def delayQuery(timeout: Duration, delay: FiniteDuration): Query[Option[Int]] =
  //   Query.async((ok, fail) => {
  //     Thread.sleep(delay.toMillis)
  //     ok(Some(1))
  //   }, timeout)

  // "FetchMonadError" should "fail with timeout when a Query does not complete in time" in {
  //   recoverToSucceededIf[TimeoutException] {
  //     runAsFuture { fetchMonadError.runQuery(delayQuery(100.millis, 600.millis)) }
  //   }
  // }

  // it should "not fail with timeout when a Query does complete in time" in {
  //   runAsFuture {
  //     fetchMonadError.runQuery(delayQuery(300.millis, 100.millis))
  //   }.map(_ shouldEqual Some(1))
  // }

  // it should "not fail with timeout when infinite timeout specified" in {
  //   runAsFuture {
  //     fetchMonadError.runQuery(delayQuery(Duration.Inf, 100.millis))
  //   }.map(_ shouldEqual Some(1))
  // }

}
