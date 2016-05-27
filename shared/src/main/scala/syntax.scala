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

import monix.eval.Task

object syntax {

  /** Implicit syntax to lift any value to the context of Fetch via pure */
  implicit class FetchIdSyntax[A](val a: A) extends AnyVal {

    def fetch: Fetch[A] =
      Fetch.pure(a)
  }

  /** Implicit syntax to lift exception to Fetch errors */
  implicit class FetchErrorSyntax[A <: Throwable](val a: A) extends AnyVal {

    def fetch[B]: Fetch[B] =
      Fetch.error[B](a)
  }

  /** Implicit syntax for Fetch ops in Free based Fetches */
  implicit class FetchSyntax[A](val fa: Fetch[A]) extends AnyVal {

    def join[B](fb: Fetch[B]): Fetch[(A, B)] =
      Fetch.join(fa, fb)

    def runF: Task[(FetchEnv, A)] =
      Fetch.runFetch(fa, InMemoryCache.empty)

    def runE: Task[FetchEnv] =
      Fetch.runEnv(fa, InMemoryCache.empty)

    def runA: Task[A] =
      Fetch.run(fa, InMemoryCache.empty)

    def runF(cache: DataSourceCache): Task[(FetchEnv, A)] =
      Fetch.runFetch(fa, cache)

    def runE(cache: DataSourceCache): Task[FetchEnv] =
      Fetch.runEnv(fa, cache)

    def runA(cache: DataSourceCache): Task[A] =
      Fetch.run(fa, cache)
  }
}
