/*
 * Copyright 2016-2022 47 Degrees Open Source <https://www.47deg.com>
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

import cats.data.NonEmptyList
import cats.effect._
import cats.instances.list._
import cats.syntax.all._

import io.circe._
import io.circe.generic.semiauto._

import org.http4s.client.Client
import org.http4s.circe._
import org.http4s.blaze.client._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent._

import fetch._
import fetch.syntax._

object HttpExample {
  case class UserId(id: Int)
  case class PostId(id: Int)

  case class User(id: UserId, name: String, username: String, email: String)
  case class Post(id: PostId, userId: UserId, title: String, body: String)

  object Http {
    val executionContext =
      ExecutionContext.fromExecutor(new ScheduledThreadPoolExecutor(2))

    def client[F[_]: Async]: Resource[F, Client[F]] =
      BlazeClientBuilder[F](executionContext).resource

    implicit val userIdDecoder: Decoder[UserId] = Decoder[Int].map(UserId.apply)
    implicit val postIdDecoder: Decoder[PostId] = Decoder[Int].map(PostId.apply)
    implicit val userDecoder: Decoder[User]     = deriveDecoder
    implicit val postDecoder: Decoder[Post]     = deriveDecoder
  }

  object Users extends Data[UserId, User] {
    import Http._

    def name = "Users"

    def http[F[_]: Async]: DataSource[F, UserId, User] =
      new DataSource[F, UserId, User] {
        def data = Users

        override def CF = Async[F]

        override def fetch(id: UserId): F[Option[User]] = {
          val url = s"https://jsonplaceholder.typicode.com/users?id=${id.id}"
          client[F].use((c) => c.expect(url)(jsonOf[F, List[User]])).map(_.headOption)
        }

        override def batch(ids: NonEmptyList[UserId]): F[Map[UserId, User]] = {
          val filterIds = ids.map("id=" + _.id).toList.mkString("&")
          val url       = s"https://jsonplaceholder.typicode.com/users?$filterIds"
          val io        = client[F].use((c) => c.expect(url)(jsonOf[F, List[User]]))
          io.map(users => users.map(user => user.id -> user).toMap)
        }
      }
  }

  object Posts extends Data[UserId, List[Post]] {
    import Http._

    def name = "Posts"

    def http[F[_]: Async]: DataSource[F, UserId, List[Post]] =
      new DataSource[F, UserId, List[Post]] {
        def data = Posts

        override def CF = Async[F]

        override def fetch(id: UserId): F[Option[List[Post]]] = {
          val url = s"https://jsonplaceholder.typicode.com/posts?userId=${id.id}"
          client[F].use((c) => c.expect(url)(jsonOf[F, List[Post]])).map(Option.apply)
        }

        override def batch(ids: NonEmptyList[UserId]): F[Map[UserId, List[Post]]] = {
          val filterIds = ids.map("userId=" + _.id).toList.mkString("&")
          val url       = s"https://jsonplaceholder.typicode.com/posts?$filterIds"
          client[F].use((c) => c.expect(url)(jsonOf[F, List[Post]])).map(_.groupBy(_.userId).toMap)
        }
      }
  }

  def fetchUserById[F[_]: Async](id: UserId): Fetch[F, User] =
    Fetch(id, Users.http)

  def fetchPostsForUser[F[_]: Async](id: UserId): Fetch[F, List[Post]] =
    Fetch(id, Posts.http)

  def fetchUser[F[_]: Async](id: Int): Fetch[F, User] =
    fetchUserById(UserId(id))

  def fetchManyUsers[F[_]: Async](ids: List[Int]): Fetch[F, List[User]] =
    ids.map(i => fetchUserById(UserId(i))).batchAll

  def fetchPosts[F[_]: Async](user: User): Fetch[F, (User, List[Post])] =
    fetchPostsForUser(user.id).map(posts => (user, posts))
}

class Http4sExample extends AnyWordSpec with Matchers {
  import HttpExample._

  // runtime
  val executionContext                     = ExecutionContext.global
  implicit val ioRuntime: unsafe.IORuntime = unsafe.IORuntime.global

  "We can fetch one user" in {
    val io: IO[(Log, User)] = Fetch.runLog[IO](fetchUser(1))

    val (log, result) = io.unsafeRunSync()

    println(result)
    log.rounds.size shouldEqual 1
  }

  "We can fetch multiple users in parallel" in {
    val io = Fetch.runLog[IO](fetchManyUsers(List(1, 2, 3)))

    val (log, result) = io.unsafeRunSync()

    result.foreach(println)
    log.rounds.size shouldEqual 1
  }

  "We can fetch multiple users with their posts" in {
    def fetch[F[_]: Async]: Fetch[F, List[(User, List[Post])]] =
      for {
        users          <- fetchManyUsers(List(1, 2))
        usersWithPosts <- users.map(fetchPosts[F]).batchAll
      } yield usersWithPosts

    val io = Fetch.runLog[IO](fetch)

    val (log, results) = io.unsafeRunSync()

    results
      .map { case (user, posts) =>
        s"${user.username} has ${posts.size} posts"
      }
      .foreach(println)
    log.rounds.size shouldEqual 2
  }

}
