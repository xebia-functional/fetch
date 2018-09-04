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
import scala.util.control.NoStackTrace

import scala.concurrent.duration.MILLISECONDS

import cats._
import cats.instances.list._
import cats.effect._
import cats.effect.concurrent.{Ref, Deferred}
import cats.syntax.all._
import cats.data.NonEmptyList


object `package` {
  // Fetch queries
  sealed trait FetchRequest extends Product with Serializable

  // A query to a remote data source
  sealed trait FetchQuery extends FetchRequest {
    def dataSource[I, A]: DataSource[I, A]
    def identities[I]: NonEmptyList[I]
  }
  case class FetchOne[I, A](id: I, ds: DataSource[I, A]) extends FetchQuery {
    override def identities[I]: NonEmptyList[I] = NonEmptyList(id.asInstanceOf[I], List.empty[I])
    override def dataSource[I, A]: DataSource[I, A] = ds.asInstanceOf[DataSource[I, A]]
  }
  case class Batch[I, A](ids: NonEmptyList[I], ds: DataSource[I, A]) extends FetchQuery {
    override def identities[I]: NonEmptyList[I] = ids.toNonEmptyList.asInstanceOf[NonEmptyList[I]]
    override def dataSource[I, A]: DataSource[I, A] = ds.asInstanceOf[DataSource[I, A]]
  }

  // Fetch result states
  sealed trait FetchStatus
  case class FetchDone[A](result: A) extends FetchStatus
  case class FetchMissing() extends FetchStatus

  // Fetch errors
  sealed trait FetchException extends Throwable with NoStackTrace
  case class MissingIdentity[I](i: I, request: FetchQuery) extends FetchException
  case class UnhandledException(e: Throwable) extends FetchException

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
            val newRequest = Batch(combineIdentities(a, b), aDs)

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

        case (a@FetchOne(aId, aDs), b@Batch(anotherIds, anotherDs)) =>
          val newRequest = Batch(combineIdentities(a, b), aDs)

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

        case (a@Batch(manyId, manyDs), b@FetchOne(oneId, oneDs)) =>
          val newRequest = Batch(combineIdentities(a, b), manyDs)

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

