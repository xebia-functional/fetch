/*
 * Copyright 2016-2022 47 Degrees Open Source <https://www.47deg.com>
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

import cats._
import cats.effect._
import cats.implicits._
import cats.laws.discipline._
import org.scalatestplus.scalacheck.Checkers
import org.typelevel.discipline.scalatest._
import _root_.org.scalacheck._
import cats.effect.testkit.TestInstances
import org.scalatest.funspec.AnyFunSpec

object FetchArbitrary extends TestInstances {
  import TestHelper._
  implicit val arb: Arbitrary[Fetch[IO, Int]] = Arbitrary(
    Gen.oneOf(
      one[IO](1),
      one[IO](2),
      // many[IO](1),
      // many[IO](2),
      anotherOne[IO](1),
      anotherOne[IO](2),
      never[IO]
    )
  )

  implicit val arbF: Arbitrary[Fetch[IO, Int => Int]] = Arbitrary(
    Gen.oneOf(
      one[IO](1).map(i => {_: Int => i}),
      one[IO](2).map(i => {_: Int => i}),
      // many[IO](1),
      // many[IO](2),
      anotherOne[IO](1).map(i => {_: Int => i}),
      anotherOne[IO](2).map(i => {_: Int => i}),
      never[IO].map(i => {_: Int => i})
    )
  )

  implicit def testEq[A: Eq](implicit ticker: Ticker): Eq[Fetch[IO, A]] = Eq.instance{
    case (a1, a2) => 
      unsafeRun(Fetch.run(a1)) === unsafeRun(Fetch.run(a2))
  }
}

class FetchLawTests extends AnyFunSpec with Discipline with Checkers with FunSpecDiscipline {
  import FetchArbitrary.{arb, arbF, testEq, Ticker}
  implicit val MyTicker: Ticker = Ticker()
  checkAll("Fetch", MonadTests[Fetch[IO, *]].monad[Int, Int, Int])
}