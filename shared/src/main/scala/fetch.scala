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
import cats.data._
import cats.implicits._

import cats.effect._
import cats.effect.concurrent.{ Ref, Deferred }

import cats.temp.par._


object `package` {
  // Fetch queries

  private[fetch] sealed trait FetchRequest extends Product with Serializable

  private[fetch] sealed trait FetchQuery[I, A] extends FetchRequest {
    def dataSource: DataSource[I, A]
    def identities: NonEmptyList[I]
  }
  private[fetch] final case class FetchOne[I, A](id: I, ds: DataSource[I, A]) extends FetchQuery[I, A] {
    override def identities: NonEmptyList[I] = NonEmptyList.one(id)
    override def dataSource: DataSource[I, A] = ds
  }
  private[fetch] final case class Batch[I, A](ids: NonEmptyList[I], ds: DataSource[I, A]) extends FetchQuery[I, A] {
    override def identities: NonEmptyList[I] = ids
    override def dataSource: DataSource[I, A] = ds
  }

  // Fetch result states

  private[fetch] sealed trait FetchStatus extends Product with Serializable
  private[fetch] final case class FetchDone[A](result: A) extends FetchStatus
  private[fetch] final case class FetchMissing() extends FetchStatus

  // Fetch errors

  sealed trait FetchException extends Throwable with NoStackTrace {
    def environment: Env
  }
  final case class MissingIdentity[I, A](i: I, request: FetchQuery[I, A], environment: Env) extends FetchException
  final case class UnhandledException(e: Throwable, environment: Env) extends FetchException

  // In-progress request

  private[fetch] final case class BlockedRequest[F[_]](request: FetchRequest, result: FetchStatus => F[Unit])

  /* Combines the identities of two `FetchQuery` to the same data source. */
  private def combineIdentities[I, A](x: FetchQuery[I, A], y: FetchQuery[I, A]): NonEmptyList[I] = {
    y.identities.foldLeft(x.identities) {
      case (acc, i) => if (acc.exists(_ == i)) acc else NonEmptyList(acc.head, acc.tail :+ i)
    }
  }

  /* Combines two requests to the same data source. */
  private def combineRequests[F[_] : Monad](x: BlockedRequest[F], y: BlockedRequest[F]): BlockedRequest[F] = (x.request, y.request) match {
    case (a@FetchOne(aId, ds), b@FetchOne(anotherId, _)) =>
      if (aId == anotherId)  {
        val newRequest = FetchOne(aId, ds)
        val newResult = (r: FetchStatus) => (x.result(r), y.result(r)).tupled.void
        BlockedRequest(newRequest, newResult)
      } else {
        val newRequest = Batch(combineIdentities(a, b), ds)
        val newResult = (r: FetchStatus) => r match {
          case FetchDone(m : Map[Any, Any]) => {
            val xResult = m.get(aId).map(FetchDone(_)).getOrElse(FetchMissing())
            val yResult = m.get(anotherId).map(FetchDone(_)).getOrElse(FetchMissing())
              (x.result(xResult), y.result(yResult)).tupled.void
          }

          case FetchMissing() =>
            (x.result(r), y.result(r)).tupled.void
        }
        BlockedRequest(newRequest, newResult)
      }

    case (a@FetchOne(oneId, ds), b@Batch(anotherIds, _)) =>
      val newRequest = Batch(combineIdentities(a, b), ds)
      val newResult = (r: FetchStatus) => r match {
        case FetchDone(m : Map[Any, Any]) => {
          val oneResult = m.get(oneId).map(FetchDone(_)).getOrElse(FetchMissing())

          (x.result(oneResult), y.result(r)).tupled.void
        }

        case FetchMissing() =>
          (x.result(r), y.result(r)).tupled.void
      }
      BlockedRequest(newRequest, newResult)

    case (a@Batch(manyId, ds), b@FetchOne(oneId, _)) =>
      val newRequest = Batch(combineIdentities(a, b), ds)
      val newResult = (r: FetchStatus) => r match {
        case FetchDone(m : Map[Any, Any]) => {
          val oneResult = m.get(oneId).map(FetchDone(_)).getOrElse(FetchMissing())
            (x.result(r), y.result(oneResult)).tupled.void
        }

        case FetchMissing() =>
          (x.result(r), y.result(r)).tupled.void
      }
      BlockedRequest(newRequest, newResult)

    case (a@Batch(manyId, ds), b@Batch(otherId, _)) =>
      val newRequest = Batch(combineIdentities(a, b), ds)
      val newResult = (r: FetchStatus) => (x.result(r), y.result(r)).tupled.void
      BlockedRequest(newRequest, newResult)
  }