        case (a@Batch(manyId, manyDs), b@Batch(otherId, otherDs)) =>
          val newRequest = Batch(combineIdentities(a, b), manyDs)

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
  case class Unfetch[A](
    run: IO[FetchResult[A]]
  ) extends Fetch[A]

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
      } yield result)

    override def product[A, B](fa: Fetch[A], fb: Fetch[B]): Fetch[(A, B)] =
      Unfetch(for {
        fab <- (fa.run, fb.run).tupled
        result = fab match {
          case (Done(a), Done(b)) => Done((a, b))
          case (Done(a), Blocked(br, c)) => Blocked(br, product(fa, c))
          case (Blocked(br, c), Done(b)) => Blocked(br, product(c, fb))
          case (Blocked(br, c), Blocked(br2, c2)) =>
            Blocked(br |+| br2, product(c, c2))
        }
      } yield result)

    override def map2[A, B, C](fa: Fetch[A], fb: Fetch[B])(ff: (A, B) => C): Fetch[C] =
      Unfetch(for {
        fab <- (fa.run, fb.run).tupled
        result = fab match {
          case (Done(a), Done(b)) => Done(ff(a, b))
          case (Done(a), Blocked(br, c)) => Blocked(br, map2(fa, c)(ff))
          case (Blocked(br, c), Done(b)) => Blocked(br, map2(c, fb)(ff))
          case (Blocked(br, c), Blocked(br2, c2)) =>
            Blocked(br |+| br2, map2(c, c2)(ff))
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

    def apply[I, A](id: I, ds: DataSource[I, A])(
      implicit
        CS: ContextShift[IO]
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
            value <- fetched match {
              case FetchDone(a) =>
                IO.pure(Done(a).asInstanceOf[FetchResult[A]])

              case FetchMissing() =>
                IO.raiseError(MissingIdentity(id, request))
            }
          } yield value
        ))
      )
    }

    def error[A](e: Throwable): Fetch[A] =
      Unfetch(IO.raiseError(UnhandledException(e)))

    /**
      * Run a `Fetch`, the result in the `IO` monad.
      */
    def run[A](
      fa: Fetch[A],
      cache: DataSourceCache = InMemoryCache.empty
    )(
      implicit C: ConcurrentEffect[IO],
      T: Timer[IO],
      P: Parallel[IO, IO.Par]
    ): IO[A] = for {
      cache <- Ref.of[IO, DataSourceCache](cache)
      result <- performRun(fa, cache, None)
    } yield result

    /**
      * Run a `Fetch`, the environment and the result in the `IO` monad.
      */
    def runEnv[A](
      fa: Fetch[A],
      cache: DataSourceCache = InMemoryCache.empty
    )(
      implicit C: ConcurrentEffect[IO],
      T: Timer[IO],
      P: Parallel[IO, IO.Par]
    ): IO[(FetchEnv, A)] = for {
      env <- Ref.of[IO, FetchEnv](FetchEnv())
      cache <- Ref.of[IO, DataSourceCache](cache)
      result <- performRun(fa, cache, Some(env))
      e <- env.get
    } yield (e, result)

    /**
      * Run a `Fetch`, the cache and the result in the `IO` monad.
      */
    def runCache[A](
      fa: Fetch[A],
      cache: DataSourceCache = InMemoryCache.empty
    )(
      implicit C: ConcurrentEffect[IO],
      T: Timer[IO],
      P: Parallel[IO, IO.Par]
    ): IO[(DataSourceCache, A)] = for {
      cache <- Ref.of[IO, DataSourceCache](cache)
      result <- performRun(fa, cache, None)
      c <- cache.get
    } yield (c, result)

    private def performRun[A](
      fa: Fetch[A],
      cache: Ref[IO, DataSourceCache],
      env: Option[Ref[IO, FetchEnv]]
    )(
      implicit C: ConcurrentEffect[IO],
      T: Timer[IO],
      P: Parallel[IO, IO.Par]
    ): IO[A] = for {
      result <- fa.run

      value <- result.asInstanceOf[FetchResult[A]] match {
        case Done(a) => Concurrent[IO].pure(a)
        case Blocked(rs, cont) => for {
          _ <- fetchRound(rs, cache, env)
          result <- performRun(cont, cache, env)
        } yield result
      }
    } yield value

    private def fetchRound[A](
      rs: RequestMap,
      cache: Ref[IO, DataSourceCache],
      env: Option[Ref[IO, FetchEnv]]
    )(
      implicit
        C: ConcurrentEffect[IO],
      T: Timer[IO],
      P: Parallel[IO, IO.Par]
    ): IO[Unit] = {
      val blocked = rs.m.toList.map(_._2)
      if (blocked.isEmpty) IO.unit
      else
        for {
          requests <- NonEmptyList.fromListUnsafe(blocked).parTraverse(
            runBlockedRequest(_, cache, env)
          )
          performedRequests = requests.foldLeft(List.empty[Request])(_ ++ _)
          _ <- if (performedRequests.isEmpty) IO.unit
          else env match {
            case Some(e) => e.modify((oldE) => (oldE.evolve(Round(performedRequests)), oldE))
            case None => IO.unit
          }
        } yield ()
    }

    private def runBlockedRequest[A](
      blocked: BlockedRequest,
      cache: Ref[IO, DataSourceCache],
      env: Option[Ref[IO, FetchEnv]]
    )(
      implicit
        C: ConcurrentEffect[IO],
      T: Timer[IO],
      P: Parallel[IO, IO.Par]
    ): IO[List[Request]] =
      blocked.request match {
        case q @ FetchOne(id, ds) => runFetchOne(q, blocked.result, cache, env)
        case q @ Batch(ids, ds) => runBatch(q, blocked.result, cache, env)
      }
  }

  private def runFetchOne(
    q: FetchOne[Any, Any],
    putResult: FetchStatus => IO[Unit],
    cache: Ref[IO, DataSourceCache],
    env: Option[Ref[IO, FetchEnv]]
  )(
    implicit
      C: ConcurrentEffect[IO],
    T: Timer[IO]
  ): IO[List[Request]] =
    for {
      c <- cache.get
      startTime <- T.clock.realTime(MILLISECONDS)
      maybeCached <- c.lookup(q.id, q.ds)
      result <- maybeCached match {
        // Cached
        case Some(v) => putResult(FetchDone(v)) >> IO.pure(Nil)

        // Not cached, must fetch
        case None => for {
          startTime <- T.clock.realTime(MILLISECONDS)
          o <- q.ds.fetch(q.id)
          endTime <- T.clock.realTime(MILLISECONDS)
          result <- o match {
            // Fetched
            case Some(a) => for {
              newC <- c.insert(q.id, q.ds, a)
              _ <- cache.modify((c) => (newC, c))
              result <- putResult(FetchDone[Any](a))
            } yield List(Request(q, startTime, endTime))

            // Missing
            case None =>
              putResult(FetchMissing()) >> IO.pure(List(Request(q, startTime, endTime)))
          }
        } yield result
      }
    } yield result

  private case class BatchedRequest(
    batches: List[Batch[Any, Any]],
    results: Map[Any, Any]
  )

  private def runBatch(
    q: Batch[Any, Any],
    putResult: FetchStatus => IO[Unit],
    cache: Ref[IO, DataSourceCache],
    env: Option[Ref[IO, FetchEnv]]
  )(
    implicit
      C: ConcurrentEffect[IO],
    T: Timer[IO],
    P: Parallel[IO, IO.Par]
  ): IO[List[Request]] =
    for {
      c <- cache.get

      // Remove cached IDs
      idLookups <- q.ids.traverse[IO, (Any, Option[Any])](
        (i) => c.lookup(i, q.ds).map( m => (i, m) )
      )
      cachedResults = idLookups.collect({
        case (i, Some(a)) => (i, a)
      }).toMap
      uncachedIds = idLookups.collect({
        case (i, None) => i
      })

      result <- uncachedIds match {
        // All cached
        case Nil => putResult(FetchDone[Map[Any, Any]](cachedResults)) >> IO.pure(Nil)

        // Some uncached
        case l@_ => for {
          startTime <- T.clock.realTime(MILLISECONDS)

          uncached = NonEmptyList.fromListUnsafe(l)
          request = Batch(uncached, q.ds)

          batchedRequest <- request.ds.maxBatchSize match {
            // Unbatched
            case None =>
              request.ds.batch(uncached).map(BatchedRequest(List(request), _))

            // Batched
            case Some(batchSize) =>
              runBatchedRequest(request, batchSize, request.ds.batchExecution)
          }

          endTime <- T.clock.realTime(MILLISECONDS)
          resultMap = combineBatchResults(batchedRequest.results, cachedResults)

          updatedCache <- batchedRequest.results.toList.foldLeftM(c)({
            case (c, (i, v)) => c.insert(i, request.ds, v)
          })
          _ <- cache.modify((c) => (updatedCache, c))
          result <- putResult(FetchDone[Map[Any, Any]](resultMap))
        } yield batchedRequest.batches.map(Request(_, startTime, endTime))
      }
    } yield result

  private def runBatchedRequest(
    q: Batch[Any, Any],
    batchSize: Int,
    e: ExecutionType
  )(
    implicit
      C: ConcurrentEffect[IO],
    T: Timer[IO],
    P: Parallel[IO, IO.Par]
  ): IO[BatchedRequest] = {
    val batches = NonEmptyList.fromListUnsafe(
      q.ids.toList.grouped(batchSize)
        .map(batchIds => NonEmptyList.fromListUnsafe(batchIds))
        .toList
    )
    val reqs = batches.toList.map(Batch[Any, Any](_, q.ds))

    val results = e match {
      case Sequential =>
        batches.traverse(q.ds.batch)
      case Parallel =>
        batches.parTraverse(q.ds.batch)
    }

    results.map(_.toList.reduce(combineBatchResults)).map(BatchedRequest(reqs, _))
  }

  private def combineBatchResults(r: Map[Any, Any], rs: Map[Any, Any]): Map[Any, Any] =
    r ++ rs
}
