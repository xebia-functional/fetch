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

import org.scalatest._

import cats.{Eval, Id, MonadError}
import cats.data.NonEmptyList
import cats.std.list._

import fetch._

object TestHelper {

  import fetch.implicits._
  import fetch.syntax._

  val M: MonadError[Eval, Throwable] = implicits.evalMonadError

  final case class NotFound() extends Throwable

  final case class One(id: Int)
  implicit object OneSource extends DataSource[One, Int] {
    override def name = "OneSource"
    override def fetchOne(id: One): Eval[Option[Int]] = {
      M.pure(Option(id.id))
    }
    override def fetchMany(ids: NonEmptyList[One]): Eval[Map[One, Int]] =
      M.pure(ids.unwrap.map(one => (one, one.id)).toMap)
  }
  def one(id: Int): Fetch[Int] = Fetch(One(id))

  final case class AnotherOne(id: Int)
  implicit object AnotheroneSource extends DataSource[AnotherOne, Int] {
    override def name = "AnotherOneSource"
    override def fetchOne(id: AnotherOne): Eval[Option[Int]] =
      M.pure(Option(id.id))
    override def fetchMany(ids: NonEmptyList[AnotherOne]): Eval[Map[AnotherOne, Int]] =
      M.pure(ids.unwrap.map(anotherone => (anotherone, anotherone.id)).toMap)
  }
  def anotherOne(id: Int): Fetch[Int] = Fetch(AnotherOne(id))

  final case class Many(n: Int)
  implicit object ManySource extends DataSource[Many, List[Int]] {
    override def name = "ManySource"
    override def fetchOne(id: Many): Eval[Option[List[Int]]] =
      M.pure(Option(0 until id.n toList))
    override def fetchMany(ids: NonEmptyList[Many]): Eval[Map[Many, List[Int]]] =
      M.pure(ids.unwrap.map(m => (m, 0 until m.n toList)).toMap)
  }

  final case class Never()
  implicit object NeverSource extends DataSource[Never, Int] {
    override def name = "NeverSource"
    override def fetchOne(id: Never): Eval[Option[Int]] =
      M.pure(None)
    override def fetchMany(ids: NonEmptyList[Never]): Eval[Map[Never, Int]] =
      M.pure(Map.empty[Never, Int])
  }
  def many(id: Int): Fetch[List[Int]] = Fetch(Many(id))

  def runEnv[A](f: Fetch[A]): FetchEnv =
    Fetch.runEnv[Eval](f).value

  def totalFetched(rs: Seq[Round]): Int =
    rs.filterNot(_.cached)
      .foldLeft(0)((acc, round) =>
            round.kind match {
          case OneRound(_)          => acc + 1
          case ManyRound(ids)       => acc + ids.size
          case ConcurrentRound(ids) => acc + ids.map(_._2.size).sum
      })

  def totalBatches(rs: Seq[Round]): Int =
    rs.filterNot(_.cached)
      .foldLeft(0)((acc, round) =>
            round.kind match {
          case OneRound(_)          => acc
          case ManyRound(ids)       => acc + 1
          case ConcurrentRound(ids) => acc + ids.size
      })

  def concurrent(rs: Seq[Round]): Seq[Round] =
    rs.filter(
        r =>
          r.kind match {
            case ConcurrentRound(_) => true
            case other              => false
        }
    )
}

class FetchSyntaxTests extends FreeSpec with Matchers {

  import fetch.implicits._
  import fetch.syntax._
  import TestHelper._

  "Cartesian syntax is implicitly concurrent" in {
    import cats.syntax.cartesian._

    val fetch: Fetch[(Int, List[Int])] = (one(1) |@| many(3)).tupled

    val env    = Fetch.runEnv[Eval](fetch).value
    val rounds = env.rounds

    concurrent(rounds).size shouldEqual 1
  }

  "Apply syntax is implicitly concurrent" in {
    import cats.syntax.apply._

    val fetch: Fetch[Int] = Fetch.pure((x: Int, y: Int) => x + y).ap2(one(1), one(2))

    val env    = Fetch.runEnv[Eval](fetch).value
    val rounds = env.rounds

    concurrent(rounds).size shouldEqual 1
    totalBatches(rounds) shouldEqual 1
    totalFetched(rounds) shouldEqual 2
  }

