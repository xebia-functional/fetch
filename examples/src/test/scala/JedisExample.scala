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

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect._
import cats.instances.list._
import cats.syntax.all._
import cats.temp.par._

import io.circe._
import io.circe.generic.semiauto._

import org.http4s.circe._
import org.http4s.client.blaze._
import org.scalatest.{Matchers, WordSpec}

import java.io._
import java.nio.charset.Charset
import redis.clients.jedis._

import fetch._

object DataSources {
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

  def client[F[_]: ConcurrentEffect] =
    Http1Client[F](
      BlazeClientConfig.defaultConfig.copy(
        responseHeaderTimeout = 30.seconds // high timeout because jsonplaceholder takes a while to respond
      ))

  // a DataSource that can fetch Users with their UserId.

  object Users extends DataSource[UserId, User] {
    override def name = "UserH4s"

    override def fetch[F[_]: ConcurrentEffect: Par](id: UserId): F[Option[User]] = {
      val url = s"https://jsonplaceholder.typicode.com/users?id=${id.id}"
      client[F] >>= ((c) => c.expect(url)(jsonOf[F, List[User]]).map(_.headOption))
    }

    override def batch[F[_]: ConcurrentEffect: Par](
        ids: NonEmptyList[UserId]
    ): F[Map[UserId, User]] = {
      val filterIds = ids.map("id=" + _.id).toList.mkString("&")
      val url       = s"https://jsonplaceholder.typicode.com/users?$filterIds"
      val io        = client[F] >>= ((c) => c.expect(url)(jsonOf[F, List[User]]))
      io.map(users => users.map(user => user.id -> user).toMap)
    }
  }

  // some helper methods to create Fetches

  def user[F[_]: ConcurrentEffect](id: UserId): Fetch[F, User] =
    Fetch(id, Users)

  object Numbers extends DataSource[Int, Int] {
    override def name = "Numbers"

    override def fetch[F[_]: ConcurrentEffect: Par](id: Int): F[Option[Int]] =
      Sync[F].pure(Option(id))
  }

  def number[F[_]: ConcurrentEffect](id: Int): Fetch[F, Int] =
    Fetch(id, Numbers)

  def fetch[F[_]: ConcurrentEffect]: Fetch[F, User] =
    for {
      _ <- user(UserId(1))
      n <- number(1)
      _ <- user(UserId(n))
      _ <- number(n)
      u <- user(UserId(n))
    } yield u
}

object Binary {
  def fromString(s: String): Array[Byte] =
    s.getBytes(Charset.forName("UTF-8"))

  def serialize[F[_]: Sync, A](v: A): F[Array[Byte]] = {
    val baos = new ByteArrayOutputStream()
    val oos  = new ObjectOutputStream(baos)
    Sync[F].bracket(Sync[F].pure(oos))({ in =>
      Sync[F].pure({
        in.writeObject(v)
        in.flush()
        baos.toByteArray
      })
    })({ in =>
      Sync[F].delay(in.close())
    })
  }

  def deserialize[F[_]: Sync, I, A](bs: Option[Array[Byte]]): F[Option[A]] =
    bs match {
      case None => Sync[F].pure(None)
      case Some(raw) => {
        val bais = new ByteArrayInputStream(raw)
        val oos  = new ObjectInputStream(bais)
        Sync[F].bracket(
          Sync[F].pure((bais, oos))
        )({
          case (b, o) =>
            Sync[F].pure({
              val res = o.readObject()
              Option(res.asInstanceOf[A])
            })
        })({
          case (b, o) =>
            Sync[F].delay({
              b.close()
              o.close()
            })
        })
      }
    }

}

class JedisExample extends WordSpec with Matchers {
  import DataSources._

  case class RedisCache[F[_]: Sync](host: String) extends DataSourceCache[F] {
    private val binaryClient = new BinaryJedis(host)

    def client: F[BinaryJedis] =
      Sync[F].pure(binaryClient)

    private def get(i: Array[Byte]): F[Option[Array[Byte]]] =
      Sync[F].bracket(client)({ c =>
        Sync[F].pure(Option(c.get(i)))
      })({ in =>
        Sync[F].pure(in.close())
      })

    private def set(i: Array[Byte], v: Array[Byte]): F[Unit] =
      Sync[F].bracket(client)({ c =>
        Sync[F].pure(c.set(i, v)).void
      })({ in =>
        Sync[F].pure(in.close())
      })

    private def identity[I, A](i: I, ds: DataSource[I, A]): Array[Byte] =
      Binary.fromString(s"${ds.name} ${i}")

    def lookup[I, A](i: I, ds: DataSource[I, A]): F[Option[A]] =
      get(identity(i, ds)) >>= { case (raw) => Binary.deserialize(raw) }

    def insert[I, A](i: I, v: A, ds: DataSource[I, A]): F[DataSourceCache[F]] =
      for {
        s <- Binary.serialize(v)
        _ <- set(identity(i, ds), s)
      } yield this
  }

  // runtime

  val executionContext              = ExecutionContext.Implicits.global
  implicit val t: Timer[IO]         = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  "We can use a Redis cache" in {
    val cache               = RedisCache[IO]("localhost")
    val io: IO[(Env, User)] = Fetch.runEnv[IO](fetch, cache)

    val (env, result) = io.unsafeRunSync

    println(result)
    env.rounds.size shouldEqual 2

    val io2: IO[(Env, User)] = Fetch.runEnv[IO](fetch, cache)
    val (env2, result2)      = io2.unsafeRunSync

    println(result2)
    env2.rounds.size shouldEqual 0
  }
}