  /* A map from datasources to blocked requests used to group requests to the same data source. */
  private[fetch] final case class RequestMap[F[_]](m: Map[DataSource[Any, Any], BlockedRequest[F]])

  /* Combine two `RequestMap` instances to batch requests to the same data source. */
  private def combineRequestMaps[F[_] : Monad](x: RequestMap[F], y: RequestMap[F]): RequestMap[F] =
    RequestMap(
      x.m.foldLeft(y.m) {
        case (acc, (ds, blocked)) => {
          val combinedReq: BlockedRequest[F] = acc.get(ds).fold(blocked)(combineRequests(blocked, _))
          acc.updated(ds, combinedReq)
        }
      }
    )

  // Fetch result data type

  private[fetch] sealed trait FetchResult[F[_], A] extends Product with Serializable
  private[fetch] final case class Done[F[_], A](x: A) extends FetchResult[F, A]
  private[fetch] final case class Blocked[F[_], A](rs: RequestMap[F], cont: Fetch[F, A]) extends FetchResult[F, A]
  private[fetch] final case class Throw[F[_], A](e: Env => FetchException) extends FetchResult[F, A]

  // Fetch data type

  sealed trait Fetch[F[_], A] {
    private[fetch] def run: F[FetchResult[F, A]]
  }
  private[fetch] final case class Unfetch[F[_], A](
    private[fetch] run: F[FetchResult[F, A]]
  ) extends Fetch[F, A]

  // Fetch Monad

  implicit def fetchM[F[_]: Monad]: Monad[Fetch[F, ?]] = new Monad[Fetch[F, ?]] with StackSafeMonad[Fetch[F, ?]] {
    def pure[A](a: A): Fetch[F, A] =
      Unfetch(
        Monad[F].pure(Done(a))
      )

    override def map[A, B](fa: Fetch[F, A])(f: A => B): Fetch[F, B] =
      Unfetch(for {
        fetch <- fa.run
        result = fetch match {
          case Done(v) => Done[F, B](f(v))
          case Blocked(br, cont) =>
            Blocked(br, map(cont)(f))
          case Throw(e) => Throw[F, B](e)
        }
      } yield result)

    override def product[A, B](fa: Fetch[F, A], fb: Fetch[F, B]): Fetch[F, (A, B)] =
      Unfetch[F, (A, B)](for {
        fab <- (fa.run, fb.run).tupled
        result = fab match {
          case (Throw(e), _) =>
            Throw[F, (A, B)](e)
          case (Done(a), Done(b)) =>
            Done[F, (A, B)]((a, b))
          case (Done(a), Blocked(br, c)) =>
            Blocked[F, (A, B)](br, product(fa, c))
          case (Blocked(br, c), Done(b)) =>
            Blocked[F, (A, B)](br, product(c, fb))
          case (Blocked(br, c), Blocked(br2, c2)) =>
            Blocked[F, (A, B)](combineRequestMaps(br, br2), product(c, c2))
          case (_, Throw(e)) =>
            Throw[F, (A, B)](e)
        }
      } yield result)

    def flatMap[A, B](fa: Fetch[F, A])(f: A => Fetch[F, B]): Fetch[F, B] =
      Unfetch(fa.run.flatMap {
        case Done(v) => f(v).run
        case Throw(e) =>
          Applicative[F].pure(Throw[F, B](e))
        case Blocked(br, cont) =>
          Applicative[F].pure(Blocked(br, flatMap(cont)(f)))
      })
  }