  "`fetch` syntax allows lifting of any value to the context of a fetch" in {
    Fetch.pure(42) shouldEqual 42.fetch
  }

  "`fetch` syntax allows lifting of any `Throwable` as a failure on a fetch" in {
    case object Ex extends RuntimeException
    val ME = implicitly[MonadError[Eval, Throwable]]
    val e1 = ME.attempt(Fetch.run[Eval](Fetch.error(Ex))).value
    val e2 = ME.attempt(Fetch.run[Eval](Ex.fetch)).value
    e1 shouldEqual e2
  }

  "`join` syntax is equivalent to `Fetch#join`" in {
    val join1 = Fetch.join(one(1), many(3)).runA[Eval].value
    val join2 = one(1).join(many(3)).runA[Eval].value

    join1 shouldEqual join2
  }

  "`runF` syntax is equivalent to `Fetch#runFetch`" in {

    val rf1 = Fetch.runFetch(1.fetch).value
    val rf2 = 1.fetch.runF[Eval].value

    rf1 shouldEqual rf2
  }

  "`runE` syntax is equivalent to `Fetch#runEnv`" in {

    val rf1 = Fetch.runEnv(1.fetch).value
    val rf2 = 1.fetch.runE[Eval].value

    rf1 shouldEqual rf2
  }

  "`runA` syntax is equivalent to `Fetch#run`" in {

    val rf1 = Fetch.run(1.fetch).value
    val rf2 = 1.fetch.runA[Eval].value

    rf1 shouldEqual rf2
  }
}

class FetchTests extends FreeSpec with Matchers {

  import fetch.implicits._
  import TestHelper._

  "We can lift plain values to Fetch" in {
    val fetch: Fetch[Int] = Fetch.pure(42)
    Fetch.run[Eval](fetch).value shouldEqual 42
  }

  "Data sources with errors throw fetch failures" in {
    val fetch: Fetch[Int] = Fetch(Never())

    intercept[FetchFailure] {
      Fetch.runEnv[Eval](fetch).value
    } match {
      case FetchFailure(env) => {
          env.rounds.headOption match {
            case Some(Round(_, _, OneRound(id), _, _, _)) => id shouldEqual Never()
            case _                                        => fail("Expected Some(Round(_,_, Oneround(id),_,_,_)) but None found")
          }
        }
    }
  }

  "Data sources with errors throw fetch failures that can be handled" in {
    val fetch: Fetch[Int] = Fetch(Never())

    M.handleErrorWith(
          Fetch.run[Eval](fetch)
      )(err => Eval.now(42))
      .value shouldEqual 42
  }

  "Data sources with errors and cached values throw fetch failures with the cache" in {
    val fetch: Fetch[Int] = Fetch(Never())
    val cache = InMemoryCache(
        OneSource.identity(One(1)) -> 1
    )

    intercept[FetchFailure] {
      Fetch.run[Eval](fetch, cache).value
    } match {
      case FetchFailure(env) => env.cache shouldEqual cache
    }
  }

  "Data sources with errors won't fail if they're cached" in {
    val fetch: Fetch[Int] = Fetch(Never())
    val cache = InMemoryCache(
        NeverSource.identity(Never()) -> 1
    )
    Fetch.run[Eval](fetch, cache).value shouldEqual 1
  }

  "We can lift errors to Fetch" in {
    val fetch: Fetch[Int] = Fetch.error(NotFound())
    intercept[NotFound] {
      Fetch.run[Eval](fetch).value
    }
  }

  "We can lift handle and recover from errors in Fetch" in {
    val fetch: Fetch[Int] = Fetch.error(NotFound())
    M.handleErrorWith(
          Fetch.run[Eval](fetch)
      )(err => M.pure(42))
      .value shouldEqual 42
  }

  "We can lift values which have a Data Source to Fetch" in {
    Fetch.run[Eval](one(1)).value shouldEqual 1
  }

  "We can map over Fetch values" in {
    val fetch = one(1).map(_ + 1)
    Fetch.run[Eval](fetch).value shouldEqual 2
  }

