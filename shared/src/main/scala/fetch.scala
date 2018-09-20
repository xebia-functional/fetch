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


// Fetch queries

private[fetch] sealed trait FetchRequest

private[fetch] sealed trait FetchQuery[F[_], I, A] extends FetchRequest with Product with Serializable {
  def dataSource: DataSource[F, I, A]
  def identities: NonEmptyList[I]
}
private[fetch] final case class FetchOne[F[_], I, A](id: I, ds: DataSource[F, I, A]) extends FetchQuery[F, I, A] {
  override def identities: NonEmptyList[I] = NonEmptyList.one(id)
  override def dataSource: DataSource[F, I, A] = ds
}
private[fetch] final case class Batch[F[_], I, A](ids: NonEmptyList[I], ds: DataSource[F, I, A]) extends FetchQuery[F, I, A] {
  override def identities: NonEmptyList[I] = ids
  override def dataSource: DataSource[F, I, A] = ds
}

// Fetch result states

private[fetch] sealed trait FetchStatus extends Product with Serializable
private[fetch] final case class FetchDone[A](result: A) extends FetchStatus
private[fetch] final case class FetchMissing() extends FetchStatus

// Fetch errors

sealed trait FetchException extends Throwable with NoStackTrace {
  def environment: Env
}
final case class MissingIdentity[F[_], I, A](i: I, request: FetchQuery[F, I, A], environment: Env) extends FetchException
final case class UnhandledException(e: Throwable, environment: Env) extends FetchException

// In-progress request

private[fetch] final case class BlockedRequest[F[_]](request: FetchQuery[F, Any, Any], result: FetchStatus => F[Unit]) {
  override def toString: String = s"BlockedRequest($request, cont...)"
}

