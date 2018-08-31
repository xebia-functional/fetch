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

import scala.concurrent.duration.MILLISECONDS

import cats._
import cats.data.{NonEmptyList, NonEmptySet, StateT}
import cats.instances.list._
import cats.effect._
import cats.effect.concurrent.{Ref, Deferred}
import cats.syntax.all._


object `package` {
  type DataSourceName     = String
  type DataSourceIdentity = (DataSourceName, Any)

  // Fetch queries
  sealed trait FetchRequest extends Product with Serializable

  sealed trait FetchQuery extends FetchRequest {
    def dataSource[I, A]: DataSource[I, A]
    def identities[I]: NonEmptyList[I]
  }
  case class FetchOne[I, A](id: I, ds: DataSource[I, A]) extends FetchQuery {
    override def identities[I]: NonEmptyList[I] = NonEmptyList(id.asInstanceOf[I], List.empty[I])
    override def dataSource[I, A]: DataSource[I, A] = ds.asInstanceOf[DataSource[I, A]]
  }
  case class FetchMany[I, A](ids: NonEmptyList[I], ds: DataSource[I, A]) extends FetchQuery {
    override def identities[I]: NonEmptyList[I] = ids.toNonEmptyList.asInstanceOf[NonEmptyList[I]]
    override def dataSource[I, A]: DataSource[I, A] = ds.asInstanceOf[DataSource[I, A]]
  }

  // Fetch result states
  sealed trait FetchStatus
  case class FetchDone[A](result: A) extends FetchStatus
  case class FetchMissing() extends FetchStatus

  // In-progress request
  case class BlockedRequest(request: FetchRequest, result: FetchStatus => IO[Unit])

  /* Combines the identities of two `FetchQuery` to the same data source. */
  private def combineIdentities[I](x: FetchQuery, y: FetchQuery): NonEmptyList[I] = {
    y.identities[I].foldLeft(x.identities[I]) {
      case (acc, i) => if (acc.exists(_ == i)) acc else NonEmptyList(acc.head, acc.tail :+ i)
    }
  }

  /* Combines two requests to the same data source. */
  implicit val brSemigroup: Semigroup[BlockedRequest] = new Semigroup[BlockedRequest] {
    def combine(x: BlockedRequest, y: BlockedRequest): BlockedRequest =
      (x.request, y.request) match {
        case (a@FetchOne(aId, aDs), b@FetchOne(anotherId, anotherDs)) =>
          if (aId == anotherId)  {
            val newRequest = FetchOne(aId, aDs)

            val newResult = (r: FetchStatus) => x.result(r) >> y.result(r)

            BlockedRequest(newRequest, newResult)
          } else {
            val newRequest = FetchMany(combineIdentities(a, b), aDs)

            val newResult = (r: FetchStatus) => r match {
              case FetchDone(m : Map[Any, Any]) =>
                for {
                  _ <- x.result(m.get(aId).map(FetchDone(_)).getOrElse(FetchMissing()))
                  _ <- y.result(m.get(anotherId).map(FetchDone(_)).getOrElse(FetchMissing()))
                } yield ()

              case FetchMissing() =>
                x.result(r) >> y.result(r)
            }

            BlockedRequest(newRequest, newResult)
          }

        case (a@FetchOne(aId, aDs), b@FetchMany(anotherIds, anotherDs)) =>
          val newRequest = FetchMany(combineIdentities(a, b), aDs)

          val newResult = (r: FetchStatus) => r match {
            case FetchDone(m : Map[Any, Any]) =>
              for {
                _ <- x.result(m.get(aId).map(FetchDone(_)).getOrElse(FetchMissing()))
                _ <- y.result(r)
              } yield ()

            case FetchMissing() =>
              x.result(r) >> y.result(r)
          }

          BlockedRequest(newRequest, newResult)

        case (a@FetchMany(manyId, manyDs), b@FetchOne(oneId, oneDs)) =>
          val newRequest = FetchMany(combineIdentities(a, b), manyDs)

          val newResult = (r: FetchStatus) => r match {
            case FetchDone(m : Map[Any, Any]) =>
              for {
                _ <- x.result(r)
                _ <- y.result(m.get(oneId).map(FetchDone(_)).getOrElse(FetchMissing()))
              } yield ()
            case FetchMissing() =>
              x.result(r) >> y.result(r)
          }

          BlockedRequest(newRequest, newResult)

        case (a@FetchMany(manyId, manyDs), b@FetchMany(otherId, otherDs)) =>
          val newRequest = FetchMany(combineIdentities(a, b), manyDs)

          val newResult = (r: FetchStatus) => x.result(r) >> y.result(r)

          BlockedRequest(newRequest, newResult)
      }
  }

  /* A map from datasources to blocked requests used to group requests to the same data source. */
  case class RequestMap(m: Map[DataSource[Any, Any], BlockedRequest])

