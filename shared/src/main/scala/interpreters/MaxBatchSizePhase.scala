/*
 * Copyright 2016-2017 47 Degrees, LLC. <http://www.47deg.com>
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
package interpreters

import scala.collection.immutable._

import cats.{~>, Applicative, Semigroup}
import cats.data.{Ior, NonEmptyList}
import cats.free.Free
import cats.implicits._

object MaxBatchSizePhase {

  val apply: FetchOp ~> Fetch =
    λ[FetchOp ~> Fetch] {
      case many @ FetchMany(_, _) => batchMany(many)
      case conc @ Concurrent(_)   => batchConcurrent(conc)
      case op                     => Free.liftF(op)
    }

  private[this] def manyInBatches[I, A](many: FetchMany[I, A]): NonEmptyList[FetchMany[I, A]] = {
    val FetchMany(ids, ds) = many
    ds.maxBatchSize.fold(NonEmptyList.of(many)) { batchSize =>
      ids.unsafeListOp {
        _.grouped(batchSize)
          .map(batchIds => FetchMany[I, A](NonEmptyList.fromListUnsafe(batchIds), ds))
          .toList
      }
    }
  }

  private[this] def batchMany[I, A](many: FetchMany[I, A]): Fetch[List[A]] = {
    val batchedFetches = manyInBatches(many)
    many.ds.batchExecution match {
      case _ if many.ds.maxBatchSize.isEmpty =>
        Free.liftF(many)
      case Sequential =>
        batchedFetches.reduceMapM[Fetch, List[A]](Free.liftF(_))
      case Parallel =>
        val queries = batchedFetches.asInstanceOf[NonEmptyList[FetchQuery[Any, Any]]]
        Free.liftF(Concurrent(queries)).map { results =>
          many.ids.toList.mapFilter(id => results.get(many.ds.identity(id)))
        }
    }
  }

  private[this] def batchConcurrent(conc: Concurrent): Fetch[InMemoryCache] = {
    type Batch = NonEmptyList[FetchQuery[Any, Any]]
    val Concurrent(fetches) = conc

    val parIorSeqBatches: Ior[Batch, NonEmptyList[Batch]] =
      fetches.reduceMap {
        case many @ FetchMany(_, ds) =>
          val batches = manyInBatches(many)
          many.ds.batchExecution match {
            case Parallel   => Ior.left(batches)
            case Sequential => Ior.right(NonEmptyList.of(batches))
          }
        case other =>
          Ior.left(NonEmptyList.of(other))
      }

    val batches: NonEmptyList[Batch] =
      parIorSeqBatches.map(transposeNelsUnequalLengths) match {
        case Ior.Left(par)       => NonEmptyList.of(par)
        case Ior.Right(seqs)     => seqs
        case Ior.Both(par, seqs) => NonEmptyList(par <+> seqs.head, seqs.tail)
      }

    batches.map(Concurrent(_)).reduceMapM[Fetch, InMemoryCache](Free.liftF(_))
  }

  private[fetch] def transposeNelsUnequalLengths[A](
      nestedNel: NonEmptyList[NonEmptyList[A]]
  ): NonEmptyList[NonEmptyList[A]] = {
    type NEL[A] = NonEmptyList[A]
    // return one transposed line and the rest of the still untransposed lines
    def oneLine(nelnel: NEL[NEL[A]]): (NEL[A], List[NEL[A]]) =
      nelnel.reduceMap(nel => (NonEmptyList.of(nel.head), nel.tail.toNel.toList))

    // keep transposing until oneLine returns an empty list as "rest lines"
    // List[NEL[A]] == Option[NEL[NEL[A]]]
    unfoldNonEmpty[NEL, NEL[A], NEL[NEL[A]]](nestedNel) { nel =>
      val (line, rest) = oneLine(nel)
      (line, rest.toNel)
    }
  }

  private[fetch] def unfoldNonEmpty[F[_], A, B](seed: B)(
      f: B => (A, Option[B]))(implicit F: Applicative[F], S: Semigroup[F[A]]): F[A] = {
    def loop(seed: B)(xs: F[A]): F[A] = f(seed) match {
      case (a, Some(b)) => loop(b)(S.combine(xs, F.pure(a)))
      case (a, None)    => S.combine(xs, F.pure(a))
    }

    f(seed) match {
      case (a, Some(b)) => loop(b)(F.pure(a))
      case (a, None)    => F.pure(a)
    }
  }
}
