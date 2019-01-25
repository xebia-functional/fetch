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

object DatabaseExample {
  case class AuthorId(id: Int)
  case class Author(id: Int, name: String)

  object Queries {
    implicit val authorIdMeta: Meta[AuthorId] =
      Meta[Int].xmap(AuthorId(_), _.id)

    def fetchById(id: AuthorId): ConnectionIO[Option[Author]] =
      sql"SELECT * FROM author WHERE id = $id".query[Author].option

    def fetchByIds(ids: NonEmptyList[AuthorId]): ConnectionIO[List[Author]] = {
      val q = fr"SELECT * FROM author WHERE" ++ Fragments.in(fr"id", ids)
      q.query[Author].list
    }
  }

  object Database {
    val createTable = sql"""
       CREATE TABLE author (
         id INTEGER PRIMARY KEY,
         name VARCHAR(20) NOT NULL UNIQUE
       )
      """.update.run

    val dropTable = sql"DROP TABLE IF EXISTS author".update.run

    def addAuthor(author: Author) =
      sql"INSERT INTO author (id, name) VALUES(${author.id}, ${author.name})".update.run

    val authors: List[Author] =
      List("William Shakespeare", "Charles Dickens", "George Orwell").zipWithIndex.map {
        case (name, id) => Author(id + 1, name)
      }

    def createTransactor[F[_]: ConcurrentEffect] =
      H2Transactor[F]("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "")

    def transactor[F[_]: ConcurrentEffect]: F[Transactor[F]] =
      for {
        xa <- createTransactor
        _  <- (dropTable *> createTable *> authors.traverse(addAuthor)).transact(xa)
      } yield xa
  }

  object Authors extends Data {
    def db[F[_]]: DataSource[F, AuthorId, Author] =
      new DataSource[F, AuthorId, Author] {
        override def fetch(id: AuthorId)(
            implicit C: ConcurrentEffect[F]
        ): F[Option[Author]] =
          Database.transactor
            .flatMap(Queries.fetchById(id).transact(_))

        override def batch(ids: NonEmptyList[AuthorId])(
            implicit C: ConcurrentEffect[F]
        ): F[Map[AuthorId, Author]] =
          Database.transactor
            .flatMap(Queries.fetchByIds(ids).transact(_))
            .map { authors =>
              authors.map(a => AuthorId(a.id) -> a).toMap
            }
      }

    def fetchAuthor[F[_]: ConcurrentEffect](id: Int): Fetch[F, Author] =
      Fetch(AuthorId(id), Authors, Authors.db)
  }
}

class DoobieExample extends WordSpec with Matchers {
  import DatabaseExample._

  val executionContext              = ExecutionContext.Implicits.global
  implicit val t: Timer[IO]         = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  "We can fetch one author from the DB" in {
    val io: IO[(Env, Author)] = Fetch.runEnv[IO](Authors.fetchAuthor(1))

    val (env, result) = io.unsafeRunSync

    result shouldEqual Author(1, "William Shakespeare")
    env.rounds.size shouldEqual 1
  }

  "We can fetch multiple authors from the DB in parallel" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Author]] =
      List(1, 2).traverse(Authors.fetchAuthor[F])

    val io: IO[(Env, List[Author])] = Fetch.runEnv[IO](fetch)

    val (env, result) = io.unsafeRunSync

    result shouldEqual Author(1, "William Shakespeare") :: Author(2, "Charles Dickens") :: Nil
    env.rounds.size shouldEqual 1
  }

  "We can fetch multiple authors from the DB using a for comprehension" in {
    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Author]] =
      for {
        a <- Authors.fetchAuthor(1)
        b <- Authors.fetchAuthor(a.id + 1)
      } yield List(a, b)

    val io: IO[(Env, List[Author])] = Fetch.runEnv[IO](fetch)

    val (env, result) = io.unsafeRunSync

    result shouldEqual Author(1, "William Shakespeare") :: Author(2, "Charles Dickens") :: Nil
    env.rounds.size shouldEqual 2
  }

}