  object Fetch {
    // Fetch creation

    /**
     * Lift a plain value to the Fetch monad.
     */
    def pure[F[_]: ConcurrentEffect, A](a: A): Fetch[F, A] =
      Unfetch(Applicative[F].pure(Done(a)))

    def exception[F[_]: ConcurrentEffect, A](e: Env => FetchException): Fetch[F, A] =
      Unfetch(Applicative[F].pure(Throw[F, A](e)))

    def error[F[_]: ConcurrentEffect, A](e: Throwable): Fetch[F, A] =
      exception((env) => UnhandledException(e, env))

    def apply[F[_] : ConcurrentEffect, I, A](id: I, ds: DataSource[I, A]): Fetch[F, A] =
      Unfetch[F, A](
        for {
          deferred <- Deferred[F, FetchStatus]
          request = FetchOne(id, ds)
          result = deferred.complete _
          blocked = BlockedRequest(request, result)
          anyDs = ds.asInstanceOf[DataSource[Any, Any]]
          blockedRequest = RequestMap(Map(anyDs -> blocked))
        } yield Blocked(blockedRequest, Unfetch[F, A](
          deferred.get.map {
            case FetchDone(a) =>
              Done(a).asInstanceOf[FetchResult[F, A]]
            case FetchMissing() =>
              Throw((env) => MissingIdentity(id, request, env))
          }
        ))
      )

    def optional[F[_] : ConcurrentEffect, I, A](id: I, ds: DataSource[I, A]): Fetch[F, Option[A]] =
      Unfetch[F, Option[A]](
        for {
          deferred <- Deferred[F, FetchStatus]
          request = FetchOne(id, ds)
          result = deferred.complete _
          blocked = BlockedRequest(request, result)
          anyDs = ds.asInstanceOf[DataSource[Any, Any]]
          blockedRequest = RequestMap(Map(anyDs -> blocked))
        } yield Blocked(blockedRequest, Unfetch[F, Option[A]](
          deferred.get.map {
            case FetchDone(a) =>
              Done(Some(a)).asInstanceOf[FetchResult[F, Option[A]]]
            case FetchMissing() =>
              Done(Option.empty[A])
          }
        ))
      )

    // Running a Fetch

    /**
      * Run a `Fetch`, the result in the `F` monad.
      */
    def run[F[_]]: FetchRunner[F] = new FetchRunner[F]

    private[fetch] class FetchRunner[F[_]](private val dummy: Boolean = true) extends AnyVal {
      def apply[A](
        fa: Fetch[F, A]
      )(
        implicit
          P: Par[F],
          C: ConcurrentEffect[F],
          CS: ContextShift[F],
          T: Timer[F]
      ): F[A] =
        apply(fa, InMemoryCache.empty[F])

      def apply[A](
        fa: Fetch[F, A],
        cache: DataSourceCache[F]
      )(
        implicit
          P: Par[F],
          C: ConcurrentEffect[F],
          CS: ContextShift[F],
          T: Timer[F]
      ): F[A] = for {
        cache <- Ref.of[F, DataSourceCache[F]](cache)
        result <- performRun(fa, cache, None)
      } yield result
    }

    /**
      * Run a `Fetch`, the environment and the result in the `F` monad.
      */
    def runEnv[F[_]]: FetchRunnerEnv[F] = new FetchRunnerEnv[F]

    private[fetch] class FetchRunnerEnv[F[_]](private val dummy: Boolean = true) extends AnyVal {
      def apply[A](
        fa: Fetch[F, A]
      )(
        implicit
          P: Par[F],
          C: ConcurrentEffect[F],
          CS: ContextShift[F],
          T: Timer[F]
      ): F[(Env, A)] =
        apply(fa, InMemoryCache.empty[F])

      def apply[A](
        fa: Fetch[F, A],
        cache: DataSourceCache[F]
      )(
        implicit
          P: Par[F],
          C: ConcurrentEffect[F],
          CS: ContextShift[F],
          T: Timer[F]
      ): F[(Env, A)] = for {
        env <- Ref.of[F, Env](FetchEnv())
        cache <- Ref.of[F, DataSourceCache[F]](cache)
        result <- performRun(fa, cache, Some(env))
        e <- env.get
      } yield (e, result)
    }