  def optionCombine[A : Semigroup](a: A, opt: Option[A]): A =
    opt.map(a |+| _).getOrElse(a)

  implicit val rqSemigroup: Semigroup[RequestMap] = new Semigroup[RequestMap] {
    def combine(x: RequestMap, y: RequestMap): RequestMap =
      RequestMap(
        x.m.foldLeft(y.m) {
          case (acc, (ds, blocked)) => acc.updated(ds, optionCombine(blocked, acc.get(ds)))
        }
      )
  }

  // `Fetch` result data type
  sealed trait FetchResult[A]
  case class Done[A](x: A) extends FetchResult[A]
  case class Blocked[A](rs: RequestMap, cont: Fetch[A]) extends FetchResult[A]

  sealed trait Fetch[A] {
    def run: IO[FetchResult[A]]
  }
  case class Unfetch[A](run: IO[FetchResult[A]]) extends Fetch[A]

  implicit val fetchM: Monad[Fetch] = new Monad[Fetch] {
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
            Blocked(br |+| br2, product(c, c2))
        }
      } yield result)

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
                Blocked(br, flatMap(cont)(f))
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
          df <- Deferred[IO, FetchStatus]
          result = df.complete _
          blocked = BlockedRequest(request, result)
        } yield Blocked(RequestMap(Map(ds.asInstanceOf[DataSource[Any, Any]] -> blocked)), Unfetch(
          for {
            fetched <- df.get
            value = fetched match {
              case FetchDone(a) => Done(a).asInstanceOf[FetchResult[A]]
              case FetchMissing() => ???
            }
          } yield value
        ))
      )
    }

    private def fetchRound[A](rs: RequestMap)(
      implicit C: ConcurrentEffect[IO]
    ): IO[Unit] = {
      rs.m.toList.traverse_((r) => {
        val (dataSource, blocked) = r
        blocked.request match {
          case FetchOne(id, ds) =>
            ds.fetchOne[IO](id).flatMap(_ match {
              case None => blocked.result(FetchMissing())
              case Some(a) => blocked.result(FetchDone[Any](a))
            })

          case FetchMany(ids, ds) =>
            ds.fetchMany[IO](ids).flatMap((m: Map[Any, Any]) =>
              blocked.result(FetchDone(m))
            )
        }
      })
    }

    private def fetchRoundWithEnv[A](rs: RequestMap, env: Ref[IO, FetchEnv])(
      implicit C: ConcurrentEffect[IO],
       T: Timer[IO]
    ): IO[Unit] = {
      for {
        requests <- Ref.of[IO, List[Request]](List())
        _ <- rs.m.toList.traverse_((r) => {
          val (dataSource, blocked) = r
          blocked.request match {
            case FetchOne(id, ds) =>
              for {
                startTime <- T.clock.realTime(MILLISECONDS)
                o <- ds.fetchOne[IO](id)
                endTime <- T.clock.realTime(MILLISECONDS)
                _ <- requests.modify((rs) => (rs :+ Request(blocked.request, startTime, endTime), r))
                result <- o match {
                  case None => blocked.result(FetchMissing())
                  case Some(a) => blocked.result(FetchDone[Any](a))
                }
              } yield result

            case FetchMany(ids, ds) =>
              for {
                startTime <- T.clock.realTime(MILLISECONDS)
                m <- ds.fetchMany[IO](ids)
                endTime <- T.clock.realTime(MILLISECONDS)
                _ <- requests.modify((rs) => (rs :+ Request(blocked.request, startTime, endTime), r))
                result <- blocked.result(FetchDone[Map[Any, Any]](m))
              } yield result

          }
        })
        rs <- requests.get
        _ <- env.modify((e) => (e.evolve(Round(rs)), e))
      } yield ()
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
          _ <- fetchRound(rs)
          result <- run(cont)
        } yield result
      }
    } yield value

    def performRunEnv[A](
      fa: Fetch[A],
      env: Ref[IO, FetchEnv]
    )(
      implicit C: ConcurrentEffect[IO],
      T: Timer[IO]
    ): IO[A] = for {
      result <- fa.run

      value <- result.asInstanceOf[FetchResult[A]] match {
        case Done(a) => Concurrent[IO].pure(a)
        case Blocked(rs, cont) => for {
          _ <- fetchRoundWithEnv(rs, env)
          result <- performRunEnv(cont, env)
        } yield result
      }
    } yield value

    def runEnv[A](
      fa: Fetch[A]
    )(
      implicit C: ConcurrentEffect[IO],
       T: Timer[IO]
    ): IO[(FetchEnv, A)] = for {
      env <- Ref.of[IO, FetchEnv](FetchEnv())
      result <- performRunEnv(fa, env)
      e <- env.get
    } yield (e, result)
  }
}
