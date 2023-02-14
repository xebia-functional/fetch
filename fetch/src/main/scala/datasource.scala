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

import cats.data.NonEmptyList
import cats.effect._
import cats.effect.implicits._
import cats.effect.std.{Queue, Supervisor}
import cats.kernel.{Hash => H}
import cats.syntax.all._

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/**
 * `Data` is a trait used to identify and optimize access to a `DataSource`.
 */
trait Data[I, A] { self =>
  def name: String

  def identity: Data.Identity =
    H.fromUniversalHashCode.hash(self)
}

object Data {
  type Identity = Int
}

/**
 * A `DataSource` is the recipe for fetching a certain identity `I`, which yields results of type
 * `A` performing an effect of type `F[_]`.
 */
trait DataSource[F[_], I, A] {
  def data: Data[I, A]

  implicit def CF: Concurrent[F]

  /**
   * Fetch one identity, returning a None if it wasn't found.
   */
  def fetch(id: I): F[Option[A]]

  /**
   * Fetch many identities, returning a mapping from identities to results. If an identity wasn't
   * found, it won't appear in the keys.
   */
  def batch(ids: NonEmptyList[I]): F[Map[I, A]] =
    FetchExecution
      .parallel(
        ids.map(id => fetch(id).tupleLeft(id))
      )
      .map(_.collect { case (id, Some(x)) => id -> x }.toMap)

  def maxBatchSize: Option[Int] = None

  def batchExecution: BatchExecution = InParallel
}

object DataSource {
  private def upToWithin[F[_], T](queue: Queue[F, T], maxElements: Int, interval: FiniteDuration)(
      implicit F: Temporal[F]
  ): F[List[T]] = {
    Ref[F].of(List.empty[T]).flatMap { ref =>
      val takeAndBuffer = F.uncancelable { poll =>
        poll(queue.take).flatMap { x =>
          ref.updateAndGet(list => x :: list)
        }
      }
      val bufferUntilNumElements = takeAndBuffer.iterateUntil { buffer =>
        buffer.size == maxElements
      }
      F.timeoutTo(bufferUntilNumElements, interval, ref.get)
    }
  }

  /**
   * Returns a new DataSource that will batch Fetch requests across executions within a given
   * interval.
   *
   * As an example, if we have a Fetch request A, and a fetch request B that are being executed
   * simultaneously without knowledge of the other within some milliseconds of the other, the
   * datasource will transparently batch the two requests in a single batch call execution.
   *
   * This is useful if you want to treat each fetch individually from the others, for example in an
   * HTTP server processing requests.
   *
   * The original DataSource limits will be respected
   *
   * @param dataSource
   *   the original datasource to be wrapped
   * @param delayPerBatch
   *   the interval for processing Fetch requests as a single Batch call
   * @return
   */
  def batchAcrossFetches[F[_], I, A](
      dataSource: DataSource[F, I, A],
      delayPerBatch: FiniteDuration
  )(implicit
      F: Async[F]
  ): Resource[F, DataSource[F, I, A]] = {
    type Callback = Either[Throwable, Option[A]] => Unit
    for {
      queue      <- Resource.eval(Queue.unbounded[F, (I, Callback)])
      supervisor <- Supervisor[F]
      workerFiber = upToWithin(
        queue,
        dataSource.maxBatchSize.getOrElse(Int.MaxValue),
        delayPerBatch
      ).flatMap { x =>
        if (x.isEmpty) {
          supervisor.supervise(F.unit)
        } else {
          val asMap        = x.groupBy(_._1).mapValues(callbacks => callbacks.map(_._2))
          val batchResults = dataSource.batch(NonEmptyList.fromListUnsafe(asMap.keys.toList))
          val resultsHaveBeenSent = batchResults.map { results =>
            asMap.foreach { case (identity, callbacks) =>
              callbacks.foreach(cb => cb(Right(results.get(identity))))
            }
          }
          val fiberWork = F.handleError(resultsHaveBeenSent) { ex =>
            asMap.foreach { case (_, callbacks) =>
              callbacks.foreach(cb => cb(Left(ex)))
            }
          }
          supervisor.supervise(fiberWork)
        }
      }.foreverM[Unit]
      _ <- F.background(workerFiber)
    } yield {
      new DataSource[F, I, A] {
        override def data: Data[I, A] = dataSource.data

        override implicit def CF: Concurrent[F] = dataSource.CF

        override def fetch(id: I): F[Option[A]] = {
          F.async { cb =>
            queue.offer((id, cb)) *> F.pure(None)
          }
        }
      }
    }
  }
}

sealed trait BatchExecution extends Product with Serializable
case object Sequentially    extends BatchExecution
case object InParallel      extends BatchExecution