    /**
      * Run a `Fetch`, the cache and the result in the `F` monad.
      */
    def runCache[F[_]]: FetchRunnerCache[F] = new FetchRunnerCache[F]

    private[fetch] class FetchRunnerCache[F[_]](private val dummy: Boolean = true) extends AnyVal {
      def apply[A](
        fa: Fetch[F, A]
      )(
        implicit
          P: Par[F],
          C: ConcurrentEffect[F],
          CS: ContextShift[F],
          T: Timer[F]
      ): F[(DataSourceCache[F], A)] =
        apply(fa, InMemoryCache.empty[F])

      def apply[A](
        fa: Fetch[F, A],
        cache: DataSourceCache[F]
      )(
        implicit
          P: Par[F],
          C: ConcurrentEffect[F],
          CS: ContextShift[F],
          T: Timer[F]
      ): F[(DataSourceCache[F], A)] = for {
        cache <- Ref.of[F, DataSourceCache[F]](cache)
        result <- performRun(fa, cache, None)
        c <- cache.get
      } yield (c, result)
    }

    // Data fetching

    private def performRun[F[_], A](
      fa: Fetch[F, A],
      cache: Ref[F, DataSourceCache[F]],
      env: Option[Ref[F, Env]]
    )(
      implicit
        P: Par[F],
        C: ConcurrentEffect[F],
        CS: ContextShift[F],
        T: Timer[F]
    ): F[A] = for {
      result <- fa.run

      value <- result match {
        case Done(a) => Applicative[F].pure(a)
        case Blocked(rs, cont) => for {
          _ <- fetchRound(rs, cache, env)
          result <- performRun(cont, cache, env)
        } yield result
        case Throw(envToThrowable) =>
          env.fold(
            Applicative[F].pure(FetchEnv() : Env)
          )(_.get).flatMap((e: Env) =>
            Sync[F].raiseError[A](envToThrowable(e))
          )
      }
    } yield value

    private def fetchRound[F[_], A](
      rs: RequestMap[F],
      cache: Ref[F, DataSourceCache[F]],
      env: Option[Ref[F, Env]]
    )(
      implicit
        P: Par[F],
        C: ConcurrentEffect[F],
        CS: ContextShift[F],
        T: Timer[F]
    ): F[Unit] = {
      val blocked = rs.m.toList.map(_._2)
      if (blocked.isEmpty) Applicative[F].unit
      else
        for {
          requests <- NonEmptyList.fromListUnsafe(blocked).parTraverse(
            runBlockedRequest(_, cache, env)
          )
          performedRequests = requests.foldLeft(List.empty[Request])(_ ++ _)
          _ <- if (performedRequests.isEmpty) Applicative[F].unit
          else env match {
            case Some(e) => e.modify((oldE) => (oldE.evolve(Round(performedRequests)), oldE))
            case None => Applicative[F].unit
          }
        } yield ()
    }

    private def runBlockedRequest[F[_], A](
      blocked: BlockedRequest[F],
      cache: Ref[F, DataSourceCache[F]],
      env: Option[Ref[F, Env]]
    )(
      implicit
        P: Par[F],
        C: ConcurrentEffect[F],
        CS: ContextShift[F],
        T: Timer[F]
    ): F[List[Request]] =
      blocked.request match {
        case q @ FetchOne(id, ds) => runFetchOne[F](q, blocked.result, cache, env)
        case q @ Batch(ids, ds) => runBatch[F](q, blocked.result, cache, env)
      }
  }

