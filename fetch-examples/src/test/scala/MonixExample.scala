///*
// * Copyright 2016-2019 47 Degrees, LLC. <http://www.47deg.com>
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//import cats.effect._
//
//import scala.concurrent._
//
//import monix.execution._
//import monix.eval._
//import monix.eval.instances._
//
//import fetch._
//
//import org.scalatest.matchers.should.Matchers
//import org.scalatest.wordspec.AnyWordSpec
//
//class MonixExample extends AnyWordSpec with Matchers {
//  implicit val scheduler: Scheduler      = Scheduler.io(name = "test-scheduler")
//  val executionContext: ExecutionContext = scheduler
//
//  import DatabaseExample._
//
//  "We can run a Fetch into a Monix Task" in {
//    def fetch[F[_]: ConcurrentEffect: ContextShift]: Fetch[F, Author] =
//      Authors.fetchAuthor(1)
//
//    val task = Fetch.runLog[Task](fetch)
//
//    task
//      .map({ case (log, result) =>
//        result shouldEqual Author(1, "William Shakespeare")
//        log.rounds.size shouldEqual 1
//      })
//      .runToFuture
//  }
//}
