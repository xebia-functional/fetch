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

import cats.{Eval, MonadError}

/**
  * A cache that stores its elements in memory.
  */
case class InMemoryCache(state: Map[DataSourceIdentity, Any]) extends DataSourceCache {
  override def get(k: DataSourceIdentity): Option[Any] =
    state.get(k)

  override def update[A](k: DataSourceIdentity, v: A): InMemoryCache =
    copy(state = state.updated(k, v))
}

object InMemoryCache {
  def empty: InMemoryCache = InMemoryCache(Map.empty[DataSourceIdentity, Any])

  def apply(results: (DataSourceIdentity, Any)*): InMemoryCache =
    InMemoryCache(
        results.foldLeft(Map.empty[DataSourceIdentity, Any])({
      case (c, (k, v)) => c.updated(k, v)
    }))
}

object implicits {
  implicit val evalMonadError: MonadError[Eval, Throwable] = new MonadError[Eval, Throwable] {
    override def pure[A](x: A): Eval[A] = Eval.now(x)

    override def map[A, B](fa: Eval[A])(f: A ⇒ B): Eval[B] = fa.map(f)

    override def flatMap[A, B](fa: Eval[A])(ff: A => Eval[B]): Eval[B] =
      fa.flatMap(ff)

    override def raiseError[A](e: Throwable): Eval[A] =
      Eval.later({ throw e })

    override def handleErrorWith[A](fa: Eval[A])(f: Throwable ⇒ Eval[A]): Eval[A] =
      Eval.later({
        try {
          fa.value
        } catch {
          case e: Throwable => f(e).value
        }
      })
  }
}
