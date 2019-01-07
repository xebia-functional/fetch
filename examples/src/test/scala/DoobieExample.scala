/*
 * Copyright 2016-2019 47 Degrees, LLC. <http://www.47deg.com>
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

import cats.data.NonEmptyList
import cats.effect._
import cats.instances.list._
import cats.syntax.all._

import doobie.{Query => _, _}
import doobie.h2.H2Transactor
import doobie.implicits._

import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.{ExecutionContext}

import fetch._

class DoobieExample extends WordSpec with Matchers {
  implicit val executionContext = ExecutionContext.Implicits.global

  implicit val t: Timer[IO]         = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  val createTransactor: IO[Transactor[IO]] =
    H2Transactor[IO]("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "")

  case class AuthorId(id: Int)
  case class Author(id: Int, name: String)

  val dropTable = sql"DROP TABLE IF EXISTS author".update.run

  val createTable = sql"""
     CREATE TABLE author (
       id INTEGER PRIMARY KEY,
       name VARCHAR(20) NOT NULL UNIQUE
     )
    """.update.run

  def addAuthor(author: Author) =
    sql"INSERT INTO author (id, name) VALUES(${author.id}, ${author.name})".update.run

  val authors: List[Author] =
    List("William Shakespeare", "Charles Dickens", "George Orwell").zipWithIndex.map {
      case (name, id) => Author(id + 1, name)
    }

  val xa: Transactor[IO] =
    (for {
      xa <- createTransactor
      _  <- (dropTable *> createTable *> authors.traverse(addAuthor)).transact(xa)
    } yield xa).unsafeRunSync()

  val authorDS = new DataSource[AuthorId, Author] {
    override def name = "AuthorDoobie"

    override def fetch[F[_]: ConcurrentEffect](id: AuthorId): F[Option[Author]] =
      LiftIO[F].liftIO(fetchById(id).transact(xa))

    override def batch[F[_]: ConcurrentEffect](
        ids: NonEmptyList[AuthorId]): F[Map[AuthorId, Author]] =
      LiftIO[F].liftIO(
        fetchByIds(ids)
          .map { authors =>
            authors.map(a => AuthorId(a.id) -> a).toMap
          }
          .transact(xa)
      )

    def fetchById(id: AuthorId): ConnectionIO[Option[Author]] =
      sql"SELECT * FROM author WHERE id = $id".query[Author].option

    def fetchByIds(ids: NonEmptyList[AuthorId]): ConnectionIO[List[Author]] = {
      val q = fr"SELECT * FROM author WHERE" ++ Fragments.in(fr"id", ids)
      q.query[Author].list
    }

    implicit val authorIdMeta: Meta[AuthorId] =
      Meta[Int].xmap(AuthorId(_), _.id)
  }

  def author[F[_]: ConcurrentEffect](id: Int): Fetch[F, Author] =
    Fetch(AuthorId(id), authorDS)

  "We can fetch one author from the DB" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, Author] =
      author(1)

    val io: IO[(Env, Author)] = Fetch.runEnv[IO](fetch)

    val (env, result) = io.unsafeRunSync

    result shouldEqual Author(1, "William Shakespeare")
    env.rounds.size shouldEqual 1
  }

  "We can fetch multiple authors from the DB in parallel" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Author]] =
      List(1, 2).traverse(author[F])

    val io: IO[(Env, List[Author])] = Fetch.runEnv[IO](fetch)

    val (env, result) = io.unsafeRunSync

    result shouldEqual Author(1, "William Shakespeare") :: Author(2, "Charles Dickens") :: Nil
    env.rounds.size shouldEqual 1
  }

  "We can fetch multiple authors from the DB using a for comprehension" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Author]] =
      for {
        a <- author(1)
        b <- author(a.id + 1)
      } yield List(a, b)

    val io: IO[(Env, List[Author])] = Fetch.runEnv[IO](fetch)

    val (env, result) = io.unsafeRunSync

    result shouldEqual Author(1, "William Shakespeare") :: Author(2, "Charles Dickens") :: Nil
    env.rounds.size shouldEqual 2
  }

}
