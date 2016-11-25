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

object debug {
  import scala.text.Document

  def string(doc: Document): String = {
    val writer = new java.io.StringWriter
    doc.format(1, writer)
    writer.toString
  }

  def pile(docs: Seq[Document]): Document =
    docs.foldLeft(Document.empty: Document)(_ :/: _)

  def showEnv(env: FetchEnv): Document = {
    val result = for {
      last <- env.rounds.lastOption
    } yield last.response
    val resultDoc = result.fold(Document.empty: Document)((r) => Document.text(s"ing `${r}`"))

    val duration = for {
      first <- env.rounds.headOption
      last  <- env.rounds.lastOption
    } yield last.end - first.start
    val durationDoc =
      duration.fold(Document.empty: Document)((d) => Document.text(s" took ${d / 1e9} seconds"))

    Document.text("Fetch") :: resultDoc :: durationDoc :/: Document
      .nest(2, pile(env.rounds.map(showRound)))
  }

  def showRound(r: Round): Document = r match {
    case Round(cache, q @ FetchOne(id, ds), result, start, end) =>
      showQuery(q) :: Document.text(s" took ${r.duration / 1e6} seconds")
    case Round(cache, q @ FetchMany(ids, ds), result, start, end) =>
      showQuery(q) :: Document.text(s" took ${r.duration / 1e6} seconds")
    case Round(cache, Concurrent(queries), result, start, end) =>
      Document
        .text("[Concurrent]") :: Document.text(s" took ${r.duration / 1e6} seconds") :: Document
        .nest(2, pile(queries.toList.map(showQuery)))
  }

  def showQuery(q: FetchQuery[_, _]): Document = q match {
    case FetchOne(id, ds) => Document.text(s"[Fetch one] From `${ds.name}` with id `${id}`")
    case FetchMany(ids, ds) =>
      Document.text(s"[Fetch many] From `${ds.name}` with ids `${ids.toList}`")
  }

  def describe(env: FetchEnv) = {
    println(string(showEnv(env)))
  }
}
