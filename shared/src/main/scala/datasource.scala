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

import cats.{Functor, Monad}
import cats.effect._
import cats.data.NonEmptyList
import cats.instances.list._
import cats.instances.option._
import cats.syntax.all._

/**
 * A `DataSource` is the recipe for fetching a certain identity `I`, which yields
 * results of type `A` performing an effect of type `F[_]`.
 */
trait DataSource[F[_], I, A] {
  /** The name of the data source.
   */
  def name: String

  /** Fetch one identity, returning a None if it wasn't found.
   */
  def fetch(id: I)(implicit C: ConcurrentEffect[F]): F[Option[A]]

  /** Fetch many identities, returning a mapping from identities to results. If an
   * identity wasn't found, it won't appear in the keys.
   */
  def batch(ids: NonEmptyList[I])(implicit C: ConcurrentEffect[F]): F[Map[I, A]] =
    FetchExecution.parallel(
      ids.map(id => fetch(id).map((v) => id -> v))
    ).map(_.collect({ case (id, Some(x)) => id -> x }).toMap)

  def maxBatchSize: Option[Int] = None

  def batchExecution: BatchExecution = InParallel
}

sealed trait BatchExecution extends Product with Serializable
case object Sequentially extends BatchExecution
case object InParallel   extends BatchExecution
