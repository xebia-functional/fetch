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

import fetch._
import fetch.implicits._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

import cats.data.NonEmptyList
import cats.syntax.cartesian._

import cats.free.FreeTopExt

object TestApp2 {

  implicit val executionContext = ExecutionContext.Implicits.global

  case class BD(id: Int)
  implicit object MaxBatchSource extends DataSource[BD, Int] {
    override def name = "BSrc"
    override def fetchOne(id: BD): Query[Option[Int]] = {
      println("fetchOne")
      Query.sync(Option(id.id))
    }
    override def fetchMany(ids: NonEmptyList[BD]): Query[Map[BD, Int]] = {
      println("fetchMany")
      Query.sync(ids.toList.map(one => (one, one.id)).toMap)
    }

  }

  def fetchBatchedData(id: Int): Fetch[Int] = Fetch(BD(id)).map { x =>
    Thread.sleep(500)
    println("boo")
    x
  }

  case class Many(n: Int)
  implicit object ManySource extends DataSource[Many, List[Int]] {
    override def name = "ManySource"
    override def fetchOne(id: Many): Query[Option[List[Int]]] =
      Query.sync(Option(0 until id.n toList))
    override def fetchMany(ids: NonEmptyList[Many]): Query[Map[Many, List[Int]]] =
      Query.sync(ids.toList.map(m => (m, 0 until m.n toList)).toMap)
  }
  def many(id: Int): Fetch[List[Int]] = Fetch(Many(id))

  def printFetchResultBlocking[A](fut: Future[(FetchEnv, A)]): Unit = {
    val (env, res) = Await.result(fut, Duration.Inf)
    println(s"\n>> Result : $res\n\nRounds :")
    println(env.rounds.zipWithIndex.map(roundToString.tupled).mkString("\n"))
    println()
    println("\n\n===============================================\n\n")
  }

  val roundToString: (Round, Int) => String = (r, index) =>
    s"""| Round #${index + 1} (${r.request.getClass.getName})
        | Request  : ${r.request}
        | Cache    : Contains ${r.cache.asInstanceOf[InMemoryCache].state.size} element(s)
        |   ${r.cache}
        | Response : ${r.response}
        | Duration : ${r.duration}
        |------------------------------------------------""".stripMargin

  def main(args: Array[String]): Unit = {

    // 1 round
    val fetch1: Fetch[(Int, Int)] =
      (fetchBatchedData(1) |@| fetchBatchedData(2)).tupled
    // println(FreeTopExt.print(fetch1))
    printFetchResultBlocking(Fetch.runFetch[Future](fetch1))

    def fetchFor(i: Int, j: Int): Fetch[Int] =
      for {
        a <- fetchBatchedData(i)
        b <- fetchBatchedData(j)
      } yield a + b

    // 2 rounds (2 x 2)
    val fetch2: Fetch[(Int, Int)] = Fetch.join(fetchFor(30, 31), fetchFor(32, 33))
    // println(FreeTopExt.print(fetch2))
    printFetchResultBlocking(Fetch.runFetch[Future](fetch2))

    // 2 rounds (2 x 3)
    val fetch3 = Fetch.traverse(List(9, 10, 11))(i => fetchFor(i, i + 100))
    // println(FreeTopExt.print(fetch3))
    printFetchResultBlocking(Fetch.runFetch[Future](fetch3))

    // 3 rounds (9 + 4 + 6)
    val fetch4 = Fetch.join(
      Fetch.join(
        for {
          a <- Fetch.traverse(List(2, 3, 4))(fetchBatchedData)
          b <- Fetch.traverse(List(0, 1))(many)
          c <- Fetch.traverse(List(9, 10, 11))(fetchBatchedData)
        } yield c,
        for {
          a <- Fetch.traverse(List(5, 6, 7))(fetchBatchedData)
          b <- Fetch.traverse(List(2, 3))(many)
          c <- Fetch.traverse(List(12, 13, 14))(fetchBatchedData)
        } yield c
      ),
      Fetch.traverse(List(15, 16, 17))(fetchBatchedData)
    )
    // println(FreeTopExt.print(fetch4))
    // printFetchResultBlocking(Fetch.runFetch[Future](fetch4))

    // 3 rounds (2 + 2 + 2)
    val fetch5 = Fetch.join(
      for {
        a <- fetchBatchedData(2)
        b <- many(1)
        c <- fetchBatchedData(5)
      } yield c,
      for {
        a <- fetchBatchedData(3)
        b <- many(2)
        c <- fetchBatchedData(4)
      } yield c
    )
    // println(FreeTopExt.print(fetch5))
    printFetchResultBlocking(Fetch.runFetch[Future](fetch5))

    // 2 rounds (2 + 1)
    val fetch6: Fetch[(Int, Int)] = Fetch.join(
      fetchBatchedData(1),
      for {
        a <- Fetch.pure(2).map { i =>
          // this will be executed every time we search for independent queries
          // this only happens with `Fetch.pure` values
          println(s"hello $i -"); i
        }
        _ <- Fetch.pure(2)
        b <- fetchBatchedData(3)
        c <- fetchBatchedData(4)
      } yield a + b
    )
    // println(FreeTopExt.print(fetch6))
    printFetchResultBlocking(Fetch.runFetch[Future](fetch6))

    val fetchBig =
      for {
        i <- Fetch.join(fetch1, fetch4)
        _ <- Fetch.join(many(5), fetchBatchedData(10000))
        j <- for {
          _ <- Fetch.traverse(List.range(10, 15))(many)
          x <- Fetch.traverse(List.range(100, 105))(fetchBatchedData)
        } yield x
        k <- fetchBatchedData(55)
      } yield k
    // println(FreeTopExt.print(fetchBig))
    // printFetchResultBlocking(Fetch.runFetch[Future](fetchBig))

  }
}
