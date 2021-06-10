/*
 * Copyright 2016-2021 47 Degrees Open Source <https://www.47deg.com>
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
import doobie.util.ExecutionContexts

import org.scalatest._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors

import fetch._

object DatabaseExample {
  case class AuthorId(id: Int)
  case class Author(id: Int, name: String)

  object Queries {

    import doobie.implicits._

    implicit val authorIdMeta: Meta[AuthorId] =
      Meta[Int].imap(AuthorId(_))(_.id)

    def fetchById(id: AuthorId): ConnectionIO[Option[Author]] =
      sql"SELECT * FROM author WHERE id = $id".query[Author].option

    def fetchByIds(ids: NonEmptyList[AuthorId]): ConnectionIO[List[Author]] = {
      val q = fr"SELECT * FROM author WHERE" ++ Fragments.in(fr"id", ids)
      q.query[Author].to[List]
    }
  }

  object Database {
    def connectionPool[F[_]: Sync](n: Int): Resource[F, ExecutionContext] =
      ExecutionContexts.fixedThreadPool[F](n)

    def transactionPool[F[_]: Sync]: Resource[F, ExecutionContext] =
      ExecutionContexts.cachedThreadPool

    import doobie.implicits._

    def createTable[F[_]: Sync](tx: Transactor[F]) = sql"""
       CREATE TABLE author (
         id INTEGER PRIMARY KEY,
         name VARCHAR(20) NOT NULL UNIQUE
       )
      """.update.run.transact(tx)

    def dropTable[F[_]: Sync](tx: Transactor[F]) =
      sql"DROP TABLE IF EXISTS author".update.run.transact(tx)

    def addAuthor[F[_]: Sync](author: Author)(tx: Transactor[F]) =
      sql"INSERT INTO author (id, name) VALUES(${author.id}, ${author.name})".update.run
        .transact(tx)

    val authors: List[Author] =
      List("William Shakespeare", "Charles Dickens", "George Orwell").zipWithIndex.map {
        case (name, id) => Author(id + 1, name)
      }

    def createTransactor[F[_]: Async] =
      for {
        conn <- connectionPool[F](1)
        tx <-
          H2Transactor
            .newH2Transactor[F](
              "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
              "sa",
              "",
              conn
            )
      } yield tx
  }

  object Authors extends Data[AuthorId, Author] {

    import doobie.implicits._
    def name = "Authors"

    def db[F[_]: Async]: DataSource[F, AuthorId, Author] =
      new DataSource[F, AuthorId, Author] {
        def data = Authors

        override def CF = Concurrent[F]

        override def fetch(id: AuthorId): F[Option[Author]] =
          Database
            .createTransactor[F]
            .use(Queries.fetchById(id).transact(_))

        override def batch(ids: NonEmptyList[AuthorId]): F[Map[AuthorId, Author]] =
          Database
            .createTransactor[F]
            .use(Queries.fetchByIds(ids).transact(_))
            .map(authors => authors.map(a => AuthorId(a.id) -> a).toMap)
      }

    def fetchAuthor[F[_]: Async](id: Int): Fetch[F, Author] =
      Fetch(AuthorId(id), Authors.db)
  }
}

class DoobieExample extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  import DatabaseExample._
  import Database._

  val executionContext                                  = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))
  implicit val ioRuntime: unsafe.IORuntime              = unsafe.IORuntime.global
  implicit val transactor: Resource[IO, Transactor[IO]] = createTransactor[IO]

  override def beforeAll(): Unit =
    (transactor
      .use { tx =>
        createTable(tx) *> authors.traverse(addAuthor(_)(tx))
      })
      .void
      .unsafeRunSync()
  override def afterAll(): Unit = transactor.use(dropTable(_)).void.unsafeRunSync()

  "We can fetch one author from the DB" in {
    val io: IO[(Log, Author)] = Fetch.runLog[IO](Authors.fetchAuthor(1))

    val (log, result) = io.unsafeRunSync()

    result shouldEqual Author(1, "William Shakespeare")
    log.rounds.size shouldEqual 1
  }

  "We can fetch multiple authors from the DB in parallel" in {
    def fetch[F[_]: Async]: Fetch[F, List[Author]] =
      List(1, 2).traverse(Authors.fetchAuthor[F])

    val io: IO[(Log, List[Author])] = Fetch.runLog[IO](fetch)

    val (log, result) = io.unsafeRunSync()

    result shouldEqual Author(1, "William Shakespeare") :: Author(2, "Charles Dickens") :: Nil
    log.rounds.size shouldEqual 1
  }

  "We can fetch multiple authors from the DB using a for comprehension" in {
    def fetch[F[_]: Async]: Fetch[F, List[Author]] =
      for {
        a <- Authors.fetchAuthor(1)
        b <- Authors.fetchAuthor(a.id + 1)
      } yield List(a, b)

    val io: IO[(Log, List[Author])] = Fetch.runLog[IO](fetch)

    val (log, result) = io.unsafeRunSync()

    result shouldEqual Author(1, "William Shakespeare") :: Author(2, "Charles Dickens") :: Nil
    log.rounds.size shouldEqual 2
  }

}
