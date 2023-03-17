/*
 * Copyright 2016-2023 47 Degrees Open Source <https://www.47deg.com>
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

import cats._
import cats.data._
import cats.effect._
import cats.effect.implicits._
import cats.syntax.all._

import scala.util.control.NoStackTrace

object `package` {
  private[fetch] sealed trait FetchRequest extends Product with Serializable {
    def cached: Boolean
  }

  private[fetch] sealed trait FetchQuery[I, A] extends FetchRequest {
    def data: Data[I, A]
    def identities: Set[I]
  }
  private[fetch] final case class FetchOne[I, A](
      id: I,
      data: Data[I, A],
      cached: Boolean = false
  ) extends FetchQuery[I, A] {
    override def identities: Set[I] = Set(id)
  }
  private[fetch] final case class Batch[I, A](
      ids: NonEmptyList[I],
      data: Data[I, A],
      cached: Boolean = false
  ) extends FetchQuery[I, A] {
    override def identities: Set[I] = ids.toList.toSet
  }

  // Fetch result states

  private[fetch] sealed trait FetchStatus                 extends Product with Serializable
  private[fetch] final case class FetchDone[A](result: A) extends FetchStatus
  private[fetch] final case class FetchMissing()          extends FetchStatus

  // Fetch errors

  sealed trait FetchException extends Throwable with NoStackTrace {
    def log: Log
  }
  final case class MissingIdentity[I, A](i: I, request: FetchQuery[I, A], log: Log)
      extends FetchException
  final case class UnhandledException(e: Throwable, log: Log) extends FetchException

  // In-progress request

  private[fetch] final case class BlockedRequest[F[_]](
      request: FetchRequest,
      result: CombinationTailRec[F]
  )

  /* Combines the identities of two `FetchQuery` to the same data source. */
  private def combineIdentities[I, A](x: FetchQuery[I, A], y: FetchQuery[I, A]): NonEmptyList[I] =
    (x.identities ++ y.identities).toList.toNel.get

  private[fetch] sealed trait CombinationTailRec[F[_]] extends Product with Serializable {
    def flatMap(f: () => CombinationTailRec[F]): CombinationTailRec[F] = CombinationFlatMap(this, f)
  }

  private[fetch] case class CombinationDone[F[_]](result: F[Unit]) extends CombinationTailRec[F]
  private[fetch] case class CombinationLeaf[F[_]](f: FetchStatus => F[Unit])
      extends CombinationTailRec[F]
  private[fetch] case class CombinationBarrier[F[_]](
      sub: () => CombinationTailRec[F],
      status: FetchStatus
  ) extends CombinationTailRec[F]
  private[fetch] case class CombinationSuspend[F[_]](resume: FetchStatus => CombinationTailRec[F])
      extends CombinationTailRec[F]
  private[fetch] case class CombinationFlatMap[F[_]](
      sub: CombinationTailRec[F],
      f: () => CombinationTailRec[F]
  ) extends CombinationTailRec[F]

  /* Run combineRequests in the stack safe way */
  def runCombinationResult[F[_]: Monad](
      comb: CombinationTailRec[F],
      status: FetchStatus
  ): F[Unit] =
    comb match {
      case CombinationDone(r) =>
        // Combination is finished, just return the result
        r

      case CombinationLeaf(f) =>
        // Last step before completing a combination, apply the method and box the result
        runCombinationResult(
          CombinationDone(f(status)),
          status
        )

      case CombinationBarrier(sub, newStatus) =>
        // A barrier is boxing a CombinationTailRec to which a specific status must be applied
        // Just make a recursive call with the status
        runCombinationResult(sub(), newStatus)

      case CombinationSuspend(resume) =>
        // Base CombinationTailRec, derives a CombinationTailRec from the current status
        runCombinationResult(resume(status), status)

      case CombinationFlatMap(current, next) =>
        current match {
          case CombinationDone(r) =>
            // Current combination is done, just flatMap the result with the next combination
            r.flatMap(_ => runCombinationResult(next(), status))

          case CombinationLeaf(f) =>
            // Current combination is a lead. Compute and box the result, and still flatMap on the next combination
            runCombinationResult(
              CombinationDone(f(status)).flatMap(next),
              status
            )

          case CombinationBarrier(sub, newStatus) =>
            // Barrier, the current combination needs to be ran with the specific status, and the next one needs to work on the current status
            runCombinationResult(
              sub().flatMap(() => CombinationBarrier(next, status)),
              newStatus
            )

          case CombinationSuspend(sub) =>
            // Derive the combination from the current status and still flatMap on the next combination
            runCombinationResult(
              sub(status).flatMap(next),
              status
            )

          case CombinationFlatMap(sub, fNext) =>
            // FlatMap of a FlatMap
            // Treat the current combination, then the fNext, and finally the next combination of the top level flatMap
            runCombinationResult(
              sub.flatMap(() => fNext().flatMap(next)),
              status
            )
        }
    }

  /* Combines two requests to the same data source. */
  private def combineRequests[F[_]: Monad](
      x: BlockedRequest[F],
      y: BlockedRequest[F]
  ): BlockedRequest[F] =
    (x.request, y.request) match {
      case (a: FetchOne[Any, Any], b: FetchOne[Any, Any]) =>
        val aId       = a.id
        val ds        = a.data
        val anotherId = b.id
        if (aId == anotherId) {
          val newRequest = FetchOne(aId, ds)
          val newResult  = x.result.flatMap(() => y.result)
          BlockedRequest(newRequest, newResult)
        } else {
          val combined   = combineIdentities(a, b)
          val newRequest = Batch(combined, ds)
          val newResult = CombinationSuspend((r: FetchStatus) =>
            r match {
              case FetchDone(preM) =>
                val m       = preM.asInstanceOf[Map[Any, Any]]
                val xResult = m.get(aId).map(FetchDone(_)).getOrElse(FetchMissing())
                val yResult = m.get(anotherId).map(FetchDone(_)).getOrElse(FetchMissing())
                CombinationBarrier(() => x.result, xResult)
                  .flatMap(() => CombinationBarrier(() => y.result, yResult))

              case FetchMissing() =>
                x.result.flatMap(() => y.result)
            }
          )
          BlockedRequest(newRequest, newResult)
        }

      case (a: FetchOne[Any, Any], b: Batch[Any, Any]) =>
        val oneId      = a.id
        val ds         = a.data
        val combined   = combineIdentities(a, b)
        val newRequest = Batch(combined, ds)
        val newResult = CombinationSuspend((r: FetchStatus) =>
          r match {
            case FetchDone(preM) =>
              val m         = preM.asInstanceOf[Map[Any, Any]]
              val oneResult = m.get(oneId).map(FetchDone(_)).getOrElse(FetchMissing())
              CombinationBarrier(() => x.result, oneResult)
                .flatMap(() => y.result)

            case FetchMissing() =>
              x.result.flatMap(() => y.result)
          }
        )

        BlockedRequest(newRequest, newResult)

      case (a: Batch[Any, Any], b: FetchOne[Any, Any]) =>
        val ds         = a.data
        val oneId      = b.id
        val combined   = combineIdentities(a, b)
        val newRequest = Batch(combined, ds)
        val newResult = CombinationSuspend((r: FetchStatus) =>
          r match {
            case FetchDone(preM) =>
              val m         = preM.asInstanceOf[Map[Any, Any]]
              val oneResult = m.get(oneId).map(FetchDone(_)).getOrElse(FetchMissing())
              CombinationBarrier(() => y.result, oneResult)
                .flatMap(() => x.result)

            case FetchMissing() =>
              x.result.flatMap(() => y.result)
          }
        )
        BlockedRequest(newRequest, newResult)

      case (a: Batch[Any, Any], b: Batch[Any, Any]) =>
        val ds         = a.data
        val combined   = combineIdentities(a, b)
        val newRequest = Batch(combined, ds)
        val newResult  = x.result.flatMap(() => y.result)
        BlockedRequest(newRequest, newResult)
    }

  /* A map from datasource identities to (data source, blocked request) pairs used to group requests to the same data source. */
  private[fetch] final case class RequestMap[F[_]](
      m: Map[Data.Identity, (DataSource[F, Any, Any], BlockedRequest[F])]
  )

  /* Combine two `RequestMap` instances to batch requests to the same data source. */
  private def combineRequestMaps[F[_]: Monad](x: RequestMap[F], y: RequestMap[F]): RequestMap[F] =
    RequestMap(
      x.m.foldLeft(y.m) { case (acc, (dsId, (ds, blocked))) =>
        val combined = acc
          .get(dsId)
          .fold(
            (ds, blocked)
          ) { case (d, req) =>
            (d, combineRequests(blocked, req))
          }
        acc.updated(dsId, combined)
      }
    )

  // Fetch result data type

  private[fetch] sealed trait FetchResult[F[_], A]    extends Product with Serializable
  private[fetch] final case class Done[F[_], A](x: A) extends FetchResult[F, A]
  private[fetch] final case class Blocked[F[_], A](rs: RequestMap[F], cont: Fetch[F, A])
      extends FetchResult[F, A]
  private[fetch] final case class Throw[F[_], A](e: Log => FetchException) extends FetchResult[F, A]

  // Fetch data type

  sealed trait Fetch[F[_], A] {
    private[fetch] def run: F[FetchResult[F, A]]
  }
  private[fetch] final case class Unfetch[F[_], A](
      private[fetch] val run: F[FetchResult[F, A]]
  ) extends Fetch[F, A]

  // Fetch Monad

  implicit def fetchM[F[_]: Monad]: Monad[Fetch[F, *]] =
    new Monad[Fetch[F, *]] with StackSafeMonad[Fetch[F, *]] {
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

      override def map2[A, B, Z](fa: Fetch[F, A], fb: Fetch[F, B])(f: (A, B) => Z): Fetch[F, Z] =
        Unfetch(for {
          fab <- (fa.run, fb.run).tupled
          result = fab match {
            case (Throw(e), _) =>
              Throw[F, Z](e)
            case (Done(a), Done(b)) =>
              Done[F, Z](f(a, b))
            case (Done(a), Blocked(br, c)) =>
              Blocked[F, Z](br, map2(fa, c)(f))
            case (Blocked(br, c), Done(b)) =>
              Blocked[F, Z](br, map2(c, fb)(f))
            case (Blocked(br, c), Blocked(br2, c2)) =>
              Blocked[F, Z](combineRequestMaps(br, br2), map2(c, c2)(f))
            case (_, Throw(e)) =>
              Throw[F, Z](e)
          }
        } yield result)

      override def map2Eval[A, B, Z](fa: Fetch[F, A], fb: Eval[Fetch[F, B]])(
          f: (A, B) => Z
      ): Eval[Fetch[F, Z]] = Eval.now(map2(fa, fb.value)(f))

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

      override def productR[A, B](fa: Fetch[F, A])(fb: Fetch[F, B]): Fetch[F, B] =
        Unfetch[F, B](for {
          fab <- (fa.run, fb.run).tupled
          result = fab match {
            case (Throw(e), _) =>
              Throw[F, B](e)
            case (Done(a), Done(b)) =>
              Done[F, B](b)
            case (Done(a), Blocked(br, c)) =>
              Blocked[F, B](br, productR(fa)(c))
            case (Blocked(br, c), Done(b)) =>
              Blocked[F, B](br, productR(c)(fb))
            case (Blocked(br, c), Blocked(br2, c2)) =>
              Blocked[F, B](combineRequestMaps(br, br2), productR(c)(c2))
            case (_, Throw(e)) =>
              Throw[F, B](e)
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
    def pure[F[_]: Applicative, A](a: A): Fetch[F, A] =
      Unfetch(Applicative[F].pure(Done(a)))

    /**
     * Given a number of fetches, returns all of the results in a `List`. In the event that multiple
     * fetches are made to the same data source, this will attempt to batch them together.
     *
     * As of 3.1.x, this is functionally equivalent to using `.sequence` syntax from Cats on any
     * data structure implementing `Traverse`.
     */
    def batchAll[F[_]: Monad, A](fetches: Fetch[F, A]*): Fetch[F, List[A]] =
      fetches.toList.sequence

    def exception[F[_]: Applicative, A](e: Log => FetchException): Fetch[F, A] =
      Unfetch(Applicative[F].pure(Throw[F, A](e)))

    def error[F[_]: Applicative, A](e: Throwable): Fetch[F, A] =
      exception(log => UnhandledException(e, log))

    def apply[F[_]: Concurrent, I, A](
        id: I,
        ds: DataSource[F, I, A]
    ): Fetch[F, A] =
      Unfetch[F, A](
        for {
          deferred <- Deferred[F, FetchStatus]
          request        = FetchOne(id, ds.data)
          result         = completeOrFail(deferred) _
          blocked        = BlockedRequest(request, CombinationLeaf(result))
          anyDs          = ds.asInstanceOf[DataSource[F, Any, Any]]
          blockedRequest = RequestMap(Map(ds.data.identity -> (anyDs, blocked)))
        } yield Blocked(
          blockedRequest,
          Unfetch[F, A](
            deferred.get.map {
              case FetchDone(a) =>
                Done(a.asInstanceOf[A])
              case FetchMissing() =>
                Throw(log => MissingIdentity(id, request, log))
            }
          )
        )
      )

    def optional[F[_]: Concurrent, I, A](
        id: I,
        ds: DataSource[F, I, A]
    ): Fetch[F, Option[A]] =
      Unfetch[F, Option[A]](
        for {
          deferred <- Deferred[F, FetchStatus]
          request        = FetchOne(id, ds.data)
          result         = completeOrFail(deferred) _
          blocked        = BlockedRequest(request, CombinationLeaf(result))
          anyDs          = ds.asInstanceOf[DataSource[F, Any, Any]]
          blockedRequest = RequestMap(Map(ds.data.identity -> (anyDs, blocked)))
        } yield Blocked(
          blockedRequest,
          Unfetch[F, Option[A]](
            deferred.get.map {
              case FetchDone(a) =>
                Done(Some(a.asInstanceOf[A]))
              case FetchMissing() =>
                Done(Option.empty[A])
            }
          )
        )
      )

    private[this] def completeOrFail[F[_], A](
        deferred: Deferred[F, A]
    )(value: A)(implicit F: MonadThrow[F]) =
      deferred.complete(value).flatMap { wasSet =>
        F.unlessA(wasSet)(
          F.raiseError(
            new IllegalStateException("Attempted to complete an already-complete Deferred")
          )
        )
      }

    def liftIO[F[_]: ApplicativeThrow: LiftIO, A](io: IO[A]): Fetch[F, A] =
      liftF(LiftIO[F].liftIO(io))

    def liftF[F[_]: ApplicativeThrow, A](f: F[A]): Fetch[F, A] =
      Unfetch[F, A](
        f.attempt.map {
          case Left(err) => Throw[F, A](log => UnhandledException(err, log))
          case Right(r)  => Done[F, A](r)
        }
      )

    // Running a Fetch

    /**
     * Run a `Fetch`, the result in the `F` monad.
     */
    def run[F[_]]: FetchRunner[F] = new FetchRunner[F]

    private[fetch] class FetchRunner[F[_]](private val dummy: Boolean = true) extends AnyVal {
      def apply[A](
          fa: Fetch[F, A]
      )(implicit
          C: Concurrent[F],
          T: Clock[F]
      ): F[A] =
        apply(fa, InMemoryCache.empty[F])

      def apply[A](
          fa: Fetch[F, A],
          cache: DataCache[F]
      )(implicit
          C: Concurrent[F],
          T: Clock[F]
      ): F[A] =
        for {
          cache  <- ref[F, DataCache[F]](cache)
          result <- performRun(fa, cache, None)
        } yield result
    }

    /**
     * Run a `Fetch`, the log and the result in the `F` monad.
     */
    def runLog[F[_]]: FetchRunnerLog[F] = new FetchRunnerLog[F]

    private[fetch] class FetchRunnerLog[F[_]](private val dummy: Boolean = true) extends AnyVal {
      def apply[A](
          fa: Fetch[F, A]
      )(implicit
          C: Concurrent[F],
          T: Clock[F]
      ): F[(Log, A)] =
        apply(fa, InMemoryCache.empty[F])

      def apply[A](
          fa: Fetch[F, A],
          cache: DataCache[F]
      )(implicit
          C: Concurrent[F],
          T: Clock[F]
      ): F[(Log, A)] =
        for {
          logAndCache <- (ref[F, Log](FetchLog()), ref[F, DataCache[F]](cache)).tupled
          (log, cache) = logAndCache
          result <- performRun(fa, cache, Some(log))
          e      <- log.get
        } yield (e, result)
    }

    /**
     * Run a `Fetch`, the cache and the result in the `F` monad.
     */
    def runCache[F[_]]: FetchRunnerCache[F] = new FetchRunnerCache[F]

    private[fetch] class FetchRunnerCache[F[_]](private val dummy: Boolean = true) extends AnyVal {
      def apply[A](
          fa: Fetch[F, A]
      )(implicit
          C: Concurrent[F],
          T: Clock[F]
      ): F[(DataCache[F], A)] =
        apply(fa, InMemoryCache.empty[F])

      def apply[A](
          fa: Fetch[F, A],
          cache: DataCache[F]
      )(implicit
          C: Concurrent[F],
          T: Clock[F]
      ): F[(DataCache[F], A)] =
        for {
          cache  <- ref[F, DataCache[F]](cache)
          result <- performRun(fa, cache, None)
          c      <- cache.get
        } yield (c, result)
    }

    /**
     * Run a `Fetch` getting the log, cache and result in the `F` monad.
     */
    def runAll[F[_]]: FetchRunnerAll[F] = new FetchRunnerAll[F]

    private[fetch] class FetchRunnerAll[F[_]](private val dummy: Boolean = true) extends AnyVal {
      def apply[A](
          fa: Fetch[F, A]
      )(implicit
          C: Concurrent[F],
          T: Clock[F]
      ): F[(Log, DataCache[F], A)] =
        apply(fa, InMemoryCache.empty[F])

      def apply[A](
          fa: Fetch[F, A],
          cache: DataCache[F]
      )(implicit
          C: Concurrent[F],
          T: Clock[F]
      ): F[(Log, DataCache[F], A)] =
        for {
          logAndCache <- (ref[F, Log](FetchLog()), ref[F, DataCache[F]](cache)).tupled
          (log, cache) = logAndCache
          result <- performRun(fa, cache, Some(log))
          eAndC  <- (log.get, cache.get).tupled
          (e, c) = eAndC
        } yield (e, c, result)
    }

    private def ref[F[_]: Ref.Make, A](a: A): F[Ref[F, A]] =
      Ref.of[F, A](a)

    // Data fetching

    private def performRun[F[_], A](
        fa: Fetch[F, A],
        cache: Ref[F, DataCache[F]],
        log: Option[Ref[F, Log]]
    )(implicit
        C: Concurrent[F],
        T: Clock[F]
    ): F[A] =
      for {
        result <- fa.run

        value <- result match {
          case Done(a) => Applicative[F].pure(a)
          case Blocked(rs, cont) =>
            for {
              _      <- fetchRound(rs, cache, log)
              result <- performRun(cont, cache, log)
            } yield result
          case Throw(logToThrowable) =>
            log
              .fold(
                Applicative[F].pure(FetchLog(): Log)
              )(_.get)
              .flatMap((e: Log) => C.raiseError[A](logToThrowable(e)))
        }
      } yield value

    private def fetchRound[F[_], A](
        rs: RequestMap[F],
        cache: Ref[F, DataCache[F]],
        log: Option[Ref[F, Log]]
    )(implicit
        C: Concurrent[F],
        T: Clock[F]
    ): F[Unit] = {
      val blocked = rs.m.toList.map(_._2)
      if (blocked.isEmpty) Applicative[F].unit
      else
        for {
          requests <- FetchExecution.parallel(
            NonEmptyList
              .fromListUnsafe(blocked)
              .map { case (ds, req) =>
                runBlockedRequest(req, ds, cache, log)
              }
          )
          performedRequests = requests.foldLeft(List.empty[Request])(_ ++ _)
          _ <-
            if (performedRequests.isEmpty) Applicative[F].unit
            else
              log match {
                case Some(l) =>
                  l.modify((oldE) => (oldE.append(Round(performedRequests)), oldE)).void
                case None => Applicative[F].unit
              }
        } yield ()
    }

    private def runBlockedRequest[F[_], A](
        blocked: BlockedRequest[F],
        ds: DataSource[F, Any, Any],
        cache: Ref[F, DataCache[F]],
        log: Option[Ref[F, Log]]
    )(implicit
        C: Concurrent[F],
        T: Clock[F]
    ): F[List[Request]] =
      blocked.request match {
        case q: FetchOne[Any, Any] => runFetchOne[F](q, ds, blocked.result, cache, log)
        case q: Batch[Any, Any]    => runBatch[F](q, ds, blocked.result, cache, log)
      }
  }

  private def runFetchOne[F[_]](
      q: FetchOne[Any, Any],
      ds: DataSource[F, Any, Any],
      putResult: CombinationTailRec[F],
      cache: Ref[F, DataCache[F]],
      log: Option[Ref[F, Log]]
  )(implicit
      C: Concurrent[F],
      T: Clock[F]
  ): F[List[Request]] =
    for {
      startTime   <- T.monotonic.map(_.toMillis)
      c           <- cache.get
      maybeCached <- c.lookup(q.id, q.data)
      result <- maybeCached match {
        // Cached
        case Some(v) =>
          runCombinationResult(putResult, FetchDone(v)).as(
            List(Request(FetchOne(q.id, q.data, cached = true), startTime, startTime))
          )

        // Not cached, must fetch
        case None =>
          for {
            o       <- ds.fetch(q.id)
            endTime <- T.monotonic.map(_.toMillis)
            result <- o match {
              // Fetched
              case Some(a) =>
                for {
                  newC   <- c.insert(q.id, a, q.data)
                  _      <- cache.set(newC)
                  result <- runCombinationResult(putResult, FetchDone[Any](a))
                } yield List(Request(q, startTime, endTime))

              // Missing
              case None =>
                runCombinationResult(putResult, FetchMissing())
                  .as(List(Request(q, startTime, endTime)))
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
      ds: DataSource[F, Any, Any],
      putResult: CombinationTailRec[F],
      cache: Ref[F, DataCache[F]],
      log: Option[Ref[F, Log]]
  )(implicit
      C: Concurrent[F],
      T: Clock[F]
  ): F[List[Request]] =
    for {
      c <- cache.get

      // Remove cached IDs
      idLookups <- q.ids.traverse[F, (Any, Option[Any])]((i) => c.lookup(i, q.data).tupleLeft(i))
      (uncachedIds, cached) = idLookups.toList.partitionEither { case (i, result) =>
        result.tupleLeft(i).toRight(i)
      }
      cachedResults = cached.toMap
      startTime <- T.monotonic.map(_.toMillis)
      cachedRequests = cached.toNel.map(x =>
        Request(Batch(x.map(_._1), q.data, cached = true), startTime, startTime)
      )
      result <- uncachedIds.toNel match {
        // All cached
        case None =>
          runCombinationResult(putResult, FetchDone[Map[Any, Any]](cachedResults)).as(Nil)

        // Some uncached
        case Some(uncached) =>
          val request = Batch[Any, Any](uncached, q.data)
          for {
            batchedRequest <- ds.maxBatchSize match {
              // Unbatched
              case None =>
                ds.batch(uncached).map(BatchedRequest(List(request), _))

              // Batched
              case Some(batchSize) =>
                runBatchedRequest[F](request, ds, batchSize, ds.batchExecution)
            }

            endTime <- T.monotonic.map(_.toMillis)
            resultMap = combineBatchResults(batchedRequest.results, cachedResults)

            updatedCache <- c.bulkInsert(batchedRequest.results.toList, q.data)
            _            <- cache.set(updatedCache)

            result <- runCombinationResult(putResult, FetchDone[Map[Any, Any]](resultMap))

          } yield batchedRequest.batches.map(Request(_, startTime, endTime))
      }
    } yield result ++ cachedRequests.toList

  private def runBatchedRequest[F[_]](
      q: Batch[Any, Any],
      ds: DataSource[F, Any, Any],
      batchSize: Int,
      e: BatchExecution
  )(implicit
      C: Concurrent[F],
      T: Clock[F]
  ): F[BatchedRequest] = {
    val batches = NonEmptyList.fromListUnsafe(
      q.ids.toList
        .grouped(batchSize)
        .map(batchIds => NonEmptyList.fromListUnsafe(batchIds))
        .toList
    )
    val reqs = batches.toList.map(Batch[Any, Any](_, q.data))

    val results = e match {
      case Sequentially =>
        batches.traverse(ds.batch)
      case InParallel =>
        FetchExecution.parallel(batches.map(ds.batch(_)))
    }

    results.map(_.toList.reduce(combineBatchResults)).map(BatchedRequest(reqs, _))
  }

  private def combineBatchResults(r: Map[Any, Any], rs: Map[Any, Any]): Map[Any, Any] =
    r ++ rs
}
