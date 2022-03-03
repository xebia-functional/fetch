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

import cats.instances.all._
import cats.syntax.all._

object debug {
  import fetch.document.Document

  def string(doc: Document): String = {
    val writer = new java.io.StringWriter
    doc.format(1, writer)
    writer.toString
  }

  def pile(docs: Seq[Document]): Document =
    docs.foldLeft(Document.empty: Document)(_ :/: _)

  def showDuration(millis: Long): Document = {
    val secs = millis / 1e3
    Document.text(f" 🕛 $secs%1.2f seconds")
  }

  def firstRequest(r: Round): Option[Long] =
    for {
      aQuery <- r.queries.headOption
      firstR = r.queries.foldLeft(aQuery.start) { case (acc, q) =>
        acc min q.start
      }
    } yield firstR

  def lastRequest(r: Round): Option[Long] =
    for {
      aQuery <- r.queries.headOption
      lastR = r.queries.foldLeft(aQuery.end) { case (acc, q) =>
        acc max q.end
      }
    } yield lastR

  def showLog(log: Log): Document =
    log.rounds match {
      case Nil => Document.empty
      case _ =>
        val duration: Option[Long] = for {
          firstRound        <- log.rounds.headOption
          firstRequestStart <- firstRequest(firstRound)
          lastRound         <- log.rounds.lastOption
          lastRequestEnd    <- lastRequest(lastRound)
        } yield lastRequestEnd - firstRequestStart
        val durationDoc =
          duration.fold(Document.empty: Document)((d: Long) =>
            Document.text("Fetch execution") :-: showDuration(d)
          )

        durationDoc :/: Document.nest(
          2,
          pile(log.rounds.mapWithIndex((r, i) => showRound(r, i + 1)))
        )
    }

  def showRound(r: Round, n: Int): Document = {
    val roundDuration = for {
      f <- firstRequest(r)
      l <- lastRequest(r)
    } yield l - f

    val round =
      Document.text(s"[Round $n]") :-: roundDuration.fold(Document.text(""))(showDuration(_))

    round :-: Document.nest(
      2,
      pile(r.queries.map(showRequest))
    )
  }

  def showRequest(r: Request): Document =
    r.request match {
      case FetchOne(id, d) =>
        Document.text(s"[Fetch one] From `${d.name}` with id $id") :-: showDuration(r.duration)
      case Batch(ids, d) =>
        Document.text(s"[Batch] From `${d.name}` with ids ${ids.toList}") :-: showDuration(
          r.duration
        )
    }

  def showMissing(d: Data[_, _], ids: List[_]): Document =
    Document.text(s"`${d.name}` missing identities $ids")

  def showRoundCount(err: FetchException): Document =
    Document.text(s", fetch interrupted after ${err.log.rounds.size} rounds")

  def showException(err: FetchException): Document =
    err match {
      case MissingIdentity(id, q, log) =>
        Document
          .text(
            s"[ERROR] Identity with id `$id` for data source `${q.data.name}` not found"
          ) :-: showRoundCount(
          err
        )
      case UnhandledException(exc, log) =>
        Document
          .text(
            s"[ERROR] Unhandled `${exc.getClass.getName}`: '${exc.getMessage}'"
          ) :-: showRoundCount(
          err
        )
    }

  /* Given a [[fetch.env.Log]], describe it with a human-readable string. */
  def describe(log: Log): String =
    string(showLog(log))

  /* Given a [[Throwable]], describe it with a human-readable string. */
  def describe(err: Throwable): String =
    err match {
      case fe: FetchException =>
        string(
          showException(fe) :/:
            Document.nest(2, showLog(fe.log))
        )
      case _ => string(Document.text("Unexpected exception"))
    }
}
