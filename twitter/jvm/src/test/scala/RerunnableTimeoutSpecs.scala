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

package fetch.twitterFuture

import io.catbird.util.Rerunnable
import com.twitter.util.{ExecutorServiceFuturePool, FuturePool}
import scala.concurrent.{Future => ScalaFuture, ExecutionContext}
import org.scalatest.{AsyncFlatSpec, Matchers}

import fetch.{FetchMonadError, FetchMonadErrorTimeoutSpec}
import fetch.twitterFuture.implicits._

class RerunnableTimeoutSpec
    extends AsyncFlatSpec
    with Matchers
    with FetchMonadErrorTimeoutSpec[Rerunnable] {

  implicit val pool: FuturePool = FuturePool.interruptibleUnboundedPool

  implicit override val executionContext: ExecutionContext = {
    val executor = pool.asInstanceOf[ExecutorServiceFuturePool].executor
    ExecutionContext.fromExecutorService(executor)
  }

  def runAsFuture[A](rerun: Rerunnable[A]): ScalaFuture[A] =
    Convert.twitterToScalaFuture(rerun.run)

  def fetchMonadError: FetchMonadError[Rerunnable] =
    FetchMonadError[Rerunnable]

}