  "We can use fetch inside a for comprehension" in {
    val fetch = for {
      o <- one(1)
      t <- one(2)
    } yield (o, t)

    Fetch.run[Eval](fetch).value shouldEqual (1, 2)
  }

  "Monadic bind implies sequential execution" in {
    val fetch = for {
      o <- one(1)
      t <- one(2)
    } yield (o, t)

    Fetch.runEnv[Eval](fetch).value.rounds.size shouldEqual 2
  }

  "We can mix data sources" in {
    val fetch: Fetch[(Int, List[Int])] = for {
      o <- one(1)
      m <- many(3)
    } yield (o, m)

    Fetch.run[Eval](fetch).value shouldEqual (1, List(0, 1, 2))
  }

  "We can use Fetch as a cartesian" in {
    import cats.syntax.cartesian._

    val fetch: Fetch[(Int, List[Int])] = (one(1) |@| many(3)).tupled

    Fetch.run[Eval](fetch).value shouldEqual (1, List(0, 1, 2))
  }

  "We can use Fetch as an applicative" in {
    import cats.syntax.cartesian._

    val fetch: Fetch[Int] = (one(1) |@| one(2) |@| one(3)).map(_ + _ + _)

    Fetch.run[Eval](fetch).value shouldEqual 6
  }

  "We can traverse over a list with a Fetch for each element" in {
    import cats.std.list._
    import cats.syntax.traverse._

    val fetch: Fetch[List[Int]] = for {
      manies <- many(3)
      ones   <- manies.traverse(one)
    } yield ones

    Fetch.run[Eval](fetch).value shouldEqual List(0, 1, 2)
  }

  "Traversals are implicitly concurrent" in {
    import cats.std.list._
    import cats.syntax.traverse._

    val fetch: Fetch[List[Int]] = for {
      manies <- many(3)
      ones   <- manies.traverse(one)
    } yield ones

    val rounds = Fetch.runEnv[Eval](fetch).value.rounds

    concurrent(rounds).size shouldEqual 1
  }

  "The product of two fetches implies parallel fetching" in {
    val fetch: Fetch[(Int, List[Int])] = Fetch.join(one(1), many(3))

    val rounds = Fetch.runEnv[Eval](fetch).value.rounds

    concurrent(rounds).size shouldEqual 1
  }

  "If a fetch fails in the left hand of a product the product will fail" in {
    val fetch: Fetch[(Int, List[Int])] = Fetch.join(Fetch.error(NotFound()), many(3))

    intercept[NotFound] {
      Fetch.run[Eval](fetch).value
    }
  }

  "If a fetch fails in the right hand of a product the product will fail" in {
    val fetch: Fetch[(List[Int], Int)] = Fetch.join(many(3), Fetch.error(NotFound()))

    intercept[NotFound] {
      Fetch.run[Eval](fetch).value
    }
  }

  "If there is a missing identity in the left hand of a product the product will fail" in {
    val fetch: Fetch[(Int, List[Int])] = Fetch.join(Fetch(Never()), many(3))

    intercept[FetchFailure] {
      Fetch.run[Eval](fetch).value
    }
  }

  "If there is a missing identity in the right hand of a product the product will fail" in {
    val fetch: Fetch[(List[Int], Int)] = Fetch.join(many(3), Fetch(Never()))

    intercept[FetchFailure] {
      Fetch.run[Eval](fetch).value
    }
  }

  "The product of concurrent fetches implies everything fetched concurrently" in {
    val fetch = Fetch.join(
        Fetch.join(
            one(1),
            Fetch.join(one(2), one(3))
        ),
        one(4)
    )

    val env    = Fetch.runEnv[Eval](fetch).value
    val rounds = env.rounds

    concurrent(rounds).size shouldEqual 1
    totalBatches(rounds) shouldEqual 1
    totalFetched(rounds) shouldEqual 4
  }

  "The product of concurrent fetches of the same type implies everything fetched in a single batch" in {
    val fetch = Fetch.join(
        Fetch.join(
            for {
              a <- one(1)
              b <- one(2)
              c <- one(3)
            } yield c,
            for {
              a <- one(2)
              m <- many(4)
              c <- one(3)
            } yield c
        ),
        one(3)
    )

    val env    = Fetch.runEnv[Eval](fetch).value
    val rounds = env.rounds

    concurrent(rounds).size shouldEqual 2
    totalBatches(rounds) shouldEqual 2
    totalFetched(rounds) shouldEqual 4
  }

