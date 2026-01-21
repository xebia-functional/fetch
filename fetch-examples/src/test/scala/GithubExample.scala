/*
 * Copyright 2016-2023 47 Degrees Open Source <https://www.47deg.com>
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

import cats.effect._
import cats.syntax.all._
import fetch.{Data, DataSource, Fetch}
import fetch.syntax._
import io.circe._
import io.circe.generic.semiauto._
import org.http4s._
import org.http4s.blaze.client._
import org.http4s.circe._
import org.http4s.client._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.io.{BufferedReader, BufferedWriter, File, FileReader, FileWriter}

class GithubExample extends AnyWordSpec with Matchers {
  implicit val ioRuntime: unsafe.IORuntime = unsafe.IORuntime.global

  val ACCESS_TOKEN: String = sys.env("GITHUB_TOKEN")
  val CACHE_FOLDER: String = sys.env.getOrElse("GITHUB_RESPONSES", "target/github-responses")

  def cleanUpCache: IO[Unit] = IO {
    val folder = new File(CACHE_FOLDER)
    if (folder.isDirectory) folder.listFiles().toList else Nil
  }.flatMap(_.traverse_(f => IO.blocking(f.delete()).attempt.void))

  // http4s client which is used by the datasources

  object FileMiddleware {

    def file(uri: Uri): File =
      new File(CACHE_FOLDER + "/" + uri.path.toString.replaceAll("/", "_") + ".json")

    def fileWriter[F[_]: Async](file: File): Resource[F, BufferedWriter] =
      Resource.fromAutoCloseable(Async[F].blocking(new BufferedWriter(new FileWriter(file))))

    def fileReader[F[_]: Async](file: File): Resource[F, BufferedReader] =
      Resource.fromAutoCloseable(Async[F].blocking(new BufferedReader(new FileReader(file))))

    def apply[F[_]: Async]: Client[F] => Client[F] = { client =>
      val logger: Logger[F] = Slf4jLogger.getLogger[F]
      Client[F] { request =>
        val cacheFile = file(request.uri)
        for {
          _ <- Resource.eval(Async[F].catchNonFatal(cacheFile.getParentFile.mkdirs()))
          json <-
            if (cacheFile.exists()) {
              fileReader(cacheFile)
                .evalMap(br =>
                  Async[F].delay(
                    Stream.continually(br.readLine()).takeWhile(_ != null).mkString("\n")
                  )
                )
                .evalMap(s => Async[F].fromEither(io.circe.parser.parse(s)))
                .evalTap(_ => logger.info(s"Got JSON for uri ${request.uri} from cache"))
            } else {
              client
                .run(request)
                .evalMap(_.as[Json])
                .flatTap(json =>
                  fileWriter(cacheFile).evalMap(fw => Async[F].blocking(fw.write(json.spaces4)))
                )
                .evalTap(_ => logger.info(s"Request to ${request.uri}"))
            }
        } yield Response[F](Status.Ok).withEntity[Json](json)
      }
    }
  }

  def client[F[_]: Async]: Resource[F, Client[F]] =
    BlazeClientBuilder[F].resource.map(FileMiddleware.apply)

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

  object OrgRepos extends Data[Org, List[Repo]] {
    def name = "Org repositories"

    implicit val repoD: Decoder[Repo] = deriveDecoder

    def source[F[_]: Async]: DataSource[F, Org, List[Repo]] =
      new DataSource[F, Org, List[Repo]] {
        implicit val reposED: EntityDecoder[F, List[Repo]] = jsonOf

        def CF: Concurrent[F] = Concurrent[F]

        def data: Data[Org, List[Repo]] = OrgRepos

        def fetch(org: Org): F[Option[List[Repo]]] = {
          client[F].use { c =>
            val url =
              GITHUB / "orgs" / org / "repos" +? ("type", "public") +? ("per_page", 100)
            val req = Request[F](Method.GET, url).withHeaders(
              Header.Raw(CIString("Authorization"), s"token $ACCESS_TOKEN")
            )
            fetchCollectionRecursively[F, Repo](c, req).map(Option(_))
          }
        }
      }
  }

  def orgRepos[F[_]: Async](org: Org): Fetch[F, List[Repo]] =
    Fetch(org, OrgRepos.source)

  // -- languages

  type Language = String

  object Languages extends Data[Repo, List[Language]] {
    def name = "Languages"

    def source[F[_]: Async]: DataSource[F, Repo, List[Language]] =
      new DataSource[F, Repo, List[Language]] {
        implicit val langD: Decoder[List[Language]] = Decoder[JsonObject].map(
          _.toList.map(_._1)
        )
        implicit val langED: EntityDecoder[F, List[Language]] = jsonOf

        def CF: Concurrent[F] = Concurrent[F]

        def data: Data[Repo, List[Language]] = Languages

        def fetch(repo: Repo): F[Option[List[Language]]] = {
          client[F].use { c =>
            val url = Uri.unsafeFromString(repo.languages_url)
            val req = Request[F](Method.GET, url).withHeaders(
              Header.Raw(CIString("Authorization"), s"token $ACCESS_TOKEN")
            )
            fetchCollectionRecursively[F, Language](c, req).map(Option(_))
          }
        }
      }
  }

  def repoLanguages[F[_]: Async](repo: Repo): Fetch[F, List[Language]] =
    Fetch(repo, Languages.source)

  // -- contributors

  case class Contributor(login: String, contributions: Int)

  object Contributors extends Data[Repo, List[Contributor]] {
    def name = "Contributors"

    def source[F[_]: Async]: DataSource[F, Repo, List[Contributor]] =
      new DataSource[F, Repo, List[Contributor]] {
        implicit val contribD: Decoder[Contributor]                 = deriveDecoder
        implicit val contribED: EntityDecoder[F, List[Contributor]] = jsonOf

        def CF: Concurrent[F] = Concurrent[F]

        def data: Data[Repo, List[Contributor]] = Contributors

        def fetch(repo: Repo): F[Option[List[Contributor]]] = {
          client[F].use { c =>
            val url = Uri
              .unsafeFromString(
                repo.contributors_url
              ) +? ("type", "public") +? ("per_page", 100)
            val req =
              Request[F](Method.GET, url).withHeaders(
                Header.Raw(CIString("Authorization"), s"token $ACCESS_TOKEN")
              )
            fetchCollectionRecursively[F, Contributor](c, req).map(Option(_))
          }
        }
      }
  }

  def repoContributors[F[_]: Async](repo: Repo): Fetch[F, List[Contributor]] =
    Fetch(repo, Contributors.source)

  case class Project(repo: Repo, contributors: List[Contributor], languages: List[Language])

  def fetchProject[F[_]: Async](repo: Repo): Fetch[F, Project] =
    (repoContributors(repo), repoLanguages(repo)).mapN { case (contribs, langs) =>
      Project(repo = repo, contributors = contribs, languages = langs)
    }

  def fetchOrg[F[_]: Async](org: String): Fetch[F, List[Project]] =
    for {
      repos <- orgRepos(org)
      projects <- repos
        .filter(r => r.name == "fetch" || r.name == "github4s" || r.name == "memeid")
        .batchAllWith(fetchProject[F])
    } yield projects

  def fetchOrgStars[F[_]: Async](org: String): Fetch[F, Int] =
    fetchOrg(org).map(projects => projects.map(_.repo.stargazers_count).sum)

  def fetchOrgContributors[F[_]: Async](org: String): Fetch[F, Int] =
    fetchOrg(org).map(projects => projects.map(_.contributors.toSet).fold(Set())(_ ++ _).size)

  def fetchOrgLanguages[F[_]: Async](org: String): Fetch[F, Int] =
    fetchOrg(org).map(projects => projects.map(_.languages.toSet).fold(Set())(_ ++ _).size)

  "We can fetch org repos" ignore {
    val io = Fetch.runLog[IO](fetchOrg[IO]("47degrees"))

    val (log, _) = io.onError { case _ => cleanUpCache }.unsafeRunSync()

    log.rounds.size shouldEqual 2
  }

  // Github HTTP api

  val GITHUB: Uri = Uri.unsafeFromString("https://api.github.com")

  private def fetchCollectionRecursively[F[_], A](c: Client[F], req: Request[F])(implicit
      F: Async[F],
      E: EntityDecoder[F, List[A]]
  ): F[List[A]] = {
    val REL_NEXT = "rel=\"next\"".r

    def hasNext(res: Response[F]): Boolean =
      res.headers
        .get(CIString("Link"))
        .fold(false) { hs =>
          hs.exists(h => REL_NEXT.findFirstIn(h.value).isDefined)
        }

    def getNextLink(raw: String): F[String] = {
      REL_NEXT
        .findFirstMatchIn(raw)
        .liftTo[F](new Exception("Couldn't find next link"))
        .map { m =>
          m.before.toString.split(",").last.trim.dropWhile(_ == '<').takeWhile(_ != '>')
        }
    }

    def getNext(res: Response[F]): F[Uri] =
      res.headers
        .get(CIString("Link"))
        .fold(F.raiseError[Uri](new Exception("next not found"))) { hs =>
          getNextLink(hs.head.value).map(Uri.unsafeFromString)
        }

    c.run(req).use[List[A]] {
      case Status.Ok(res) =>
        if (hasNext(res)) {
          for {
            repos <- res.as[List[A]]
            nxt   <- getNext(res)
            newReq = req.withUri(nxt)
            moreRepos <- fetchCollectionRecursively(c, newReq)
          } yield repos ++ moreRepos
        } else
          res.as[List[A]]
      case res =>
        res.bodyText.compile.string.flatMap(respBody =>
          F.raiseError(
            new Exception(
              s"Couldn't complete request, returned status: ${res.status}: Body:\n$respBody"
            )
          )
        )
    }
  }

}
