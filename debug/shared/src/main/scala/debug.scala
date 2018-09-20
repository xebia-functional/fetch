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

  def firstRequest(r: Round): Long =
    r.queries.map(_.start).minimum

  def lastRequest(r: Round): Long =
    r.queries.map(_.end).maximum

  def showEnv(env: Env): Document = env.rounds match {
    case Nil => Document.empty
    case _ => {
      val duration: Option[Long] = (
        env.rounds.lastOption.map(lastRequest),
        env.rounds.headOption.map(firstRequest)
      ).mapN(_ - _)
      val durationDoc =
        duration.fold(Document.empty: Document)((d: Long) =>
          Document.text("Fetch execution") :: showDuration(d))

      durationDoc :/: Document.nest(2, pile(env.rounds.mapWithIndex((r, i) => showRound(r, i + 1))))
    }
  }

  def showRound(r: Round, n: Int): Document = {
    val round = Document.text(s"[Round ${n}]") :: showDuration(roundDuration(r))

    round :: Document.nest(
      2, pile(r.queries.map(showRequest).toList)
    )
  }

  private def roundDuration(r: Round): Long =
    lastRequest(r) - firstRequest(r)

  def showRequest(r: Request): Document = r.request match {
    case FetchOne(id, ds) =>
      Document.text(s"[Fetch one] From `${ds.name}` with id ${id}") :: showDuration(r.duration)
    case Batch(ids, ds) =>
      Document.text(s"[Batch] From `${ds.name}` with ids ${ids.toList}") :: showDuration(r.duration)
  }

  def showMissing(ds: DataSource[F, _, _] forSome { type F[_] }, ids: List[_]): Document =
    Document.text(s"`${ds.name}` missing identities ${ids}")

  def showRoundCount(err: FetchException): Document =
    Document.text(s", fetch interrupted after ${err.environment.rounds.size} rounds")

  def showException(err: FetchException): Document = err match {
    case MissingIdentity(id, q, env) =>
      Document.text(s"[ERROR] Identity with id `${id}` for data source `${q.dataSource.name}` not found") :: showRoundCount(err)
    case UnhandledException(exc, env) =>
      Document
        .text(s"[ERROR] Unhandled `${exc.getClass.getName}`: '${exc.getMessage}'") :: showRoundCount(err)
  }

  /* Given a [[fetch.env.Env]], describe it with a human-readable string. */
  def describe(env: Env): String =
    string(showEnv(env))

  /* Given a [[Throwable]], describe it with a human-readable string. */
  def describe(err: Throwable): String = err match {
    case fe: FetchException => string(
      showException(fe) :/:
        Document.nest(2, showEnv(fe.environment))
    )
    case _ => string(Document.text("Unexpected exception"))
  }
}
