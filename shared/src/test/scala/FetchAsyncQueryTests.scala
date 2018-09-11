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

import scala.language.implicitConversions
import scala.concurrent.{ExecutionContext, Future}

import org.scalatest.{AsyncFreeSpec, Matchers}

import cats.instances.list._
import cats.effect._
import cats.syntax.all._

import fetch._

class FetchAsyncQueryTests extends AsyncFreeSpec with Matchers {
  import TestHelper._

  implicit override val executionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  implicit def ioToFuture[A](io: IO[A]): Future[A] = io.unsafeToFuture()

  // "We can interpret an async fetch into an IO" in {
  //   val fetch: Fetch[Article] = article(1)
  //   val io: IO[Article]  = Fetch.run(fetch)

  //   io.map(_ shouldEqual Article(1, "An article with id 1"))
  // }

  // "We can combine several async data sources and interpret a fetch into an IO" in {
  //   val fetch: Fetch[(Article, Author)] = for {
  //     art    <- article(1)
  //     author <- author(art)
  //   } yield (art, author)

  //   val io = Fetch.run(fetch)

  //   io.map(_ shouldEqual (Article(1, "An article with id 1"), Author(2, "@egg2")))
  // }

  // "We can use combinators in a for comprehension and interpret a fetch from async sources into an IO" in {
  //   val fetch: Fetch[List[Article]] = for {
  //     articles <- List(1, 1, 2).traverse(article)
  //   } yield articles

  //   val io = Fetch.run(fetch)
  //   io.map(_ shouldEqual List(
  //     Article(1, "An article with id 1"),
  //     Article(1, "An article with id 1"),
  //     Article(2, "An article with id 2")
  //   ))
  // }

  // "We can use combinators and multiple sources in a for comprehension and interpret a fetch from async sources into an IO" in {
  //   val fetch = for {
  //     articles <- List(1, 1, 2).traverse(article)
  //     authors  <- articles.traverse(author)
  //   } yield (articles, authors)

  //   val io = Fetch.run(fetch)

  //   io.map(_ shouldEqual (
  //     List(
  //       Article(1, "An article with id 1"),
  //       Article(1, "An article with id 1"),
  //       Article(2, "An article with id 2")
  //     ),
  //     List(
  //       Author(2, "@egg2"),
  //       Author(2, "@egg2"),
  //       Author(3, "@egg3")
  //     )
  //   ))
  // }
}
