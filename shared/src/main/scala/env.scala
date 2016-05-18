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

import scala.collection.immutable._

/**
  * An environment that is passed along during the fetch rounds. It holds the
  * cache and the list of rounds that have been executed.
  */
trait Env {
  def cache: DataSourceCache
  def rounds: Seq[Round]

  def cached: Seq[Round] =
    rounds.filter(_.cached)

  def uncached: Seq[Round] =
    rounds.filterNot(_.cached)

  def next(
      newCache: DataSourceCache,
      newRound: Round,
      newIds: List[Any]
  ): Env
}

/**
  * A data structure that holds information about a fetch round.
  */
case class Round(
    cache: DataSourceCache,
    ds: DataSourceName,
    kind: RoundKind,
    startRound: Long,
    endRound: Long,
    cached: Boolean = false
) {
  def duration: Double = (endRound - startRound) / 1e6

  def isConcurrent: Boolean = kind match {
    case ConcurrentRound(_) => true
    case _ => false
  }
}

sealed trait RoundKind
final case class OneRound(id: Any) extends RoundKind
final case class ManyRound(ids: List[Any]) extends RoundKind
final case class ConcurrentRound(ids: Map[String, List[Any]]) extends RoundKind

/**
  * A concrete implementation of `Env` used in the default Fetch interpreter.
  */
case class FetchEnv(
    cache: DataSourceCache,
    ids: List[Any] = Nil,
    rounds: Queue[Round] = Queue.empty
)
    extends Env {
  def next(
      newCache: DataSourceCache,
      newRound: Round,
      newIds: List[Any]
  ): FetchEnv =
    copy(cache = newCache, rounds = rounds :+ newRound, ids = newIds)
}
