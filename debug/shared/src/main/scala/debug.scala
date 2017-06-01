/*
 * Copyright 2016-2017 47 Degrees, LLC. <http://www.47deg.com>
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

object debug {
  import fetch.document.Document

  def string(doc: Document): String = {
    val writer = new java.io.StringWriter
    doc.format(1, writer)
    writer.toString
  }

  def pile(docs: Seq[Document]): Document =
    docs.foldLeft(Document.empty: Document)(_ :/: _)

  def showDuration(secs: Double): Document =
    Document.text(f" took $secs%2f seconds")

  def showEnv(env: Env): Document = env.rounds match {
    case Nil => Document.empty
    case _ => {
      val result = for {
        last <- env.rounds.lastOption
      } yield last.response
      val resultDoc =
        result.fold(Document.empty: Document)((r) => Document.text(s"Fetching `${r}`"))

      val duration = for {
        first <- env.rounds.headOption
        last  <- env.rounds.lastOption
      } yield last.end - first.start
      val durationDoc =
        duration.fold(Document.empty: Document)((d) =>
          Document.text("Fetch execution") :: showDuration(d / 1e9))

      durationDoc :/: Document.nest(2, pile(env.rounds.map(showRound)))
    }
  }

  def showRound(r: Round): Document = r match {
    case Round(cache, q @ FetchOne(id, ds), result, start, end) =>
      showQuery(q) :: showDuration(r.duration / 1e6)
    case Round(cache, q @ FetchMany(ids, ds), result, start, end) =>
      showQuery(q) :: showDuration(r.duration / 1e6)
    case Round(cache, Concurrent(queries), result, start, end) =>
      if (queries.tail.isEmpty) {
        showQuery(queries.head) :: showDuration(r.duration / 1e6)
      } else {
        Document.text("[Concurrent]") :: showDuration(r.duration / 1e6) :: Document
          .nest(2, pile(queries.toList.map(showQuery)))
      }
  }

  def showQuery(q: FetchQuery[_, _]): Document = q match {
    case FetchOne(id, ds) => Document.text(s"[Fetch one] From `${ds.name}` with id ${id}")
    case FetchMany(ids, ds) =>
      Document.text(s"[Fetch many] From `${ds.name}` with ids ${ids.toList}")
  }

  def showMissing(ds: DataSourceName, ids: List[_]): Document =
    Document.text(s"`${ds}` missing identities ${ids}")

  def showRoundCount(err: FetchException): Document =
    Document.text(s", fetch interrupted after ${err.env.rounds.size} rounds")

  def showException(err: FetchException): Document = err match {
    case NotFound(env, q @ FetchOne(id, ds)) =>
      Document.text(s"[Error] Identity not found: ${id} in `${ds.name}`") :: showRoundCount(err)
    case MissingIdentities(env, missing) =>
      Document.text("[Error] Missing identities") :: showRoundCount(err) :/:
        Document.nest(2, pile(missing.toSeq.map((kv) => showMissing(kv._1, kv._2))))
    case UnhandledException(env, exc) =>
      Document
        .text(s"[Error] Unhandled `${exc.getClass.getName}`: '${exc.getMessage}'") :: showRoundCount(
        err)
  }

  /* Given a [[fetch.env.Env]], describe it with a human-readable string. */
  def describe(env: Env): String =
    string(showEnv(env))

  /* Given a [[fetch.FetchException]], describe it with a human-readable string. */
  def describe(err: FetchException): String = {
    string(
      showException(err) :/:
        Document.nest(
        2,
        showEnv(err.env)
      )
    )
  }
}