  "Every level of joined concurrent fetches is combined and batched" in {
    val fetch = Fetch.join(
        for {
          a <- one(2)
          b <- many(1)
          c <- one(5)
        } yield c,
        for {
          a <- one(3)
          b <- many(2)
          c <- one(4)
        } yield c
    )

    val env    = Fetch.runEnv[Eval](fetch).value
    val rounds = env.rounds

    concurrent(rounds).size shouldEqual 3
    totalBatches(rounds) shouldEqual 3
    totalFetched(rounds) shouldEqual 6
  }

  "Every level of sequenced concurrent of concurrent fetches is batched" in {
    val fetch = Fetch.join(
        Fetch.join(
            for {
              a <- Fetch.sequence(List(one(2), one(3), one(4)))
              b <- Fetch.sequence(List(many(0), many(1)))
              c <- Fetch.sequence(List(one(9), one(10), one(11)))
            } yield c,
            for {
              a <- Fetch.sequence(List(one(5), one(6), one(7)))
              b <- Fetch.sequence(List(many(2), many(3)))
              c <- Fetch.sequence(List(one(12), one(13), one(14)))
            } yield c
        ),
        Fetch.sequence(List(one(15), one(16), one(17)))
    )

    val env    = Fetch.runEnv[Eval](fetch).value
    val rounds = env.rounds

    concurrent(rounds).size shouldEqual 3
    totalBatches(rounds) shouldEqual 3
    totalFetched(rounds) shouldEqual 9 + 4 + 6
  }

  "The product of two fetches from the same data source implies batching" in {
    val fetch: Fetch[(Int, Int)] = Fetch.join(one(1), one(3))

    val rounds = Fetch.runEnv[Eval](fetch).value.rounds

    concurrent(rounds).size shouldEqual 1
    totalBatches(concurrent(rounds)) shouldEqual 1
  }

  "We can depend on previous computations of Fetch values" in {
    val fetch: Fetch[Int] = for {
      o <- one(1)
      t <- one(o + 1)
    } yield o + t

    Fetch.run[Eval](fetch).value shouldEqual 3
  }

