/*
 * Copyright 2016 47 Degrees, LLC. <http://www.47deg.com>
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
import cats.std.list._
import cats.syntax.functor._
import cats.syntax.traverse._

/**
  * A `DataSource` is the recipe for fetching a certain identity `I`, which yields
  * results of type `A`.
  */
trait DataSource[I, A] {

  /** The name of the data source.
    */
  def name: DataSourceName = this.toString

  /**
    * Derive a `DataSourceIdentity` from an identity, suitable for storing the result
    * of such identity in a `DataSourceCache`.
    */
  def identity(i: I): DataSourceIdentity = (name, i)

  /** Fetch one identity, returning a None if it wasn't found.
    */
  def fetchOne(id: I): Query[Option[A]]

  /** Fetch many identities, returning a mapping from identities to results. If an
    * identity wasn't found won't appear in the keys.
    */
  def fetchMany(ids: NonEmptyList[I]): Query[Map[I, A]]

  /** Use `fetchOne` for implementing of `fetchMany`. Use only when the data
    * source doesn't support batching.
    */
  def batchingNotSupported(ids: NonEmptyList[I]): Query[Map[I, A]] = {
    val idsList = ids.unwrap
    idsList
      .map(fetchOne)
      .sequence
      .map(results => {
        (idsList zip results)
          .collect({
            case (id, Some(result)) => (id, result)
          })
          .toMap
      })
  }
}
