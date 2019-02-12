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

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats._
import cats.data.NonEmptyList
import cats.effect._
import cats.instances.list._
import cats.syntax.all._

import io.circe._
import io.circe.generic.semiauto._

import org.http4s._
import org.http4s.headers._
import org.http4s.util.CaseInsensitiveString
import org.http4s.circe._
import org.http4s.client._
import org.http4s.client.dsl._
import org.http4s.client.blaze._
import org.scalatest.{Matchers, WordSpec}

import fetch.{Data, DataSource, Fetch}

class GithubExample extends WordSpec with Matchers {
  implicit val executionContext = ExecutionContext.Implicits.global

  val ACCESS_TOKEN: String = sys.env("ORG_GITHUB_TOKEN")

  implicit val t: Timer[IO]         = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  // http4s client which is used by the datasources

  def client[F[_]: ConcurrentEffect] =
    Http1Client[F](BlazeClientConfig.defaultConfig)

  // Github HTTP api

  val GITHUB: Uri = Uri.unsafeFromString("https://api.github.com")

  val REL_NEXT = "rel=\"next\"".r

  def hasNext[F[_]: ConcurrentEffect](res: Response[F]): Boolean =
    res.headers
      .get(CaseInsensitiveString("Link"))
      .fold(false)({ h =>
        REL_NEXT.findFirstIn(h.value).isDefined
      })

  def getNextLink[F[_]: ConcurrentEffect](raw: String): F[String] = {
    REL_NEXT
      .findFirstMatchIn(raw)
      .fold(
        Sync[F].raiseError[String](new Exception("Couldn't find next link"))
      )(m => {
        Sync[F].pure(m.before.toString.split(",").last.trim.dropWhile(_ == '<').takeWhile(_ != '>'))
      })
  }

  def getNext[F[_]: ConcurrentEffect](res: Response[F]): F[Uri] =
    res.headers
      .get(CaseInsensitiveString("Link"))
      .fold(Sync[F].raiseError[Uri](new Exception("next not found")))(
        raw => getNextLink(raw.value).map(Uri.unsafeFromString(_))
      )

  // -- repos

  type Org = String
  case class Repo(
      name: String,
      fork: Boolean,
      forks_count: Int,
      stargazers_count: Int,
      watchers_count: Int,
      languages_url: String,
      contributors_url: String
  )

  implicit val repoD: Decoder[Repo] = deriveDecoder

  private def fetchOrgRepos[F[_]](c: Client[F], req: Request[F])(
      implicit CF: ConcurrentEffect[F],
      E: EntityDecoder[F, List[Repo]]
  ): F[List[Repo]] = {
    for {
      result <- c.fetch[List[Repo]](req) {
        case Status.Ok(res) => {
          if (hasNext(res)) {
            for {
              repos <- res.as[List[Repo]]
              nxt   <- getNext(res)
              newReq = req.withUri(nxt)
              moreRepos <- fetchOrgRepos(c, newReq)
            } yield repos ++ moreRepos
          } else
            res.as[List[Repo]]
        }
        case res => {
          CF.raiseError(new Exception(res.body.toString))
        }
      }
    } yield result
  }

  object Repos extends Data[(String, String), Repo] {
    def name = "Repositories"

    def source[F[_]: ConcurrentEffect]: DataSource[F, (String, String), Repo] =
      new DataSource[F, (String, String), Repo] {
        implicit val repoED: EntityDecoder[F, Repo]        = jsonOf
        implicit val reposED: EntityDecoder[F, List[Repo]] = jsonOf

        def CF = ConcurrentEffect[F]

        def data = Repos

        def fetch(id: (String, String)): F[Option[Repo]] = {
          client[F] >>= ((c) => {
            val (owner, repo) = id
            val url           = GITHUB / "repos" / owner / repo +? ("access_token", ACCESS_TOKEN)
            val req           = Request[F](Method.GET, url)
            for {
              result <- c.fetch[Repo](req)({
                case Status.Ok(res) =>
                  res.as[Repo]
                case res =>
                  CF.raiseError(new Exception(res.body.toString))
              })
            } yield Option(result)
          })
        }
      }
  }

  def fetchRepo[F[_]: ConcurrentEffect](r: (String, String)): Fetch[F, Repo] =
    Fetch(r, Repos.source)

  object OrgRepos extends Data[Org, List[Repo]] {
    def name = "Org repositories"

    def source[F[_]: ConcurrentEffect]: DataSource[F, Org, List[Repo]] =
      new DataSource[F, Org, List[Repo]] {
        implicit val repoED: EntityDecoder[F, Repo]        = jsonOf
        implicit val reposED: EntityDecoder[F, List[Repo]] = jsonOf

        def CF = ConcurrentEffect[F]

        def data = OrgRepos

        def fetch(org: Org): F[Option[List[Repo]]] = {
          client[F] >>= ((c) => {
            val url = GITHUB / "orgs" / org / "repos" +? ("access_token", ACCESS_TOKEN) +? ("type", "public") +? ("per_page", 100)
            val req = Request[F](Method.GET, url)
            fetchOrgRepos(c, req).map(Option.apply)
          })
        }
      }
  }