  "We can collect a list of Fetch into one" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3))
    val fetch: Fetch[List[Int]]   = Fetch.sequence(sources)
    Fetch.run[Eval](fetch).value shouldEqual List(1, 2, 3)
  }

  "We can collect a list of Fetches with heterogeneous sources" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), anotherOne(4), anotherOne(5))
    val fetch: Fetch[List[Int]]   = Fetch.sequence(sources)
    Fetch.run[Eval](fetch).value shouldEqual List(1, 2, 3, 4, 5)
  }

  "Sequenced fetches are run concurrently" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), anotherOne(4), anotherOne(5))
    val fetch: Fetch[List[Int]]   = Fetch.sequence(sources)

    val rounds = Fetch.runEnv[Eval](fetch).value.rounds

    concurrent(rounds).size shouldEqual 1
    totalBatches(rounds) shouldEqual 2
  }

  "Sequenced fetches are deduped" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(1))
    val fetch: Fetch[List[Int]]   = Fetch.sequence(sources)

    val rounds = Fetch.runEnv[Eval](fetch).value.rounds

    totalFetched(concurrent(rounds)) shouldEqual 2
    concurrent(rounds).size shouldEqual 1
  }

  "Sequenced fetches are not asked for when cached" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), one(4))
    val fetch: Fetch[List[Int]]   = Fetch.sequence(sources)

    val rounds = Fetch
      .runEnv[Eval](
          fetch,
          InMemoryCache(
              OneSource.identity(One(1)) -> 1,
              OneSource.identity(One(2)) -> 2
          )
      )
      .value
      .rounds

    totalFetched(concurrent(rounds)) shouldEqual 2
    concurrent(rounds).size shouldEqual 1
  }

  "We can collect the results of a traversal" in {
    val expected = List(1, 2, 3)

    val fetch = Fetch.traverse(expected)(one)

    Fetch.run[Eval](fetch).value shouldEqual expected
  }

  "Traversals are run concurrently" in {
    val fetch = Fetch.traverse(List(1, 2, 3))(one)

    val rounds = Fetch.runEnv[Eval](fetch).value.rounds

    concurrent(rounds).size shouldEqual 1
  }

  "Duplicated sources are only fetched once" in {
    val fetch = Fetch.traverse(List(1, 2, 1))(one)

    val rounds = Fetch.runEnv[Eval](fetch).value.rounds

    concurrent(rounds).size shouldEqual 1
    totalFetched(concurrent(rounds)) shouldEqual 2
  }

  "Sources that can be fetched concurrently inside a for comprehension will be" in {
    val fetch = for {
      v      <- Fetch.pure(List(1, 2, 1))
      result <- Fetch.traverse(v)(one)
    } yield result

    val rounds = Fetch.runEnv[Eval](fetch).value.rounds

    concurrent(rounds).size shouldEqual 1
    totalFetched(concurrent(rounds)) shouldEqual 2
  }

  "Elements are cached and thus not fetched more than once" in {
    val fetch = for {
      aOne       <- one(1)
      anotherOne <- one(1)
      _          <- one(1)
      _          <- one(2)
      _          <- one(3)
      _          <- one(1)
      _          <- Fetch.traverse(List(1, 2, 3))(one)
      _          <- one(1)
    } yield aOne + anotherOne

    val rounds = Fetch.runEnv[Eval](fetch).value.rounds

    totalFetched(rounds) shouldEqual 3
  }

  "Elements that are cached won't be fetched" in {
    val fetch = for {
      aOne       <- one(1)
      anotherOne <- one(1)
      _          <- one(1)
      _          <- one(2)
      _          <- one(3)
      _          <- one(1)
      _          <- Fetch.traverse(List(1, 2, 3))(one)
      _          <- one(1)
    } yield aOne + anotherOne

    val rounds = Fetch
      .runEnv[Eval](
          fetch,
          InMemoryCache(
              OneSource.identity(One(1)) -> 1,
              OneSource.identity(One(2)) -> 2,
              OneSource.identity(One(3)) -> 3
          )
      )
      .value
      .rounds

    totalFetched(rounds) shouldEqual 0
  }

  final case class MyCache(state: Map[Any, Any] = Map.empty[Any, Any]) extends DataSourceCache {
    override def get(k: DataSourceIdentity): Option[Any] = state.get(k)
    override def update[A](k: DataSourceIdentity, v: A): MyCache =
      copy(state = state.updated(k, v))
  }

  val fullCache: MyCache = MyCache(
      Map(
          OneSource.identity(One(1))   -> 1,
          OneSource.identity(One(2))   -> 2,
          OneSource.identity(One(3))   -> 3,
          OneSource.identity(One(1))   -> 1,
          ManySource.identity(Many(2)) -> List(0, 1)
      )
  )

  "We can use a custom cache" in {
    val fetch = for {
      aOne       <- one(1)
      anotherOne <- one(1)
      _          <- one(1)
      _          <- one(2)
      _          <- one(3)
      _          <- one(1)
      _          <- Fetch.traverse(List(1, 2, 3))(one)
      _          <- one(1)
    } yield aOne + anotherOne

    val rounds = Fetch
      .runEnv[Eval](
          fetch,
          InMemoryCache(
              OneSource.identity(One(1))   -> 1,
              OneSource.identity(One(2))   -> 2,
              OneSource.identity(One(3))   -> 3,
              ManySource.identity(Many(2)) -> List(0, 1)
          )
      )
      .value
      .rounds

    totalFetched(rounds) shouldEqual 0
  }

  case class ForgetfulCache() extends DataSourceCache {
    override def get(k: DataSourceIdentity): Option[Any]                = None
    override def update[A](k: DataSourceIdentity, v: A): ForgetfulCache = this
  }

  "We can use a custom cache that discards elements" in {
    val fetch = for {
      aOne       <- one(1)
      anotherOne <- one(1)
      _          <- one(1)
      _          <- one(2)
      _          <- one(3)
      _          <- one(1)
      _          <- one(1)
    } yield aOne + anotherOne

    val rounds = Fetch.runEnv[Eval](fetch, ForgetfulCache()).value.rounds

    totalFetched(rounds) shouldEqual 7
  }

  "We can use a custom cache that discards elements together with concurrent fetches" in {
    val fetch = for {
      aOne       <- one(1)
      anotherOne <- one(1)
      _          <- one(1)
      _          <- one(2)
      _          <- Fetch.traverse(List(1, 2, 3))(one)
      _          <- one(3)
      _          <- one(1)
      _          <- one(1)
    } yield aOne + anotherOne

    val rounds = Fetch.runEnv[Eval](fetch, ForgetfulCache()).value.rounds

    totalFetched(rounds) shouldEqual 10
  }
}

