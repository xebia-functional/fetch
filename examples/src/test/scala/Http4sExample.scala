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

import scala.concurrent.duration._
import cats.data.NonEmptyList
import cats.effect._
import cats.instances.list._
import cats.syntax.all._
import io.circe._
import io.circe.generic.semiauto._
import org.http4s.circe._
import org.http4s.client.blaze._
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.{ExecutionContext}
import fetch._

class Http4sExample extends WordSpec with Matchers {
  implicit val executionContext = ExecutionContext.Implicits.global

  implicit val t: Timer[IO]         = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  // in this example we are fetching users and their posts via http using http4s
  // the demo api is https://jsonplaceholder.typicode.com/

  // the User and Post classes

  case class UserId(id: Int)
  case class PostId(id: Int)

  case class User(id: UserId, name: String, username: String, email: String)
  case class Post(id: PostId, userId: UserId, title: String, body: String)

  // some circe decoders

  implicit val userIdDecoder: Decoder[UserId] = Decoder[Int].map(UserId.apply)
  implicit val postIdDecoder: Decoder[PostId] = Decoder[Int].map(PostId.apply)
  implicit val userDecoder: Decoder[User]     = deriveDecoder
  implicit val postDecoder: Decoder[Post]     = deriveDecoder

  // http4s client which is used by the datasources

  val client = Http1Client[IO](
    BlazeClientConfig.defaultConfig.copy(
      responseHeaderTimeout = 30.seconds // high timeout because jsonplaceholder takes a while to respond
    ))

  // a DataSource that can fetch Users with their UserId.

  implicit val userDS = new DataSource[UserId, User] {
    override def name = "UserH4s"
    override def fetch(id: UserId): IO[Option[User]] = {
      val url = s"https://jsonplaceholder.typicode.com/users?id=${id.id}"
      client >>= ((c) => c.expect(url)(jsonOf[IO, List[User]]).map(_.headOption))
    }

    override def batch(ids: NonEmptyList[UserId])(
      implicit P: Parallel[IO, IO.Par]
    ): IO[Map[UserId, User]] = {
      val filterIds = ids.map("id=" + _.id).toList.mkString("&")
      val url       = s"https://jsonplaceholder.typicode.com/users?$filterIds"
      val io        = client >>= ((c) => c.expect(url)(jsonOf[IO, List[User]]))
      io.map(users => users.map(user => user.id -> user).toMap)
    }
  }

  // a datasource that can fetch all the Posts using a UserId

  implicit val postsForUserDS = new DataSource[UserId, List[Post]] {
    override def name = "PostH4s"
    override def fetch(id: UserId): IO[Option[List[Post]]] = {
      val url = s"https://jsonplaceholder.typicode.com/posts?userId=${id.id}"
      client >>= ((c) => c.expect(url)(jsonOf[IO, List[Post]]).map(Option.apply))
    }

    override def batch(ids: NonEmptyList[UserId])(
      implicit P: Parallel[IO, IO.Par]
    ): IO[Map[UserId, List[Post]]] = {
      val filterIds = ids.map("userId=" + _.id).toList.mkString("&")
      val url       = s"https://jsonplaceholder.typicode.com/posts?$filterIds"
      client >>= ((c) => c.expect(url)(jsonOf[IO, List[Post]]).map(_.groupBy(_.userId).toMap))
    }
  }

  // some helper methods to create Fetches

  def user(id: UserId): Fetch[User]               = Fetch(id, userDS)
  def postsForUser(id: UserId): Fetch[List[Post]] = Fetch(id, postsForUserDS)

  "We can fetch one user" in {
    val fetch: Fetch[User]       = user(UserId(1))
    val io: IO[(FetchEnv, User)] = Fetch.runEnv(fetch)

    val (env, result) = io.unsafeRunSync

    println(result)
    env.rounds.size shouldEqual 1
  }

  "We can fetch multiple users in parallel" in {
    val fetch: Fetch[List[User]] = List(1, 2, 3).traverse(i => user(UserId(i)))
    val io                       = Fetch.runEnv(fetch)

    val (env, result) = io.unsafeRunSync

    result.foreach(println)
    env.rounds.size shouldEqual 1
  }

  "We can fetch multiple users with their posts" in {
    val fetch: Fetch[List[(User, List[Post])]] =
      for {
        users <- List(UserId(1), UserId(2)).traverse(user)
        usersWithPosts <- users.traverse { user =>
          postsForUser(user.id).map(posts => (user, posts))
        }
      } yield usersWithPosts
    val io = Fetch.runEnv(fetch)

    val (env, results) = io.unsafeRunSync

    results
      .map {
        case (user, posts) =>
          s"${user.username} has ${posts.size} posts"
      }
      .foreach(println)
    env.rounds.size shouldEqual 2
  }

}
