/*
 * Copyright 2016-2021 47 Degrees Open Source <https://www.47deg.com>
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
 * A log that is passed along during the fetch rounds to record a fetch execution.
 * It holds the a list of rounds that have been executed.
 */
trait Log {
  def rounds: List[Round]
  def append(round: Round): Log
}

/**
 * A data structure that holds information about a request inside a fetch round.
 */
case class Request(
    request: FetchRequest,
    start: Long,
    end: Long
) {
  def duration: Long = end - start
}

/**
 * A data structure that holds information about a fetch round.
 */
case class Round(
    queries: List[Request]
)

/**
 * A concrete implementation of `Log` used in Fetch.
 */
case class FetchLog(
    q: Queue[Round] = Queue.empty
) extends Log {
  def rounds = q.toList
  def append(round: Round): Log =
    copy(q = q :+ round)
}
