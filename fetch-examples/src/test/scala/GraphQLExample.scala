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

package fetch

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import atto._, Atto._
import cats.implicits._
import cats.data.NonEmptyList
import cats.effect._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

// Types
case class Organization(org: String, projects: List[Project])
case class Project(name: Option[String], languages: List[String], collaborators: List[String])
case class Repo(name: String)

class GraphQLExample extends AnyWordSpec with Matchers {
  implicit val executionContext     = ExecutionContext.Implicits.global
  implicit val t: Timer[IO]         = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  def countFetches(r: Request): Int =
    r.request match {
      case FetchOne(_, _) => 1
      case Batch(ids, _)  => ids.toList.size
    }

  def totalFetched(rs: Seq[Round]): Int =
    rs.map((round: Round) => round.queries.map(countFetches).sum).toList.sum

  def countBatches(r: Request): Int =
    r.request match {
      case FetchOne(_, _) => 0
      case Batch(_, _)    => 1
    }

  def totalBatches(rs: Seq[Round]): Int =
    rs.map((round: Round) => round.queries.map(countBatches).sum).toList.sum

  import Parsers._
  import Sources._

  val query = """
    query {
      organization(login:"47deg") {
        repositories(first: 100){
          name,
          languages,
          collaborators
        }
      }
    }
  """

  val langsQuery = """
    query {
      organization(login:"47deg") {
        repositories(first: 100){
          languages
        }
      }
    }
  """

  val collabsQuery = """
    query {
      organization(login:"47deg") {
        repositories(first: 100){
          collaborators
        }
      }
    }
  """

  val orgQuery = """
    query {
      organization(login:"47deg") {
        repositories(first: 100) {
          name
        }
      }
    }
  """

  val repoQuery = """
    query {
      organization(login:"47deg") {
        repositories(first: 1){
          name,
          languages,
          collaborators
        }
      }
    }
  """

  def runQuery[F[_]: ConcurrentEffect](q: String): Fetch[F, Organization] =
    queryParser.parseOnly(q) match {
      case ParseResult.Done(_, query) => fetchOrg[F](query)
      case _                          => Fetch.error(new Exception("Oh noes"))
    }

  "We can interpret queries" in {
    val io            = Fetch.runLog[IO](runQuery(query))
    val (log, result) = io.unsafeRunSync

    result shouldEqual Organization(
      "47deg",
      List(
        Project(Some("fetch"), List("scala"), List("Peter", "Ale")),
        Project(Some("arrow"), List("kotlin"), List("Raul", "Paco", "Simon"))
      )
    )

    log.rounds.size shouldEqual 2
    totalBatches(log.rounds) shouldEqual 2
  }

  "We can interpret queries with only languages" in {
    val io            = Fetch.runLog[IO](runQuery(langsQuery))
    val (log, result) = io.unsafeRunSync

    result shouldEqual Organization(
      "47deg",
      List(Project(None, List("scala"), List()), Project(None, List("kotlin"), List()))
    )

    log.rounds.size shouldEqual 2
    totalBatches(log.rounds) shouldEqual 1
  }

  "We can interpret queries with only collaborators" in {
    val io            = Fetch.runLog[IO](runQuery(collabsQuery))
    val (log, result) = io.unsafeRunSync
    result shouldEqual Organization(
      "47deg",
      List(
        Project(None, List(), List("Peter", "Ale")),
        Project(None, List(), List("Raul", "Paco", "Simon"))
      )
    )

    log.rounds.size shouldEqual 2
    totalBatches(log.rounds) shouldEqual 1
  }

  "We can interpret queries with no nested joins" in {
    val io            = Fetch.runLog[IO](runQuery(orgQuery))
    val (log, result) = io.unsafeRunSync
    result shouldEqual Organization(
      "47deg",
      List(Project(Some("fetch"), List(), List()), Project(Some("arrow"), List(), List()))
    )

    log.rounds.size shouldEqual 1
    totalBatches(log.rounds) shouldEqual 0
  }

  "We can interpret queries with a limited number of repositories" in {
    val io            = Fetch.runLog[IO](runQuery(repoQuery))
    val (log, result) = io.unsafeRunSync

    result shouldEqual Organization(
      "47deg",
      List(Project(Some("fetch"), List("scala"), List("Peter", "Ale")))
    )

    log.rounds.size shouldEqual 2
    totalBatches(log.rounds) shouldEqual 0
  }

  def fetchOrg[F[_]: ConcurrentEffect](q: OrganizationQuery): Fetch[F, Organization] =
    q.repos match {
      case None    => Fetch.pure(Organization(q.org, List()))
      case Some(r) => fetchRepos(q.org, r).map(rs => Organization(q.org, rs))
    }

