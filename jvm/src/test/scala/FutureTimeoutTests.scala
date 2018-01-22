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
import org.scalatest.{AsyncFlatSpec, Matchers}
import fetch.implicits._

// Note that this test cannot run on Scala.js

class FutureTimeoutTests
    extends AsyncFlatSpec
    with Matchers
    with FetchMonadErrorTimeoutSpec[Future] {

  implicit override val executionContext: ExecutionContext = ExecutionContext.Implicits.global

  def runAsFuture[A](fa: Future[A]): Future[A] = fa

  def fetchMonadError: FetchMonadError[Future] = FetchMonadError[Future]
}