class FetchFutureTests extends AsyncFreeSpec with Matchers {
  import scala.concurrent._
  import scala.concurrent.ExecutionContext.global

  import cats.std.future._

  implicit def executionContext = global
  override def newInstance      = new FetchFutureTests

  final case class ArticleId(id: Int)
  final case class Article(id: Int, content: String) {
    def author: Int = id + 1
  }

  implicit object ArticleFuture extends DataSource[ArticleId, Article] {
    override def name = "ArticleFuture"
    override def fetchOne(id: ArticleId): Eval[Option[Article]] =
      Eval.later(Option(Article(id.id, "An article with id " + id.id)))
    override def fetchMany(ids: NonEmptyList[ArticleId]): Eval[Map[ArticleId, Article]] = {
      Eval.later({
        ids.unwrap.map(tid => (tid, Article(tid.id, "An article with id " + tid.id))).toMap
      })
    }
  }

  def article(id: Int): Fetch[Article] = Fetch(ArticleId(id))

  final case class AuthorId(id: Int)
  final case class Author(id: Int, name: String)

  implicit object AuthorFuture extends DataSource[AuthorId, Author] {
    override def name = "AuthorFuture"
    override def fetchOne(id: AuthorId): Eval[Option[Author]] =
      Eval.later(Option(Author(id.id, "@egg" + id.id)))
    override def fetchMany(ids: NonEmptyList[AuthorId]): Eval[Map[AuthorId, Author]] = {
      Eval.later({
        ids.unwrap.map(tid => (tid, Author(tid.id, "@egg" + tid.id))).toMap
      })
    }
  }

  def author(a: Article): Fetch[Author] = Fetch(AuthorId(a.author))

  "We can interpret a fetch into a future" in {
    val fetch: Fetch[Article] = article(1)

    val fut: Future[Article] = Fetch.run(fetch)

    fut.map(_ shouldEqual Article(1, "An article with id 1"))
  }

  "We can combine several data sources and interpret a fetch into a future" in {
    val fetch: Fetch[(Article, Author)] = for {
      art    <- article(1)
      author <- author(art)
    } yield (art, author)

    val fut: Future[(Article, Author)] = Fetch.run(fetch)

    fut.map(_ shouldEqual (Article(1, "An article with id 1"), Author(2, "@egg2")))
  }

  "We can use combinators in a for comprehension and interpret a fetch into a future" in {
    val fetch: Fetch[List[Article]] = for {
      articles <- Fetch.traverse(List(1, 1, 2))(article)
    } yield articles

    val fut: Future[List[Article]] = Fetch.run(fetch)

    fut.map(
        _ shouldEqual List(
            Article(1, "An article with id 1"),
            Article(1, "An article with id 1"),
            Article(2, "An article with id 2")
        )
    )
  }

  "We can use combinators and multiple sources in a for comprehension and interpret a fetch into a future" in {
    val fetch = for {
      articles <- Fetch.traverse(List(1, 1, 2))(article)
      authors  <- Fetch.traverse(articles)(author)
    } yield (articles, authors)

    val fut: Future[(List[Article], List[Author])] = Fetch.run(fetch, InMemoryCache.empty)

    fut.map(
        _ shouldEqual (
            List(
                Article(1, "An article with id 1"),
                Article(1, "An article with id 1"),
                Article(2, "An article with id 2")
            ),
            List(
                Author(2, "@egg2"),
                Author(2, "@egg2"),
                Author(3, "@egg3")
            )
        )
    )
  }
}
