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

final class DataSourceName(val name: String) extends AnyVal
final class DataSourceId(val id: Any) extends AnyVal
final class DataSourceResult(val result: Any) extends AnyVal

/**
 * A `Cache` trait so the users of the library can provide their own cache.
 */
trait DataSourceCache[F[_]] {
  def lookup[I, A](i: I, ds: DataSource[F, I, A]): F[Option[A]]
  def insert[I, A](i: I, v: A, ds: DataSource[F, I, A]): F[DataSourceCache[F]]
  def insertMany[I, A](vs: Map[I, A], ds: DataSource[F, I, A]): F[DataSourceCache[F]]

}

abstract class ManyDataSourceCache[F[_]: Monad] extends DataSourceCache[F] { self =>
  override def insertMany[I, A](vs: Map[I, A], ds: DataSource[F, I, A]): F[DataSourceCache[F]] =
    vs.toList.foldLeftM[F, DataSourceCache[F]](self)({
      case (c, (i, v)) => c.insert(i, v, ds)
    })
}

/**
 * A cache that stores its elements in memory.
 */
sealed abstract class InMemoryCache[F[_]](
  private[InMemoryCache] val state: Map[(DataSourceName, DataSourceId), DataSourceResult]
) extends DataSourceCache[F]

object InMemoryCache {
  def empty[F[_]: Monad]: InMemoryCache[F] =
    apply(Map.empty)

  def from[F[_]: Monad , I, A](results: ((String, I), A)*): InMemoryCache[F] =
    apply(results.foldLeft(Map.empty[(DataSourceName, DataSourceId), DataSourceResult])({
      case (acc, ((s, i), v)) => acc.updated((new DataSourceName(s), new DataSourceId(i)), new DataSourceResult(v))
    }))

  private def apply[F[_]: Monad](
    map: Map[(DataSourceName, DataSourceId), DataSourceResult]
  ): InMemoryCache[F] =
    new InMemoryCache[F](map) {
      def lookup[I, A](i: I, ds: DataSource[F, I, A]): F[Option[A]] =
        Applicative[F].pure(state.get((new DataSourceName(ds.name), new DataSourceId(i))).map(_.result.asInstanceOf[A]))

      def insert[I, A](i: I, v: A, ds: DataSource[F, I, A]): F[DataSourceCache[F]] =
        Applicative[F].pure(apply(state.updated((new DataSourceName(ds.name), new DataSourceId(i)), new DataSourceResult(v))))

      def insertMany[I, A](vs: Map[I, A], ds: DataSource[F, I, A]): F[DataSourceCache[F]] =
        vs.toList.foldLeftM[F, DataSourceCache[F]](this)({
          case (c, (i, v)) => c.insert(i, v, ds)
        })
    }

  implicit def inMemoryCacheMonoid[F[_]: Monad]: Monoid[InMemoryCache[F]] = {
    implicit val anySemigroup = new Semigroup[Any] {
      def combine(a: Any, b: Any): Any = b
    }
    new Monoid[InMemoryCache[F]] {
      def empty: InMemoryCache[F] = InMemoryCache.empty
      def combine(c1: InMemoryCache[F], c2: InMemoryCache[F]): InMemoryCache[F] =
        InMemoryCache(c1.state ++ c2.state)
    }
  }
}
