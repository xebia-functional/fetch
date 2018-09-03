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
      // val result = for {
      //   last <- env.rounds.lastOption
      // } yield last.response
      val resultDoc = Document.empty
//        result.fold(Document.empty: Document)((r) => Document.text(s"Fetching `${r}`"))

      val duration = for {
        firstRound <- env.rounds.headOption
        first <- firstRound.queries.headOption
        lastRound  <- env.rounds.lastOption
        last <- lastRound.queries.lastOption
      } yield last.end - first.start
      val durationDoc =
        duration.fold(Document.empty: Document)((d) =>
          Document.text("Fetch execution") :: showDuration(d / 1e9))

      durationDoc :/: Document.nest(2, pile(env.rounds.map(showRound)))
    }
  }

  def showRound(r: Round): Document =
    Document.text("[Round]") :: Document.nest(
      2, pile(r.queries.map(showRequest))
    )

  def showRequest(r: Request): Document = r.request match {
    case FetchOne(id, ds) =>
      Document.text(s"[Fetch one] From `${ds.name}` with id ${id}") :: showDuration(0)
    case Batch(ids, ds) =>
      Document.text(s"[Batch] From `${ds.name}` with ids ${ids.toList}") :: showDuration(0)
  }

  def showMissing(ds: DataSource[_, _], ids: List[_]): Document =
    Document.text(s"`${ds.name}` missing identities ${ids}")

  def showRoundCount(env: FetchEnv, err: FetchException): Document =
    Document.text(s", fetch interrupted after ${env.rounds.size} rounds")

  def showException(err: FetchException): Document = err match {
    case MissingIdentity(id, q) =>
      Document.text(s"[Error] Identity with id `${id}` for data source `${q.dataSource.name}` not found")
    // case MissingIdentities(env, missing) =>
    //   Document.text("[Error] Missing identities") :: showRoundCount(err) :/:
    //     Document.nest(2, pile(missing.toSeq.map((kv) => showMissing(kv._1, kv._2))))
    case UnhandledException(exc) =>
      Document
        .text(s"[Error] Unhandled `${exc.getClass.getName}`: '${exc.getMessage}'")
  }

  /* Given a [[fetch.env.Env]], describe it with a human-readable string. */
  def describe(env: Env): String =
    string(showEnv(env))

  /* Given a [[Throwable]], describe it with a human-readable string. */
  def describe(err: Throwable): String = err match {
    case e @ MissingIdentity(_, _) => string(showException(e))
    case e @ UnhandledException(_) => string(showException(e))
    case _ => string(Document.text("Unexpected exception"))
  }
}
