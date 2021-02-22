///*
// * Copyright 2016-2019 47 Degrees, LLC. <http://www.47deg.com>
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//import scala.concurrent.ExecutionContext
//import scala.concurrent.duration._
//
//import cats._
//import cats.data.NonEmptyList
//import cats.effect._
//import cats.instances.list._
//import cats.syntax.all._
//
//import io.circe._
//import io.circe.generic.semiauto._
//
//import org.http4s._
//import org.http4s.headers._
//import org.http4s.util.CaseInsensitiveString
//import org.http4s.circe._
//import org.http4s.client._
//import org.http4s.client.dsl._
//import org.http4s.client.blaze._
//import org.scalatest.matchers.should.Matchers
//import org.scalatest.wordspec.AnyWordSpec
//
//import fetch.{Data, DataSource, Fetch}
//
//class GithubExample extends AnyWordSpec with Matchers {
//  implicit val executionContext = ExecutionContext.Implicits.global
//
//  val ACCESS_TOKEN: String = sys.env("GITHUB_TOKEN")
//
//  implicit val t: Timer[IO]         = IO.timer(executionContext)
//  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)
//
//  // http4s client which is used by the datasources
//
//  def client[F[_]: ConcurrentEffect] =
//    BlazeClientBuilder[F](executionContext).resource
//
//  // -- repos
//
//  type Org = String
//  case class Repo(
//      name: String,
//      fork: Boolean,
//      forks_count: Int,
//      stargazers_count: Int,
//      watchers_count: Int,
//      languages_url: String,
//      contributors_url: String
//  )
//
//  object Repos extends Data[(String, String), Repo] {
//    def name = "Repositories"
//
//    implicit val repoD: Decoder[Repo] = deriveDecoder
//
//    def source[F[_]: ConcurrentEffect]: DataSource[F, (String, String), Repo] = {
//      implicit val repoED: EntityDecoder[F, Repo]        = jsonOf
//      implicit val reposED: EntityDecoder[F, List[Repo]] = jsonOf
//
//      new DataSource[F, (String, String), Repo] {
//
//        def CF = ConcurrentEffect[F]
//
//        def data = Repos
//
//        def fetch(id: (String, String)): F[Option[Repo]] = {
//          client[F].use { (c) =>
//            val (owner, repo) = id
//            val url           = GITHUB / "repos" / owner / repo +? ("access_token", ACCESS_TOKEN)
//            val req           = Request[F](Method.GET, url)
//            for {
//              result <- c
//                .run(req)
//                .use[F, Repo] {
//                  case Status.Ok(res) =>
//                    res.as[Repo]
//                  case res =>
//                    CF.raiseError(new Exception(res.body.toString))
//                }
//            } yield Option(result)
//          }
//        }
//      }
//    }
//  }
//
//  def fetchRepo[F[_]: ConcurrentEffect](r: (String, String)): Fetch[F, Repo] =
//    Fetch(r, Repos.source)
//
//  object OrgRepos extends Data[Org, List[Repo]] {
//    def name = "Org repositories"
//
//    implicit val repoD: Decoder[Repo] = deriveDecoder
//
//    def source[F[_]: ConcurrentEffect]: DataSource[F, Org, List[Repo]] =
//      new DataSource[F, Org, List[Repo]] {
//        implicit val repoED: EntityDecoder[F, Repo]        = jsonOf
//        implicit val reposED: EntityDecoder[F, List[Repo]] = jsonOf
//
//        def CF = Concurrent[F]
//
//        def data = OrgRepos
//
//        def fetch(org: Org): F[Option[List[Repo]]] = {
//          client[F].use { (c) =>
//            val url =
//              GITHUB / "orgs" / org / "repos" +? ("access_token", ACCESS_TOKEN) +? ("type", "public") +? ("per_page", 100)
//            val req = Request[F](Method.GET, url)
//            fetchCollectionRecursively[F, Repo](c, req).map(Option(_))
//          }
//        }
//      }
//  }
//
//  def orgRepos[F[_]: ConcurrentEffect](org: Org): Fetch[F, List[Repo]] =
//    Fetch(org, OrgRepos.source)
//
//  // -- languages
//
//  type Language = String
//
//  object Languages extends Data[Repo, List[Language]] {
//    def name = "Languages"
//
//    def source[F[_]: ConcurrentEffect]: DataSource[F, Repo, List[Language]] =
//      new DataSource[F, Repo, List[Language]] {
//        implicit val langD: Decoder[List[Language]] = Decoder[JsonObject].map(
//          _.toList.map(_._1)
//        )
//        implicit val langED: EntityDecoder[F, List[Language]] = jsonOf
//
//        def CF = ConcurrentEffect[F]
//
//        def data = Languages
//
//        def fetch(repo: Repo): F[Option[List[Language]]] = {
//          client[F].use { (c) =>
//            val url = Uri.unsafeFromString(repo.languages_url) +? ("access_token", ACCESS_TOKEN)
//            val req = Request[F](Method.GET, url)
//            fetchCollectionRecursively[F, Language](c, req).map(Option(_))
//          }
//        }
//      }
//  }
//
//  def repoLanguages[F[_]: ConcurrentEffect](repo: Repo): Fetch[F, List[Language]] =
//    Fetch(repo, Languages.source)
//
//  // -- contributors
//
//  case class Contributor(login: String, contributions: Int)
//
//  object Contributors extends Data[Repo, List[Contributor]] {
//    def name = "Contributors"
//
//    def source[F[_]: ConcurrentEffect]: DataSource[F, Repo, List[Contributor]] =
//      new DataSource[F, Repo, List[Contributor]] {
//        implicit val contribD: Decoder[Contributor]                 = deriveDecoder
//        implicit val contribE: EntityDecoder[F, Contributor]        = jsonOf
//        implicit val contribED: EntityDecoder[F, List[Contributor]] = jsonOf
//
//        def CF = ConcurrentEffect[F]
//
//        def data = Contributors
//
//        def fetch(repo: Repo): F[Option[List[Contributor]]] = {
//          client[F].use { (c) =>
//            val url = Uri
//              .unsafeFromString(
//                repo.contributors_url
//              ) +? ("access_token", ACCESS_TOKEN) +? ("type", "public") +? ("per_page", 100)
//            val req = Request[F](Method.GET, url)
//            fetchCollectionRecursively[F, Contributor](c, req).map(Option(_))
//          }
//        }
//      }
//  }
//
//  def repoContributors[F[_]: ConcurrentEffect](repo: Repo): Fetch[F, List[Contributor]] =
//    Fetch(repo, Contributors.source)
//
//  case class Project(repo: Repo, contributors: List[Contributor], languages: List[Language])
//
//  def fetchProject[F[_]: ConcurrentEffect](repo: Repo): Fetch[F, Project] =
//    (repoContributors(repo), repoLanguages(repo)).mapN({ case (contribs, langs) =>
//      Project(repo = repo, contributors = contribs, languages = langs)
//    })
//
//  def fetchOrg[F[_]: ConcurrentEffect](org: String) =
//    for {
//      repos    <- orgRepos(org)
//      projects <- repos.traverse(fetchProject[F])
//    } yield projects
//
//  def fetchOrgStars[F[_]: ConcurrentEffect](org: String): Fetch[F, Int] =
//    fetchOrg(org).map(projects => projects.map(_.repo.stargazers_count).sum)
//
//  def fetchOrgContributors[F[_]: ConcurrentEffect](org: String): Fetch[F, Int] =
//    fetchOrg(org).map(projects => projects.map(_.contributors.toSet).fold(Set())(_ ++ _).size)
//
//  def fetchOrgLanguages[F[_]: ConcurrentEffect](org: String): Fetch[F, Int] =
//    fetchOrg(org).map(projects => projects.map(_.languages.toSet).fold(Set())(_ ++ _).size)
//
//  "We can fetch org repos" in {
//    val io = Fetch.runLog[IO](fetchOrg[IO]("47deg"))
//
//    val (log, result) = io.unsafeRunSync
//
//    log.rounds.size shouldEqual 2
//  }
//
//  // Github HTTP api
//
//  val GITHUB: Uri = Uri.unsafeFromString("https://api.github.com")
//
//  private def fetchCollectionRecursively[F[_], A](c: Client[F], req: Request[F])(implicit
//      CF: BracketThrow[F],
//      E: EntityDecoder[F, List[A]]
//  ): F[List[A]] = {
//    val REL_NEXT = "rel=\"next\"".r
//
//    def hasNext(res: Response[F]): Boolean =
//      res.headers
//        .get(CaseInsensitiveString("Link"))
//        .fold(false)({ h => REL_NEXT.findFirstIn(h.value).isDefined })
//
//    def getNextLink(raw: String): F[String] = {
//      REL_NEXT
//        .findFirstMatchIn(raw)
//        .fold(
//          CF.raiseError[String](new Exception("Couldn't find next link"))
//        ) { m =>
//          CF.pure(m.before.toString.split(",").last.trim.dropWhile(_ == '<').takeWhile(_ != '>'))
//        }
//    }
//
//    def getNext(res: Response[F]): F[Uri] =
//      res.headers
//        .get(CaseInsensitiveString("Link"))
//        .fold(CF.raiseError[Uri](new Exception("next not found")))(raw =>
//          getNextLink(raw.value).map(Uri.unsafeFromString(_))
//        )
//
//    for {
//      result <- c.run(req).use[F, List[A]] {
//        case Status.Ok(res) =>
//          if (hasNext(res)) {
//            for {
//              repos <- res.as[List[A]]
//              nxt   <- getNext(res)
//              newReq = req.withUri(nxt)
//              moreRepos <- fetchCollectionRecursively(c, newReq)
//            } yield repos ++ moreRepos
//          } else
//            res.as[List[A]]
//        case res =>
//          CF.raiseError(new Exception(s"Couldn't complete request, returned status: ${res.status}"))
//      }
//    } yield result
//  }
//
//}
