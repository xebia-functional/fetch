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

import cats.effect.IO
import cats.data.NonEmptyList
import cats.instances.list._
import cats.instances.option._
import cats.syntax.functor._
import cats.syntax.traverse._

/**
 * A `DataSource` is the recipe for fetching a certain identity `I`, which yields
 * results of type `A`.
 */
trait DataSource[I, A] {
  /** The name of the data source.
   */
  def name: String

  /** Fetch one identity, returning a None if it wasn't found.
   */
  def fetch(id: I): IO[Option[A]]

  /** Fetch many identities, returning a mapping from identities to results. If an
   * identity wasn't found, it won't appear in the keys.
   */
  def batch(ids: NonEmptyList[I]): IO[Map[I, A]] = {
    val fetchOneWithId: I => IO[Option[(I, A)]] = id =>
      fetch(id).map(_.tupleLeft(id))

    ids.toList.traverse(fetchOneWithId).map(_.collect { case Some(x) => x }.toMap)
  }

  def maxBatchSize: Option[Int] = None

  def batchExecution: ExecutionType = Parallel
}

sealed trait ExecutionType extends Product with Serializable
case object Sequential     extends ExecutionType
case object Parallel       extends ExecutionType