private[fetch] object BlockedRequest {

  def combine[F[_] : Monad](x: BlockedRequest[F], y: BlockedRequest[F]): BlockedRequest[F] = (x.request, y.request) match {
    case (a@FetchOne(aId, ds), b@FetchOne(anotherId, _)) =>
      // println(s"got $a and $b")
      if (aId == anotherId)  {
        val newRequest = a
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

  /* Combines the identities of two `FetchQuery` to the same data source. */
  private def combineIdentities[F[_], I, A](x: FetchQuery[F, I, A], y: FetchQuery[F, I, A]): NonEmptyList[I] =
    y.identities.foldLeft(x.identities) {
      case (acc, i) => if (acc.exists(_ == i)) acc else NonEmptyList(acc.head, acc.tail :+ i)
    }
}

/* Combines two requests to the same data source. */


/* A map from datasources to blocked requests used to group requests to the same data source. */
private[fetch] final case class RequestMap[F[_]](m: Map[DataSource[F, Any, Any], BlockedRequest[F]])

private[fetch] object RequestMap {
  /* Combine two `RequestMap` instances to batch requests to the same data source. */
  def combine[F[_] : Monad](x: RequestMap[F], y: RequestMap[F]): RequestMap[F] =
    RequestMap(
      x.m.foldLeft(y.m) {
        case (acc, (ds, blocked)) =>
          val combinedReq: BlockedRequest[F] = acc.get(ds).fold(blocked)(BlockedRequest.combine(blocked, _))
          acc.updated(ds, combinedReq)
      }
    )
}

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


object Fetch {
  // Fetch creation

  /**
   * Lift a plain value to the Fetch monad.
   */
  def pure[F[_]: Applicative, A](a: A): Fetch[F, A] =
    Unfetch(Applicative[F].pure(Done(a)))

  def exception[F[_]: Applicative, A](e: Env => FetchException): Fetch[F, A] =
    Unfetch(Applicative[F].pure(Throw[F, A](e)))

  def error[F[_]: Applicative, A](e: Throwable): Fetch[F, A] =
    exception((env) => UnhandledException(e, env))

  def apply[F[_]: Concurrent, I, A](id: I, ds: DataSource[F, I, A]): Fetch[F, A] =
    create(id, ds) {
      case (FetchDone(a), _) =>
        Done(a).asInstanceOf[FetchResult[F, A]]
      case (FetchMissing(), query) =>
        Throw((env) => MissingIdentity(id, query, env))
    }

  def optional[F[_] : Concurrent, I, A](id: I, ds: DataSource[F, I, A]): Fetch[F, Option[A]] =
    create(id, ds)((status, _) => status match {
      case FetchDone(a) =>
        Done(Some(a)).asInstanceOf[FetchResult[F, Option[A]]]
      case FetchMissing() =>
        Done(Option.empty[A])
    })

  private def create[F[_] : Concurrent, I, A, B](
    id: I,
    ds: DataSource[F, I, A]
  )(f: (FetchStatus, FetchQuery[F, I, A]) => FetchResult[F, B]): Fetch[F, B] =
    Unfetch[F, B](Deferred[F, FetchStatus].map { statusDef =>
      val anyDs = ds.asInstanceOf[DataSource[F, Any, Any]]
      val blocked = BlockedRequest(FetchOne[F, Any, Any](id, anyDs), statusDef.complete)
      val requestMap = RequestMap(Map(anyDs -> blocked))
      Blocked(requestMap, Unfetch(statusDef.get.map(f(_, FetchOne(id, ds)))))
    })

  // Fetch Monad

  implicit def fetchM[F[_]: Monad]: Monad[Fetch[F, ?]] = new Monad[Fetch[F, ?]] with StackSafeMonad[Fetch[F, ?]] {
    def pure[A](a: A): Fetch[F, A] =
      Unfetch(
        Monad[F].pure(Done(a))
      )

    override def map[A, B](fa: Fetch[F, A])(f: A => B): Fetch[F, B] =
      Unfetch(fa.run.map {
        case Done(v) => Done[F, B](f(v))
        case Blocked(br, cont) =>
          Blocked(br, map(cont)(f))
        case Throw(e) => Throw[F, B](e)
      })

    override def product[A, B](fa: Fetch[F, A], fb: Fetch[F, B]): Fetch[F, (A, B)] =
      Unfetch[F, (A, B)]((fa.run, fb.run).mapN {
        case (Throw(e), _) =>
          Throw[F, (A, B)](e)
        case (_, Throw(e)) =>
          Throw[F, (A, B)](e)
        case (Done(a), Done(b)) =>
          Done[F, (A, B)]((a, b))
        case (Done(a), Blocked(br, c)) =>
          Blocked[F, (A, B)](br, product(fa, c))
        case (Blocked(br, c), Done(b)) =>
          Blocked[F, (A, B)](br, product(c, fb))
        case (Blocked(br, c), Blocked(br2, c2)) =>
          Blocked[F, (A, B)](RequestMap.combine(br, br2), product(c, c2))
      // }})
      })

    override def flatMap[A, B](fa: Fetch[F, A])(f: A => Fetch[F, B]): Fetch[F, B] =
      Unfetch(fa.run.flatMap {
        case Done(v) => f(v).run
        case Throw(e) =>
          Applicative[F].pure(Throw[F, B](e))
        case Blocked(br, cont) =>
          Applicative[F].pure(Blocked(br, flatMap(cont)(f)))
      })
  }


  // Running a Fetch

  /**
    * Run a `Fetch`, the result in the `F` monad.
    */
  def run[F[_]]: FetchRunner[F] = new FetchRunner[F]

  private[fetch] class FetchRunner[F[_]](private val dummy: Boolean = true) extends AnyVal {
    def apply[A](
      fa: Fetch[F, A],
      cache: DataSourceCache[F] // = InMemoryCache.empty[F]
    )(
      implicit
        P: Par[F],
        F: Sync[F],
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
      fa: Fetch[F, A],
      cache: DataSourceCache[F] // = InMemoryCache.empty[F]
    )(
      implicit
        P: Par[F],
        F: Sync[F],
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
      fa: Fetch[F, A],
      cache: DataSourceCache[F] //= InMemoryCache.empty[F]
    )(
      implicit
        P: Par[F],
        F: Sync[F],
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
      F: MonadError[F, Throwable],
      T: Timer[F]
  ): F[A] =
    fa.run.flatMap {
      case Done(a) =>
        Applicative[F].pure(a)
      case Blocked(rs, cont) =>
        fetchRound(rs, cache, env) *> performRun(cont, cache, env)
      case Throw(envToThrowable) =>
        env.fold(Applicative[F].pure[Env](FetchEnv()))(_.get).flatMap((e: Env) =>
          MonadError[F, Throwable].raiseError[A](envToThrowable(e))
        )
    }

  private def fetchRound[F[_], A](
    rs: RequestMap[F],
    cache: Ref[F, DataSourceCache[F]],
    env: Option[Ref[F, Env]]
  )(
    implicit
      P: Par[F],
      C: Monad[F],
      T: Timer[F]
  ): F[Unit] =
    OptionT
      .fromOption[F](rs.m.toList.map(_._2).toNel)
      .flatMapF { blocked =>
        Parallel
          .parNonEmptyTraverse(blocked)(runBlockedRequest(_, cache, env))
          // .parTraverse(blocked)(runBlockedRequest(_, cache, env))
          .map(reqList => (reqList.reduceK.toNel, env).tupled)
      }
      .semiflatMap { case (requests, envRef) =>
        envRef.update(_.evolve(Round(requests)))
      }
      .getOrElse(())

  private def runBlockedRequest[F[_], A](
    blocked: BlockedRequest[F],
    cache: Ref[F, DataSourceCache[F]],
    env: Option[Ref[F, Env]]
  )(
    implicit
      P: Par[F],
      F: Monad[F],
      T: Timer[F]
  ): F[List[Request]] =
    blocked.request match {
      case q @ FetchOne(id, ds) => runFetchOne[F](q, blocked.result, cache, env)
      case q @ Batch(ids, ds) => runBatch[F](q, blocked.result, cache, env)
    }

  private def runFetchOne[F[_]](
    q: FetchOne[F, Any, Any],
    putResult: FetchStatus => F[Unit],
    cache: Ref[F, DataSourceCache[F]],
    env: Option[Ref[F, Env]]
  )(
    implicit
      P: Par[F],
      C: Monad[F],
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

  private case class BatchedRequest[F[_]](
    batches: NonEmptyList[Batch[F, Any, Any]],
    results: Map[Any, Any]
  )

  private def runBatch[F[_]](
    q: Batch[F, Any, Any],
    putResult: FetchStatus => F[Unit],
    cacheRef: Ref[F, DataSourceCache[F]],
    env: Option[Ref[F, Env]]
  )(
    implicit
      P: Par[F],
      F: Monad[F],
      T: Timer[F]
  ): F[List[Request]] = {

    def getUncached(ids: NonEmptyList[Any]): F[(Map[Any, Any], List[Request])] =
      for {
        startTime <- T.clock.monotonic(MILLISECONDS)
        request = Batch(ids, q.ds)
        batchedRequest <- request.ds.maxBatchSize match {
          case None =>
            request.ds.batch(ids).map(BatchedRequest(NonEmptyList.one(request), _))

          case Some(batchSize) =>
            runBatchedRequest[F](request, batchSize, request.ds.batchExecution)
        }
        endTime <- T.clock.monotonic(MILLISECONDS)
        requests = batchedRequest.batches.map(Request(_, startTime, endTime)).toList
      } yield (batchedRequest.results, requests)

    def updateCache(cache: DataSourceCache[F])(add: Map[Any, Any]): F[Unit] =
      cache.insertMany(add, q.ds).flatMap(cacheRef.set)

    for {
      c <- cacheRef.get
      uncachedCached <- cachedResults(q, c)
      (resultMap, requests) <-
        uncachedCached.map(_.toList.toMap).fold[F[(Map[Any, Any], List[Request])]](
          uncachedIds =>
            getUncached(uncachedIds).flatTap(t => updateCache(c)(t._1)),
          cacheResults =>
            F.pure((cacheResults, Nil)),
          (uncachedIds, cachedResults) =>
            getUncached(uncachedIds)
              .flatTap(t => updateCache(c)(t._1))
              .map(_.leftMap(combineBatchResults(cachedResults, _)))
        )
      _ <- putResult(FetchDone[Map[Any, Any]](resultMap))
    } yield requests
  }

  private def cachedResults[F[_]: Apply, I, A](
    query: FetchQuery[F, I, A],
    cache: DataSourceCache[F]
  ): F[Ior[NonEmptyList[I], NonEmptyList[(I, A)]]] =
    query.identities.nonEmptyTraverse(id =>
      cache.lookup(id, query.dataSource).map(
        _.map(cachedVal => NonEmptyList.one((id, cachedVal)))
         .toRightIor(NonEmptyList.one(id))
      )
    ).map(_.reduce)

  private def runBatchedRequest[F[_]](
    q: Batch[F, Any, Any],
    batchSize: Int,
    e: BatchExecution
  )(
    implicit
      P: Par[F],
      F: Monad[F],
      T: Timer[F]
  ): F[BatchedRequest[F]] = {
    val batchedIds = groupedNel(q.ids, batchSize)
    val results = e match {
      case Sequentially =>
        batchedIds.traverse(q.ds.batch)
      case InParallel =>
        batchedIds.parTraverse(q.ds.batch)
    }
    val reqs = batchedIds.map(Batch[F, Any, Any](_, q.ds))
    results.map(_.toList.reduce(combineBatchResults)).map(BatchedRequest(reqs, _))
  }

  private def groupedNel[A](nel: NonEmptyList[A], size: Int): NonEmptyList[NonEmptyList[A]] =
    NonEmptyList.fromListUnsafe(
      nel.toList.grouped(size).map(as => NonEmptyList.fromListUnsafe(as)).toList
    )

  private def combineBatchResults(r: Map[Any, Any], rs: Map[Any, Any]): Map[Any, Any] =
    r ++ rs
}
