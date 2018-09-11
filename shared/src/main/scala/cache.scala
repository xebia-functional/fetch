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

import cats._
import cats.instances.list._
import cats.syntax.all._
import cats.effect._

final class DataSourceName(val name: String) extends AnyVal
final class DataSourceId(val id: Any) extends AnyVal
final class DataSourceResult(val result: Any) extends AnyVal

/**
 * A `Cache` trait so the users of the library can provide their own cache.
 */
trait DataSourceCache {
  def lookup[F[_] : ConcurrentEffect, I, A](i: I, ds: DataSource[I, A]): F[Option[A]]
  def insert[F[_] : ConcurrentEffect, I, A](i: I, v: A, ds: DataSource[I, A]): F[DataSourceCache]
  def insertMany[F[_]: ConcurrentEffect, I, A](vs: Map[I, A], ds: DataSource[I, A]): F[DataSourceCache] =
    vs.toList.foldLeftM(this)({
      case (c, (i, v)) => c.insert(i, v, ds)
    })
}

/**
 * A cache that stores its elements in memory.
 */
case class InMemoryCache(state: Map[(DataSourceName, DataSourceId), DataSourceResult]) extends DataSourceCache {
  def lookup[F[_] : ConcurrentEffect, I, A](i: I, ds: DataSource[I, A]): F[Option[A]] =
    Applicative[F].pure(state.get((new DataSourceName(ds.name), new DataSourceId(i))).map(_.result.asInstanceOf[A]))

  def insert[F[_] : ConcurrentEffect, I, A](i: I, v: A, ds: DataSource[I, A]): F[DataSourceCache] =
    Applicative[F].pure(copy(state = state.updated((new DataSourceName(ds.name), new DataSourceId(i)), new DataSourceResult(v))))
}

object InMemoryCache {
  def empty: InMemoryCache = InMemoryCache(Map.empty[(DataSourceName, DataSourceId), DataSourceResult])

  def from[I, A](results: ((String, I), A)*): InMemoryCache =
    InMemoryCache(results.foldLeft(Map.empty[(DataSourceName, DataSourceId), DataSourceResult])({
      case (acc, ((s, i), v)) => acc.updated((new DataSourceName(s), new DataSourceId(i)), new DataSourceResult(v))
    }))

  implicit val inMemoryCacheMonoid: Monoid[InMemoryCache] = {
    implicit val anySemigroup = new Semigroup[Any] {
      def combine(a: Any, b: Any): Any = b
    }
    new Monoid[InMemoryCache] {
      def empty: InMemoryCache = InMemoryCache.empty
      def combine(c1: InMemoryCache, c2: InMemoryCache): InMemoryCache =
        InMemoryCache(c1.state ++ c2.state)
    }
  }
}