  private def runFetchOne[F[_]](
    q: FetchOne[Any, Any],
    putResult: FetchStatus => F[Unit],
    cache: Ref[F, DataSourceCache[F]],
    env: Option[Ref[F, Env]]
  )(
    implicit
      P: Par[F],
      C: ConcurrentEffect[F],
      CS: ContextShift[F],
      T: Timer[F]
  ): F[List[Request]] =
    for {
      c <- cache.get
      maybeCached <- c.lookup(q.id, q.ds)
      result <- maybeCached match {
        // Cached
        case Some(v) => putResult(FetchDone(v)).as(Nil)

        // Not cached, must fetch
        case None => for {
          startTime <- T.clock.monotonic(MILLISECONDS)
          o <- q.ds.fetch(q.id)
          endTime <- T.clock.monotonic(MILLISECONDS)
          result <- o match {
            // Fetched
            case Some(a) => for {
              newC <- c.insert(q.id, a, q.ds)
              _ <- cache.set(newC)
              result <- putResult(FetchDone[Any](a))
            } yield List(Request(q, startTime, endTime))

            // Missing
            case None =>
              putResult(FetchMissing()).as(List(Request(q, startTime, endTime)))
          }
        } yield result
      }
    } yield result

  private case class BatchedRequest(
    batches: List[Batch[Any, Any]],
    results: Map[Any, Any]
  )

  private def runBatch[F[_]](
    q: Batch[Any, Any],
    putResult: FetchStatus => F[Unit],
    cache: Ref[F, DataSourceCache[F]],
    env: Option[Ref[F, Env]]
  )(
    implicit
      P: Par[F],
      C: ConcurrentEffect[F],
      CS: ContextShift[F],
      T: Timer[F]
  ): F[List[Request]] =
    for {
      c <- cache.get

      // Remove cached IDs
      idLookups <- q.ids.traverse[F, (Any, Option[Any])](
        (i) => c.lookup(i, q.ds).tupleLeft(i)
      )
      (uncachedIds, cached) = idLookups.toList.partitionEither {
        case (i, result) => result.tupleLeft(i).toRight(i)
      }
      cachedResults = cached.toMap
      result <- uncachedIds.toNel match {
        // All cached
        case None => putResult(FetchDone[Map[Any, Any]](cachedResults)).as(Nil)

        // Some uncached
        case Some(uncached) => for {
          startTime <- T.clock.monotonic(MILLISECONDS)

          request = Batch(uncached, q.ds)

          batchedRequest <- request.ds.maxBatchSize match {
            // Unbatched
            case None =>
              request.ds.batch[F](uncached).map(BatchedRequest(List(request), _))

            // Batched
            case Some(batchSize) =>
              runBatchedRequest[F](request, batchSize, request.ds.batchExecution)
          }

          endTime <- T.clock.monotonic(MILLISECONDS)
          resultMap = combineBatchResults(batchedRequest.results, cachedResults)

          updatedCache <- c.insertMany(batchedRequest.results, request.ds)
          _ <- cache.set(updatedCache)

          result <- putResult(FetchDone[Map[Any, Any]](resultMap))

        } yield batchedRequest.batches.map(Request(_, startTime, endTime))
      }
    } yield result

  private def runBatchedRequest[F[_]](
    q: Batch[Any, Any],
    batchSize: Int,
    e: BatchExecution
  )(
    implicit
      P: Par[F],
      C: ConcurrentEffect[F],
      CS: ContextShift[F],
      T: Timer[F]
  ): F[BatchedRequest] = {
    val batches = NonEmptyList.fromListUnsafe(
      q.ids.toList.grouped(batchSize)
        .map(batchIds => NonEmptyList.fromListUnsafe(batchIds))
        .toList
    )
    val reqs = batches.toList.map(Batch[Any, Any](_, q.ds))

    val results = e match {
      case Sequentially =>
        batches.traverse(q.ds.batch[F])
      case InParallel =>
        batches.parTraverse(q.ds.batch[F])
    }

    results.map(_.toList.reduce(combineBatchResults)).map(BatchedRequest(reqs, _))
  }

  private def combineBatchResults(r: Map[Any, Any], rs: Map[Any, Any]): Map[Any, Any] =
    r ++ rs
}
