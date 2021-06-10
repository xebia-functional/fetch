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

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.Monad
import cats.data.NonEmptyList
import cats.effect._
import cats.instances.list._
import cats.syntax.all._

import io.circe._
import io.circe.generic.semiauto._

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io._
import java.nio.charset.Charset
import redis.clients.jedis._
import scala.util.Try

import fetch._

object DataSources {
  object Numbers extends Data[Int, Int] {
    def name = "Numbers"

    def source[F[_]: Async]: DataSource[F, Int, Int] =
      new DataSource[F, Int, Int] {
        def data = Numbers

        override def CF = Async[F]

        override def fetch(id: Int): F[Option[Int]] =
          CF.pure(Option(id))
      }
  }

  def fetchNumber[F[_]: Async](id: Int): Fetch[F, Int] =
    Fetch(id, Numbers.source)

  def fetch[F[_]: Async]: Fetch[F, HttpExample.User] =
    for {
      _ <- HttpExample.fetchUser(1)
      n <- fetchNumber(1)
      _ <- HttpExample.fetchUser(n)
      _ <- fetchNumber(n)
      u <- HttpExample.fetchUser(n)
    } yield u

  def fetchMulti[F[_]: Async]: Fetch[F, List[HttpExample.User]] =
    List(4, 5, 6).traverse(HttpExample.fetchUser[F](_))
}

object Binary {
  type ByteArray = Array[Byte]

  def byteOutputStream[F[_]](implicit S: Sync[F]): Resource[F, ByteArrayOutputStream] =
    Resource.fromAutoCloseable(S.delay(new ByteArrayOutputStream()))

  def byteInputStream[F[_]](
      bin: ByteArray
  )(implicit S: Sync[F]): Resource[F, ByteArrayInputStream] =
    Resource.fromAutoCloseable(S.delay(new ByteArrayInputStream(bin)))

  def outputStream[F[_]](
      b: ByteArrayOutputStream
  )(implicit S: Sync[F]): Resource[F, ObjectOutputStream] =
    Resource.fromAutoCloseable(S.delay(new ObjectOutputStream(b)))

  def inputStream[F[_]](
      b: ByteArrayInputStream
  )(implicit S: Sync[F]): Resource[F, ObjectInputStream] =
    Resource.fromAutoCloseable(S.delay(new ObjectInputStream(b)))

  def fromString(s: String): Array[Byte] =
    s.getBytes(Charset.forName("UTF-8"))

  def serialize[F[_], A](obj: A)(implicit
      S: Sync[F]
  ): F[ByteArray] = {
    byteOutputStream
      .mproduct(outputStream(_))
      .use({ case (byte, out) =>
        S.delay {
          out.writeObject(obj)
          out.flush()
          byte.toByteArray
        }
      })
  }

  def deserialize[F[_], A](bin: ByteArray)(implicit
      S: Sync[F]
  ): F[Option[A]] = {
    byteInputStream(bin)
      .mproduct(inputStream(_))
      .use({ case (byte, in) =>
        S.delay {
          val obj = in.readObject()
          Try(obj.asInstanceOf[A]).toOption
        }
      })
  }
}

case class RedisCache[F[_]: Sync](host: String) extends DataCache[F] {
  private val pool = new JedisPool(host)

  def connection: Resource[F, Jedis] =
    Resource.fromAutoCloseable(Sync[F].delay(pool.getResource))

  private def get(i: Array[Byte]): F[Option[Array[Byte]]] =
    connection.use(c => Sync[F].delay(Option(c.get(i))))

  private def set(i: Array[Byte], v: Array[Byte]): F[Unit] =
    connection.use(c => Sync[F].delay(c.set(i, v)).void)

  private def bulkSet(ivs: List[(Array[Byte], Array[Byte])]): F[Unit] =
    connection.use(c =>
      Sync[F].delay({
        val pipe = c.pipelined
        ivs.foreach(i => pipe.set(i._1, i._2))
        pipe.sync
      })
    )

  private def cacheId[I, A](i: I, data: Data[I, A]): Array[Byte] =
    Binary.fromString(s"${data.identity} ${i}")

  override def lookup[I, A](i: I, data: Data[I, A]): F[Option[A]] =
    get(cacheId(i, data)) >>= {
      case None    => Sync[F].pure(None)
      case Some(r) => Binary.deserialize[F, A](r)
    }

  override def insert[I, A](i: I, v: A, data: Data[I, A]): F[DataCache[F]] =
    for {
      s <- Binary.serialize(v)
      _ <- set(cacheId(i, data), s)
    } yield this

  override def bulkInsert[I, A](vs: List[(I, A)], data: Data[I, A])(implicit
      M: Monad[F]
  ): F[DataCache[F]] =
    for {
      bin <- vs.traverse({ case (id, v) =>
        Binary.serialize(v).tupleRight(cacheId(id, data))
      })
      _ <- Sync[F].delay(bulkSet(bin))
    } yield this

}

class JedisExample extends AnyWordSpec with Matchers {
  import DataSources._

  // runtime
  val executionContext                     = ExecutionContext.Implicits.global
  implicit val ioRuntime: unsafe.IORuntime = unsafe.IORuntime.global

  "We can use a Redis cache" ignore {
    val cache = RedisCache[IO]("localhost")

    val io: IO[(Log, HttpExample.User)] = Fetch.runLog[IO](fetch, cache)

    val (log, result) = io.unsafeRunSync()

    println(result)
    log.rounds.size shouldEqual 2

    val io2: IO[(Log, HttpExample.User)] = Fetch.runLog[IO](fetch, cache)

    val (log2, result2) = io2.unsafeRunSync()

    println(result2)
    log2.rounds.size shouldEqual 0
  }

  "We can bulk insert in a Redis cache" ignore {
    val cache = RedisCache[IO]("localhost")

    val io: IO[(Log, List[HttpExample.User])] = Fetch.runLog[IO](fetchMulti, cache)

    val (log, result) = io.unsafeRunSync()

    println(result)
    log.rounds.size shouldEqual 1

    val io2: IO[(Log, List[HttpExample.User])] = Fetch.runLog[IO](fetchMulti, cache)

    val (log2, result2) = io2.unsafeRunSync()

    println(result2)
    log2.rounds.size shouldEqual 0
  }
}
