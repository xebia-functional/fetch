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

import cats.effect._

/**
 * A `Cache` trait so the users of the library can provide their own cache.
 */
trait DataSourceCache {
  def lookup[I, A](i: I, ds: DataSource[I, A]): IO[Option[FetchStatus]]
  def insert[I, A](i: I, ds: DataSource[I, A], v: FetchStatus): IO[DataSourceCache]
}

/**
 * A cache that stores its elements in memory.
 */
case class InMemoryCache(state: Map[(String, Any), FetchStatus]) extends DataSourceCache {
  def lookup[I, A](i: I, ds: DataSource[I, A]): IO[Option[FetchStatus]] =
    IO.pure(state.get((ds.name, i)).asInstanceOf[Option[FetchStatus]])

  def insert[I, A](i: I, ds: DataSource[I, A], v: FetchStatus): IO[DataSourceCache] =
    IO.pure(copy(state = state.updated((ds.name, i), v)))
}

object InMemoryCache {
  def empty: InMemoryCache = InMemoryCache(Map.empty[(String, Any), FetchStatus])

  def from[I, A](results: ((String, I), A)*): InMemoryCache =
    InMemoryCache(results.foldLeft(Map.empty[(String, Any), FetchStatus])({
      case (acc, ((s, i), v)) => acc.updated((s, i), FetchDone(v))
    }))
}
