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
import cats.effect._
import cats.data.NonEmptyList
import cats.instances.list._
import cats.syntax.all._

final class DataSourceName(val name: String) extends AnyVal
final class DataSourceId(val id: Any) extends AnyVal
final class DataSourceResult(val result: Any) extends AnyVal

/**
 * A `Cache` trait so the users of the library can provide their own cache.
 */
trait DataSourceCache[F[_]] {
  def lookup[I, A](i: I, ds: DataSource[I, A]): F[Option[A]]

  def insert[I, A](i: I, v: A, ds: DataSource[I, A]): F[DataSourceCache[F]]

  // def delete[I, A](i: I, v: A, ds: DataSource[I, A]): F[Unit]

  def bulkInsert[I, A](vs: List[(I, A)], ds: DataSource[I, A])(
    implicit M: Monad[F]
  ): F[DataSourceCache[F]] = {
    vs.foldLeftM(this){
      case (acc, (i, v)) =>
        acc.insert(i, v, ds)
    }
  }
}

/**
 * A cache that stores its elements in memory.
 */
case class InMemoryCache[F[_] : Monad](state: Map[(DataSourceName, DataSourceId), DataSourceResult]) extends DataSourceCache[F] {
  def lookup[I, A](i: I, ds: DataSource[I, A]): F[Option[A]] =
    Applicative[F].pure(state.get((new DataSourceName(ds.name), new DataSourceId(i))).map(_.result.asInstanceOf[A]))

  def insert[I, A](i: I, v: A, ds: DataSource[I, A]): F[DataSourceCache[F]] =
    Applicative[F].pure(copy(state = state.updated((new DataSourceName(ds.name), new DataSourceId(i)), new DataSourceResult(v))))
}

object InMemoryCache {
  def empty[F[_] : Monad]: InMemoryCache[F] = InMemoryCache[F](Map.empty[(DataSourceName, DataSourceId), DataSourceResult])

  def from[F[_]: Monad, I, A](results: ((String, I), A)*): InMemoryCache[F] =
    InMemoryCache[F](results.foldLeft(Map.empty[(DataSourceName, DataSourceId), DataSourceResult])({
      case (acc, ((s, i), v)) => acc.updated((new DataSourceName(s), new DataSourceId(i)), new DataSourceResult(v))
    }))
}
