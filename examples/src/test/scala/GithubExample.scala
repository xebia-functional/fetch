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

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats._
import cats.data.NonEmptyList
import cats.effect._
import cats.instances.list._
import cats.syntax.all._
import cats.temp.par._

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

import fetch.{DataSource, Env, Fetch}

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

  val REL_NEXT = """ rel="next" """".trim

  def hasNext[F[_]: ConcurrentEffect](res: Response[F]): Boolean =
    res.headers.get(CaseInsensitiveString("link")).fold(false)(_.value.contains(REL_NEXT))

  def getNextLink(raw: String): String =
    raw.value.takeWhile(_ != ';').dropWhile(_ == '<').takeWhile(_ != '>').trim

  def getNext[F[_]: ConcurrentEffect](res: Response[F]): F[Uri] =
    res.headers
      .get(CaseInsensitiveString("link"))
      .fold(Sync[F].raiseError[Uri](new Exception("next not found")))(raw =>
        Applicative[F].pure(Uri.unsafeFromString(getNextLink(raw.value))))

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

  // todo: correctly parse NEXT links
  private def fetchOrgRepos[F[_]](c: Client[F], req: Request[F])(
      implicit C: ConcurrentEffect[F]
  ): F[List[Repo]] = {
    implicit val reposED: EntityDecoder[F, List[Repo]] = jsonOf

    for {
      result <- c.fetch[List[Repo]](req) {
        case Status.Ok(res) =>
          if (hasNext(res)) {
            for {
              repos <- res.as[List[Repo]]
              nxt   <- getNext(res)
              newReq = req.withUri(nxt)
              moreRepos <- fetchOrgRepos(c, newReq)
            } yield repos ++ moreRepos
          } else
            res.as[List[Repo]]
        case res => {
          C.raiseError(new Exception(res.body.toString))
        }
      }
    } yield result
  }

  object RepoSource extends DataSource[(String, String), Repo] {
    override def name = "Repo source"

    override def fetch[F[_]](id: (String, String))(
        implicit C: ConcurrentEffect[F],
        P: Par[F]
    ): F[Option[Repo]] = {
      implicit val reposED: EntityDecoder[F, Repo] = jsonOf

      client[F] >>= ((c) => {
        val (owner, repo) = id
        val url           = GITHUB / "repos" / owner / repo +? ("access_token", ACCESS_TOKEN)
        val req           = Request[F](Method.GET, url)
        for {
          result <- c.fetch[Repo](req)({
            case Status.Ok(res) =>
              res.as[Repo]
            case res =>
              C.raiseError(new Exception(res.body.toString))
          })
        } yield Option(result)
      })
    }
  }

  def fetchRepo[F[_]: ConcurrentEffect: Par](r: (String, String)): Fetch[F, Repo] =
    Fetch(r, RepoSource)

  object OrgPublicRepos extends DataSource[Org, List[Repo]] {
    override def name = "Org repos"

    override def fetch[F[_]](org: Org)(
        implicit C: ConcurrentEffect[F],
        P: Par[F]
    ): F[Option[List[Repo]]] = {
      client[F] >>= ((c) => {
        val url = GITHUB / "orgs" / org / "repos" +? ("access_token", ACCESS_TOKEN) +? ("type", "public") +? ("per_page", 100)
        val req = Request[F](Method.GET, url)
        fetchOrgRepos(c, req).map(Option.apply)
      })
    }
  }

  def orgRepos[F[_]: ConcurrentEffect](org: Org): Fetch[F, List[Repo]] =
    Fetch(org, OrgPublicRepos)

  // -- languages

  type Language = String

  private def fetchLanguages[F[_]](c: Client[F], req: Request[F])(
      implicit C: ConcurrentEffect[F]
  ): F[List[Language]] = {
    implicit val objED: EntityDecoder[F, JsonObject] = jsonOf

    for {
      result <- c.fetch[List[Language]](req) {
        case Status.Ok(res) =>
          res.as[JsonObject].map(j => j.toList.map(_._1))
        case res => {
          C.raiseError(new Exception(res.body.toString))
        }
      }
    } yield result
  }

  object RepoLanguages extends DataSource[Repo, List[Language]] {
    def name = "Repo languages"

    override def fetch[F[_]](repo: Repo)(
        implicit C: ConcurrentEffect[F],
        P: Par[F]
    ): F[Option[List[Language]]] = {
      client[F] >>= ((c) => {
        val url = Uri.unsafeFromString(repo.languages_url) +? ("access_token", ACCESS_TOKEN)
        val req = Request[F](Method.GET, url)
        fetchLanguages(c, req).map(Option.apply)
      })
    }
  }

  def repoLanguages[F[_]: ConcurrentEffect](repo: Repo): Fetch[F, List[Language]] =
    Fetch(repo, RepoLanguages)

  // -- contributors

  case class Contributor(login: String, contributions: Int)

  implicit val contribD: Decoder[Contributor] = deriveDecoder

  // todo: correctly parse NEXT links
  private def fetchContributors[F[_]](c: Client[F], req: Request[F])(
      implicit C: ConcurrentEffect[F]
  ): F[List[Contributor]] = {
    implicit val objED: EntityDecoder[F, List[Contributor]] = jsonOf

    for {
      result <- c.fetch[List[Contributor]](req) {
        case Status.Ok(res) =>
          res.as[List[Contributor]]
        case res => {
          C.raiseError(new Exception(res.body.toString))
        }
      }
    } yield result
  }

  object RepoContributors extends DataSource[Repo, List[Contributor]] {
    def name = "Repo contributors"

    override def fetch[F[_]](repo: Repo)(
        implicit C: ConcurrentEffect[F],
        P: Par[F]
    ): F[Option[List[Contributor]]] = {
      client[F] >>= ((c) => {
        val url = Uri.unsafeFromString(repo.contributors_url) +? ("access_token", ACCESS_TOKEN) +? ("type", "public") +? ("per_page", 100)
        val req = Request[F](Method.GET, url)
        fetchContributors(c, req).map(Option.apply)
      })
    }
  }

  def repoContributors[F[_]: ConcurrentEffect](repo: Repo): Fetch[F, List[Contributor]] =
    Fetch(repo, RepoContributors)

  case class Project(
      repo: Repo,
      contributors: List[Contributor],
      languages: List[Language]
  )

  "We can fetch org repos" in {
    def fetchProject[F[_]: ConcurrentEffect: Par](repo: Repo): Fetch[F, Project] =
      (repoContributors(repo), repoLanguages(repo)).mapN({
        case (contribs, langs) =>
          Project(repo = repo, contributors = contribs, languages = langs)
      })

    def fetch[F[_]: ConcurrentEffect: Par] =
      for {
        repos    <- orgRepos("47deg")
        projects <- repos.traverse(fetchProject[F])
      } yield projects

    val io = Fetch.runEnv[IO](fetch)

    val (env, result) = io.unsafeRunSync

    env.rounds.size shouldEqual 2
  }

  "We can fetch multiple repos in parallel" in {

    def fetchRepo[F[_]: ConcurrentEffect: Par](r: (String, String)): Fetch[F, Repo] =
      Fetch(r, RepoSource)

    def fetch[F[_]: ConcurrentEffect: Par]: Fetch[F, List[Repo]] =
      List(
        ("monix", "monix"),
        ("typelevel", "cats"),
        ("typelevel", "cats-effect"),
      ).traverse(fetchRepo[F])

    val io = Fetch.runEnv[IO](fetch)

    val (env, result) = io.unsafeRunSync

    env.rounds.size shouldEqual 1
  }
}