  private def fetchRepos[F[_]: ConcurrentEffect](
      org: String,
      q: RepositoriesQuery
  ): Fetch[F, List[Project]] =
    q match {
      case RepositoriesQuery(n, name, Some(_), Some(_)) =>
        for {
          repos <- Repos.fetch(org)
          projects <-
            repos
              .take(n)
              .traverse(repo =>
                (Languages.fetch(repo), Collaborators.fetch(repo)).mapN { case (ls, cs) =>
                  Project(name >> Some(repo.name), ls, cs)
                }
              )
        } yield projects

      case RepositoriesQuery(n, name, None, None) =>
        Repos.fetch(org).map(_.map(r => Project(name >> Some(r.name), List(), List())))

      case RepositoriesQuery(n, name, Some(_), None) =>
        for {
          repos <- Repos.fetch(org)
          projects <- repos.traverse { r =>
            Languages.fetch(r).map(ls => Project(name >> Some(r.name), ls, List()))
          }
        } yield projects

      case RepositoriesQuery(n, name, None, Some(_)) =>
        for {
          repos <- Repos.fetch(org)
          projects <- repos.traverse { r =>
            Collaborators.fetch(r).map(cs => Project(name >> Some(r.name), List(), cs))
          }
        } yield projects
    }
}

object Parsers {
  def queryParser: Parser[OrganizationQuery] =
    rawParser.map({ case (o, n) =>
      OrganizationQuery(
        o,
        n.map({ case (i, name, langs, colls) =>
          RepositoriesQuery(
            i,
            if (name) Some(()) else None,
            if (langs) Some(LanguagesQuery()) else None,
            if (colls) Some(CollaboratorsQuery()) else None
          )
        })
      )
    })

  def rawParser: Parser[(String, Option[(Int, Boolean, Boolean, Boolean)])] =
    for {
      _ <- skipWhitespace
      _ <- string("query")

      _   <- leftBrace
      org <- organization

      _     <- leftBrace
      repos <- opt(repositories)

      _ <- rightBrace
      _ <- rightBrace
    } yield (org, repos)

  def leftBrace: Parser[Unit]  = skipWhitespace >> char('{') >> skipWhitespace
  def rightBrace: Parser[Unit] = skipWhitespace >> char('}') >> skipWhitespace

  def organization: Parser[String] =
    string("organization") >>
      parens(string("login") >> char(':') >> stringLiteral)

  def repositories: Parser[(Int, Boolean, Boolean, Boolean)] =
    for {
      i                    <- string("repositories") >> parens(string("first") >> char(':') >> skipWhitespace >> int)
      _                    <- leftBrace
      (name, langs, colls) <- organizationQuery
      _                    <- rightBrace
    } yield (i, name, langs, colls)

  def organizationQuery: Parser[(Boolean, Boolean, Boolean)] =
    for {
      name  <- opt(string("name")).map(!_.isEmpty)
      _     <- opt(char(',') >> skipWhitespace)
      langs <- languages
      _     <- opt(char(',') >> skipWhitespace)
      colls <- collaborators
    } yield (name, langs, colls)

  val languages: Parser[Boolean] =
    opt(string("languages")).map(!_.isEmpty)

  val collaborators: Parser[Boolean] =
    opt(string("collaborators")).map(!_.isEmpty)

  case class LanguagesQuery()
  case class CollaboratorsQuery()
  case class RepositoriesQuery(
      n: Int,
      name: Option[Unit] = None,
      languages: Option[LanguagesQuery] = None,
      collaborators: Option[CollaboratorsQuery] = None
  )
  case class OrganizationQuery(org: String, repos: Option[RepositoriesQuery])
}

object Sources {
  val reposDb = Map(
    "47deg" -> List(Repo("fetch"), Repo("arrow"))
  )

  object Repos extends Data[String, List[Repo]] {
    def name = "Repos"

    def source[F[_]: ConcurrentEffect]: DataSource[F, String, List[Repo]] =
      new DataSource[F, String, List[Repo]] {
        def CF   = ConcurrentEffect[F]
        def data = Repos

        def fetch(id: String): F[Option[List[Repo]]] =
          CF.pure(reposDb.get(id))
      }

    def fetch[F[_]: ConcurrentEffect](org: String): Fetch[F, List[Repo]] =
      Fetch(org, source)
  }

  val langsDb = Map(
    Repo("fetch") -> List("scala"),
    Repo("arrow") -> List("kotlin")
  )

  object Languages extends Data[Repo, List[String]] {
    def name = "Languages"

    def source[F[_]: ConcurrentEffect]: DataSource[F, Repo, List[String]] =
      new DataSource[F, Repo, List[String]] {
        def CF   = ConcurrentEffect[F]
        def data = Languages

        def fetch(id: Repo): F[Option[List[String]]] =
          CF.pure(langsDb.get(id))
      }

    def fetch[F[_]: ConcurrentEffect](repo: Repo): Fetch[F, List[String]] =
      Fetch(repo, source)
  }

  val collabsDb = Map(
    Repo("fetch") -> List("Peter", "Ale"),
    Repo("arrow") -> List("Raul", "Paco", "Simon")
  )

  object Collaborators extends Data[Repo, List[String]] {
    def name = "Collaborators"

    def source[F[_]: ConcurrentEffect]: DataSource[F, Repo, List[String]] =
      new DataSource[F, Repo, List[String]] {
        def CF   = ConcurrentEffect[F]
        def data = Collaborators

        def fetch(id: Repo): F[Option[List[String]]] =
          CF.pure(collabsDb.get(id))
      }

    def fetch[F[_]: ConcurrentEffect](repo: Repo): Fetch[F, List[String]] =
      Fetch(repo, source)
  }
}