  def orgRepos[F[_]: ConcurrentEffect](org: Org): Fetch[F, List[Repo]] =
    Fetch(org, OrgRepos.source)

  // -- languages

  type Language = String

  object Languages extends Data[Repo, List[Language]] {
    def name = "Languages"

    def source[F[_]: ConcurrentEffect]: DataSource[F, Repo, List[Language]] =
      new DataSource[F, Repo, List[Language]] {
        def CF = ConcurrentEffect[F]

        def data = Languages

        def fetch(repo: Repo): F[Option[List[Language]]] = {
          client[F] >>= ((c) => {
            val url = Uri.unsafeFromString(repo.languages_url) +? ("access_token", ACCESS_TOKEN)
            val req = Request[F](Method.GET, url)
            fetchLanguages(c, req).map(Option.apply)
          })
        }
      }
  }

  private def fetchLanguages[F[_]](c: Client[F], req: Request[F])(
      implicit CF: ConcurrentEffect[F]
  ): F[List[Language]] = {
    implicit val objED: EntityDecoder[F, JsonObject] = jsonOf

    for {
      result <- c.fetch[List[Language]](req) {
        case Status.Ok(res) => {
          if (hasNext(res)) {
            for {
              langs <- res.as[JsonObject].map(j => j.toList.map(_._1))
              nxt   <- getNext(res)
              newReq = req.withUri(nxt)
              moreLangs <- fetchLanguages(c, newReq)
            } yield langs ++ moreLangs
          } else
            res.as[JsonObject].map(j => j.toList.map(_._1))
        }
        case res => {
          CF.raiseError(new Exception(res.body.toString))
        }
      }
    } yield result
  }

  def repoLanguages[F[_]: ConcurrentEffect](repo: Repo): Fetch[F, List[Language]] =
    Fetch(repo, Languages.source)

  // -- contributors

  case class Contributor(login: String, contributions: Int)

  implicit val contribD: Decoder[Contributor] = deriveDecoder

  private def fetchContributors[F[_]](c: Client[F], req: Request[F])(
      implicit CF: ConcurrentEffect[F]
  ): F[List[Contributor]] = {
    implicit val objED: EntityDecoder[F, List[Contributor]] = jsonOf

    for {
      result <- c.fetch[List[Contributor]](req) {
        case Status.Ok(res) => {
          if (hasNext(res)) {
            for {
              contribs <- res.as[List[Contributor]]
              nxt      <- getNext(res)
              newReq = req.withUri(nxt)
              moreContribs <- fetchContributors(c, newReq)
            } yield contribs ++ moreContribs
          } else
            res.as[List[Contributor]]
        }
        case res => {
          CF.raiseError(new Exception(res.body.toString))
        }
      }
    } yield result
  }

  object Contributors extends Data[Repo, List[Contributor]] {
    def name = "Contributors"

    def source[F[_]: ConcurrentEffect]: DataSource[F, Repo, List[Contributor]] =
      new DataSource[F, Repo, List[Contributor]] {
        def CF = ConcurrentEffect[F]

        def data = Contributors

        def fetch(repo: Repo): F[Option[List[Contributor]]] = {
          client[F] >>= ((c) => {
            val url = Uri.unsafeFromString(repo.contributors_url) +? ("access_token", ACCESS_TOKEN) +? ("type", "public") +? ("per_page", 100)
            val req = Request[F](Method.GET, url)
            fetchContributors(c, req).map(Option.apply)
          })
        }
      }
  }

  def repoContributors[F[_]: ConcurrentEffect](repo: Repo): Fetch[F, List[Contributor]] =
    Fetch(repo, Contributors.source)

  case class Project(
      repo: Repo,
      contributors: List[Contributor],
      languages: List[Language]
  )

  "We can fetch org repos" in {
    def fetchProject[F[_]: ConcurrentEffect](repo: Repo): Fetch[F, Project] =
      (repoContributors(repo), repoLanguages(repo)).mapN({
        case (contribs, langs) =>
          Project(repo = repo, contributors = contribs, languages = langs)
      })

    def fetch[F[_]: ConcurrentEffect] =
      for {
        repos    <- orgRepos("47deg")
        projects <- repos.traverse(fetchProject[F])
      } yield projects

    val io = Fetch.runLog[IO](fetch)

    val (log, result) = io.unsafeRunSync

    log.rounds.size shouldEqual 2
  }

  "We can fetch multiple repos in parallel" in {

    def fetchRepo[F[_]: ConcurrentEffect](r: (String, String)): Fetch[F, Repo] =
      Fetch(r, Repos.source)

    def fetch[F[_]: ConcurrentEffect]: Fetch[F, List[Repo]] =
      List(
        ("monix", "monix"),
        ("typelevel", "cats"),
        ("typelevel", "cats-effect"),
        ("47deg", "fetch")).traverse(fetchRepo[F])

    val io = Fetch.runLog[IO](fetch)

    val (log, result) = io.unsafeRunSync

    log.rounds.size shouldEqual 1
  }
}
