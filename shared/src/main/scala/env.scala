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
) extends Env {
  def next(
    newCache: DataSourceCache,
    newRound: Round,
    newIds: List[Any]
  ): FetchEnv =
    copy(cache = newCache, rounds = rounds :+ newRound, ids = newIds)
}

