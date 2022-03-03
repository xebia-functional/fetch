/*
 * Copyright 2016-2022 47 Degrees Open Source <https://www.47deg.com>
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
import cats.effect._
import cats.data.NonEmptyList
import cats.instances.list._
import cats.syntax.all._

final class DataSourceId(val id: Any)         extends AnyVal
final class DataSourceResult(val result: Any) extends AnyVal

/**
 * A `Cache` trait so the users of the library can provide their own cache.
 */
trait DataCache[F[_]] {
  def lookup[I, A](i: I, data: Data[I, A]): F[Option[A]]

  def insert[I, A](i: I, v: A, data: Data[I, A]): F[DataCache[F]]

  def bulkInsert[I, A](vs: List[(I, A)], data: Data[I, A])(implicit
      M: Monad[F]
  ): F[DataCache[F]] = {
    vs.foldLeftM(this) { case (acc, (i, v)) =>
      acc.insert(i, v, data)
    }
  }
}

/**
 * A cache that stores its elements in memory.
 */
case class InMemoryCache[F[_]: Monad](state: Map[(Data[Any, Any], DataSourceId), DataSourceResult])
    extends DataCache[F] {
  def lookup[I, A](i: I, data: Data[I, A]): F[Option[A]] =
    Applicative[F].pure(
      state
        .get((data.asInstanceOf[Data[Any, Any]], new DataSourceId(i)))
        .map(_.result.asInstanceOf[A])
    )

  def insert[I, A](i: I, v: A, data: Data[I, A]): F[DataCache[F]] =
    Applicative[F].pure(
      copy(state =
        state.updated(
          (data.asInstanceOf[Data[Any, Any]], new DataSourceId(i)),
          new DataSourceResult(v)
        )
      )
    )
}

object InMemoryCache {
  def empty[F[_]: Monad]: InMemoryCache[F] =
    InMemoryCache[F](Map.empty[(Data[Any, Any], DataSourceId), DataSourceResult])

  def from[F[_]: Monad, I, A](results: ((Data[I, A], I), A)*): InMemoryCache[F] =
    InMemoryCache[F](results.foldLeft(Map.empty[(Data[Any, Any], DataSourceId), DataSourceResult]) {
      case (acc, ((data, i), v)) =>
        acc.updated(
          (data.asInstanceOf[Data[Any, Any]], new DataSourceId(i)),
          new DataSourceResult(v)
        )
    })
}
