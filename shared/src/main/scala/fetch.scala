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

package fetch

import scala.collection.immutable.Map

import cats.{Functor, Applicative, Monad, Eval}
import cats.data.{NonEmptyList, StateT}
import cats.free.Free
import cats.instances.list._
import cats.effect._
import cats.effect.concurrent.{Ref, Deferred}
import cats.syntax.all._


object `package` {
  type DataSourceName     = String
  type DataSourceIdentity = (DataSourceName, Any)

  // Fetch request
  sealed trait FetchRequest extends Product with Serializable

  // Fetch queries
  sealed trait FetchQuery[I, A] extends FetchRequest {
    def dataSource: DataSource[I, A]
    def identities: NonEmptyList[I]
  }
  case class FetchOne[I, A](id: I, dataSource: DataSource[I, A]) extends FetchQuery[I, A] {
    def identities: NonEmptyList[I] = NonEmptyList(id.asInstanceOf[I], List.empty[I])
  }
  case class FetchMany[I, A](ids: NonEmptyList[I], dataSource: DataSource[I, A]) extends FetchQuery[I, A] {
    def identities: NonEmptyList[I] = ids
  }

  case class ConcurrentRequest(queries: NonEmptyList[FetchQuery[Any, Any]]) extends FetchRequest

  // Fetch result states
  sealed trait FetchStatus[A]
  case class FetchDone[A](result: A) extends FetchStatus[A]

  // In-progress request
  case class BlockedRequest(request: FetchRequest, result: Deferred[IO, FetchStatus[Any]])

  // `Fetch` result data type
  sealed trait FetchResult[A]
  case class Done[A](x: A) extends FetchResult[A]
  case class Blocked[A](rs: List[BlockedRequest], cont: Fetch[A]) extends FetchResult[A]
  case class Errored[A](e: FetchError) extends FetchResult[A]

  //
  sealed trait Fetch[A] {
    def run: IO[FetchResult[A]]
  }
  case class Unfetch[A](run: IO[FetchResult[A]]) extends Fetch[A]

  sealed trait FetchError
  case class MissingIdentity() extends FetchError


  implicit val fetchApplicative: Monad[Fetch] = new Monad[Fetch] {
    def pure[A](a: A): Fetch[A] =
      Unfetch(
        IO.pure(Done(a))
      )

    override def map[A, B](fa: Fetch[A])(f: A => B): Fetch[B] =
      Unfetch(for {
        fetch <- fa.run
        result = fetch match {
          case Done(v) => Done(f(v))
          case Blocked(br, cont) =>
            Blocked(br, map(cont)(f))
        }
      } yield result.asInstanceOf[FetchResult[B]])

    override def product[A, B](fa: Fetch[A], fb: Fetch[B]): Fetch[(A, B)] =
      Unfetch(for {
        a <- fa.run
        b <- fb.run
        result = (a, b) match {
          case (Done(a), Done(b)) => Done((a, b))
          case (Done(a), Blocked(br, c)) => Blocked(br, product(fa, c))
          case (Blocked(br, c), Done(b)) => Blocked(br, product(c, fb))
          case (Blocked(br, c), Blocked(br2, c2)) =>
            Blocked(br ++ br2, product(c, c2))
        }
      } yield result)

    // override def map2[A, B, Z](fa: Fetch[A], fb: Fetch[B])(f: (A, B) => Z): Fetch[Z] = {
    //   println("MAP2!")
    //   ???
    // }

    // override def tuple2[A, B](fa: Fetch[A], fb: Fetch[B]): Fetch[(A, B)] = {
    //   println("TUPLE2!")
    //   ???
    // }

    override def ap[A, B](ff: Fetch[A => B])(fa: Fetch[A]): Fetch[B] =
      ???

    def tailRecM[A, B](a: A)(f: A => Fetch[Either[A, B]]): Fetch[B] =
      ???

    def flatMap[A, B](fa: Fetch[A])(f: A => Fetch[B]): Fetch[B] =
      Unfetch(for {
        fetch <- fa.run
        result: Fetch[B] = fetch match {
          case Done(v) => f(v)
          case Blocked(br, cont : Fetch[A]) =>
            Unfetch(
              IO.pure(
                Blocked(br, flatMap(cont)(f).asInstanceOf[Fetch[B]])
              )
            )
        }
        value <- result.run
      } yield value)
  }

  object Fetch {
    /**
     * Lift a plain value to the Fetch monad.
     */
    def pure[A](a: A): Fetch[A] =
      Unfetch(
        IO.pure(Done(a))
      )

    def apply[I, A](id: I)(
      implicit ds: DataSource[I, A],
      C: Concurrent[IO]
    ): Fetch[A] = {
      val request = FetchOne(id, ds)
      Unfetch(
        for {
          df <- Deferred[IO, FetchStatus[Any]]
          blocked = BlockedRequest(request, df)
        } yield Blocked(List(blocked), Unfetch(
          for {
            result <- df.get
            value = result match {
              case FetchDone(a) => Done(a).asInstanceOf[FetchResult[A]]
            }
          } yield value
        ))
      )
    }

    // private[fetch] class FetchRunnerEnv[M[_]](val dummy: Boolean = true) extends AnyVal {
    //   def apply[A](
    //     fa: Fetch[A]
    //   )(
    //     implicit E: Effect[M],
    //     MM: Monad[M]
    //   ): M[Env] =
    //     for {
    //       env <- Ref.of[M, Env](FetchEnv())
    //       result <- runFetchWithEnv[A, M](fa, env)
    //       e <- env.get
    //     } yield e
    // }

    // private def runFetchWithEnv[A, M[_] : Effect](fa: Fetch[A], env: Ref[M, Env]): M[(Env, A)] = fa match {
    //   case Done(a) => env.get.flatMap((e) => Effect[M].pure((e, a)))
    // }

    private[fetch] class FetchRunner[M[_]](val dummy: Boolean = true) extends AnyVal {
      def apply[A](
          fa: Fetch[A]
      )(
        implicit C: Concurrent[IO]
      ): IO[A] = for {
        result <- fa.run
        value <- result match {
          case Done(a) => Concurrent[IO].pure(a) // todo: handle errored
        }
      } yield value
    }

    private def performFetch[A](rs: List[BlockedRequest])(
      implicit C: ConcurrentEffect[IO]
    ): IO[Unit] = {
      rs.traverse_((r) => {
        r.request match {
          case FetchOne(id, ds) =>
            ds.fetchOne[IO](id).flatMap(_ match {
              case None => IO.raiseError(new Exception("TODO"))
              case Some(a) => r.result.complete(FetchDone[Any](a))
            })
          case FetchMany(ids, ds) => IO.raiseError(new Exception("TODO"))
        }
      })
    }

    /**
     * Run a `Fetch`, the result in the monad `M`.
     */
    def run[A](
      fa: Fetch[A]
    )(
      implicit C: ConcurrentEffect[IO]
    ): IO[A] = for {
      result <- fa.run

      value <- result.asInstanceOf[FetchResult[A]] match {
        case Done(a) => Concurrent[IO].pure(a)
        case Blocked(rs, cont) => for {
          _ <- performFetch(rs)
          result <- run(cont)
        } yield result
      }
    } yield value

  }

  private[fetch] implicit class DataSourceCast[A, B](private val ds: DataSource[A, B]) extends AnyVal {
    def castDS[C, D]: DataSource[C, D] = ds.asInstanceOf[DataSource[C, D]]
  }

}
