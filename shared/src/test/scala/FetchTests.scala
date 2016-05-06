import org.scalatest._

import cats.{ Eval, MonadError }

import fetch._

class FetchTests extends FreeSpec with Matchers {
  import fetch.implicits.eval.monadError

  implicit val M: MonadError[Eval, Throwable] = monadError

  case class NotFound() extends Throwable

  case class One(id: Int)
  implicit object OneSource extends DataSource[One, Int, Eval] {
    override def name = "OneSource"
    override def fetch(ids: List[One]): Eval[Map[One, Int]] =
      M.pure(ids.map(one => (one, one.id)).toMap)
  }
  def one(id: Int): Fetch[Int] = Fetch(One(id))

  case class AnotherOne(id: Int)
  implicit object AnotheroneSource extends DataSource[AnotherOne, Int, Eval] {
    override def name = "AnotherOneSource"

    override def fetch(ids: List[AnotherOne]): Eval[Map[AnotherOne, Int]] =
      M.pure(ids.map(anotherone => (anotherone, anotherone.id)).toMap)
  }
  def anotherOne(id: Int): Fetch[Int] = Fetch(AnotherOne(id))

  case class Many(n: Int)
  implicit object ManySource extends DataSource[Many, List[Int], Eval] {
    override def name = "ManySource"
    override def fetch(ids: List[Many]): Eval[Map[Many, List[Int]]] =
      M.pure(ids.map(m => (m, 0 until m.n toList)).toMap)
  }

  case class Never()
  implicit object NeverSource extends DataSource[Never, Int, Eval] {
    override def name = "NeverSource"
    override def fetch(ids: List[Never]): Eval[Map[Never, Int]] =
      M.pure(Map.empty[Never, Int])
  }
  def many(id: Int): Fetch[List[Int]] = Fetch(Many(id))

  def runEnv[A](f: Fetch[A]): FetchEnv[InMemoryCache] =
    Fetch.runEnv(f).value

  def totalFetched(rs: Seq[Round]): Int = rs.filterNot(_.cached).foldLeft(0)((acc, round) => round.kind match {
    case OneRound(_) => acc + 1
    case ManyRound(ids) => acc + ids.size
    case ConcurrentRound(ids) => acc + ids.map(_._2.size).sum
  })

  def totalBatches(rs: Seq[Round]): Int = rs.filterNot(_.cached).foldLeft(0)((acc, round) => round.kind match {
    case OneRound(_) => acc
    case ManyRound(ids) => acc + 1
    case ConcurrentRound(ids) => acc + ids.size
  })

  def concurrent(rs: Seq[Round]): Seq[Round] = rs.filter(r => r.kind match {
    case ConcurrentRound(_) => true
    case other => false
  })

  "We can lift plain values to Fetch" in {
    val fetch: Fetch[Int] = Fetch.pure(42)
    Fetch.run(fetch).value shouldEqual 42
  }

  "Data sources with errors throw fetch failures" in {
    val fetch: Fetch[Int] = Fetch(Never())

    intercept[FetchFailure[InMemoryCache]] {
      Fetch.runEnv(fetch).value
    } match {
      case FetchFailure(env) => {
        env.rounds.headOption match {
          case Some(Round(_, _, OneRound(id), _, _, _)) => id shouldEqual Never()
        }
      }
    }
  }

  "Data sources with errors throw fetch failures that can be handled" in {
    val fetch: Fetch[Int] = Fetch(Never())

    M.handleErrorWith(
      Fetch.run(fetch)
    )(err => Eval.now(42)).value shouldEqual 42
  }

  "Data sources with errors and cached values throw fetch failures with the cache" in {
    val fetch: Fetch[Int] = Fetch(Never())
    val cache = InMemoryCache(
      OneSource.identity(One(1)) -> 1
    )

    intercept[FetchFailure[InMemoryCache]] {
      Fetch.run(fetch, cache).value
    } match {
      case FetchFailure(env) => env.cache shouldEqual cache
    }
  }

  "Data sources with errors won't fail if they're cached" in {
    val fetch: Fetch[Int] = Fetch(Never())
    val cache = InMemoryCache(
      NeverSource.identity(Never()) -> 1
    )
    Fetch.run(fetch, cache).value shouldEqual 1
  }

  "We can lift errors to Fetch" in {
    val fetch: Fetch[Int] = Fetch.error(NotFound())
    intercept[NotFound] {
      Fetch.run(fetch).value
    }
  }

  "We can lift handle and recover from errors in Fetch" in {
    val fetch: Fetch[Int] = Fetch.error(NotFound())
    M.handleErrorWith(
      Fetch.run(fetch)
    )(err => M.pure(42)).value shouldEqual 42
  }

  "We can lift values which have a Data Source to Fetch" in {
    Fetch.run(one(1)).value shouldEqual 1
  }

  "We can map over Fetch values" in {
    val fetch = one(1).map(_ + 1)
    Fetch.run(fetch).value shouldEqual 2
  }

  "We can use fetch inside a for comprehension" in {
    val fetch = for {
      o <- one(1)
      t <- one(2)
    } yield (o, t)

    Fetch.run(fetch).value shouldEqual (1, 2)
  }

  "Monadic bind implies sequential execution" in {
    val fetch = for {
      o <- one(1)
      t <- one(2)
    } yield (o, t)

    Fetch.runEnv(fetch).value.rounds.size shouldEqual 2
  }

  "We can mix data sources" in {
    val fetch: Fetch[(Int, List[Int])] = for {
      o <- one(1)
      m <- many(3)
    } yield (o, m)

    Fetch.run(fetch).value shouldEqual (1, List(0, 1, 2))
  }

  "We can use Fetch as an applicative" in {
    import cats.syntax.cartesian._

    val fetch: Fetch[(Int, List[Int])] = (one(1) |@| many(3)).map { case (a, b) => (a, b) }

    Fetch.run(fetch).value shouldEqual (1, List(0, 1, 2))
  }

  "We can traverse over a list with a Fetch for each element" in {
    import cats.std.list._
    import cats.syntax.traverse._

    val fetch: Fetch[List[Int]] = for {
      manies <- many(3)
      ones <- manies.traverse(one)
    } yield ones

    Fetch.run(fetch).value shouldEqual List(0, 1, 2)
  }

  "Traversals are implicitly batched" in {
    import cats.std.list._
    import cats.syntax.traverse._

    val fetch: Fetch[List[Int]] = for {
      manies <- many(3)
      ones <- manies.traverse(one)
    } yield ones

    val rounds = Fetch.runEnv(fetch).value.rounds

    totalBatches(rounds).size shouldEqual 2
  }

  "The product of two fetches implies parallel fetching" in {
    val fetch: Fetch[(Int, List[Int])] = Fetch.join(one(1), many(3))

    val rounds = Fetch.runEnv(fetch).value.rounds

    concurrent(rounds).size shouldEqual 1
  }

  "If a fetch fails in the left hand of a product the product will fail" in {
    val fetch: Fetch[(Int, List[Int])] = Fetch.join(Fetch.error(NotFound()), many(3))

    intercept[NotFound] {
      Fetch.run(fetch).value
    }
  }

  "If a fetch fails in the right hand of a product the product will fail" in {
    val fetch: Fetch[(List[Int], Int)] = Fetch.join(many(3), Fetch.error(NotFound()))

    intercept[NotFound] {
      Fetch.run(fetch).value
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

    val env = Fetch.runEnv(fetch).value
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

    val env = Fetch.runEnv(fetch).value
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

    val env = Fetch.runEnv(fetch).value
    val rounds = env.rounds

    concurrent(rounds).size shouldEqual 3
    totalBatches(rounds) shouldEqual 3
    totalFetched(rounds) shouldEqual 6
  }


  "Every level of collected concurrent of concurrent fetches is batched" in {
    val fetch = Fetch.join(
      Fetch.join(
        for {
          a <- Fetch.collect(List(one(2), one(3), one(4)))
          b <- Fetch.collect(List(many(0), many(1)))
          c <- Fetch.collect(List(one(9), one(10), one(11)))
        } yield c,
        for {
          a <- Fetch.collect(List(one(5), one(6), one(7)))
          b <- Fetch.collect(List(many(2), many(3)))
          c <- Fetch.collect(List(one(12), one(13), one(14)))
        } yield c
      ),
      Fetch.collect(List(one(15), one(16), one(17)))
    )

    val env = Fetch.runEnv(fetch).value
    val rounds = env.rounds

    concurrent(rounds).size shouldEqual 3
    totalBatches(rounds) shouldEqual 3
    totalFetched(rounds) shouldEqual 9 + 4 + 6
  }

  "The product of two fetches from the same data source implies batching" in {
    val fetch: Fetch[(Int, Int)] = Fetch.join(one(1), one(3))

    val rounds = Fetch.runEnv(fetch).value.rounds

    concurrent(rounds).size shouldEqual 1
    totalBatches(concurrent(rounds)) shouldEqual 1
  }

  "Cartesian syntax is implicitly concurrent" in {
    import cats.syntax.cartesian._

    val fetch: Fetch[(Int, List[Int])] = (one(1) |@| many(3)).tupled

    val env = Fetch.runEnv(fetch).value
    val rounds = env.rounds

    concurrent(rounds).size shouldEqual 1
  }

  "Apply syntax is implicitly concurrent" in {
    import cats.syntax.apply._

    val fetch: Fetch[Int] = Fetch.pure((x: Int, y: Int) => x + y).ap2(one(1), one(2))

    val env = Fetch.runEnv(fetch).value
    val rounds = env.rounds

    concurrent(rounds).size shouldEqual 1
    totalBatches(rounds) shouldEqual 1
    totalFetched(rounds) shouldEqual 2
  }

  "We can depend on previous computations of Fetch values" in {
    val fetch: Fetch[Int] = for {
      o <- one(1)
      t <- one(o + 1)
    } yield o + t

    Fetch.run(fetch).value shouldEqual 3
  }

  "We can collect a list of Fetch into one" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3))
    val fetch: Fetch[List[Int]] = Fetch.collect(sources)
    Fetch.run(fetch).value shouldEqual List(1, 2, 3)
  }

  "We can collect a list of Fetches with heterogeneous sources" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), anotherOne(4), anotherOne(5))
    val fetch: Fetch[List[Int]] = Fetch.collect(sources)
    Fetch.run(fetch).value shouldEqual List(1, 2, 3, 4, 5)
  }

  "Collected fetches are run concurrently" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), anotherOne(4), anotherOne(5))
    val fetch: Fetch[List[Int]] = Fetch.collect(sources)

    val rounds = Fetch.runEnv(fetch).value.rounds

    concurrent(rounds).size shouldEqual 1
    totalBatches(rounds) shouldEqual 2
  }

  "Collected fetches are deduped" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(1))
    val fetch: Fetch[List[Int]] = Fetch.collect(sources)

    val rounds = Fetch.runEnv(fetch).value.rounds

    totalFetched(concurrent(rounds)) shouldEqual 2
    concurrent(rounds).size shouldEqual 1
  }

  "Collected fetches are not asked for when cached" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), one(4))
    val fetch: Fetch[List[Int]] = Fetch.collect(sources)

    val rounds = Fetch.runEnv(fetch, InMemoryCache(
      OneSource.identity(One(1)) -> 1,
      OneSource.identity(One(2)) -> 2
    )).value.rounds

    totalFetched(concurrent(rounds)) shouldEqual 2
    concurrent(rounds).size shouldEqual 1
  }

  "We can collect the results of a traversal" in {
    val expected = List(1, 2, 3)

    val fetch = Fetch.traverse(expected)(one)

    Fetch.run(fetch).value shouldEqual expected
  }

  "Traversals are run concurrently" in {
    val fetch = Fetch.traverse(List(1, 2, 3))(one)

    val rounds = Fetch.runEnv(fetch).value.rounds

    concurrent(rounds).size shouldEqual 1
  }

  "Duplicated sources are only fetched once" in {
    val fetch = Fetch.traverse(List(1, 2, 1))(one)

    val rounds = Fetch.runEnv(fetch).value.rounds

    concurrent(rounds).size shouldEqual 1
    totalFetched(concurrent(rounds)) shouldEqual 2
  }

  "Sources that can be fetched concurrently inside a for comprehension will be" in {
    val fetch = for {
      v <- Fetch.pure(List(1, 2, 1))
      result <- Fetch.traverse(v)(one)
    } yield result

    val rounds = Fetch.runEnv(fetch).value.rounds

    concurrent(rounds).size shouldEqual 1
    totalFetched(concurrent(rounds)) shouldEqual 2
  }

  "Elements are cached and thus not fetched more than once" in {
    val fetch = for {
      aOne <- one(1)
      anotherOne <- one(1)
      _ <- one(1)
      _ <- one(2)
      _ <- one(3)
      _ <- one(1)
      _ <- Fetch.traverse(List(1, 2, 3))(one)
      _ <- one(1)
    } yield aOne + anotherOne

    val rounds = Fetch.runEnv(fetch).value.rounds

    totalFetched(rounds) shouldEqual 3
  }

  "Elements that are cached won't be fetched" in {
    val fetch = for {
      aOne <- one(1)
      anotherOne <- one(1)
      _ <- one(1)
      _ <- one(2)
      _ <- one(3)
      _ <- one(1)
      _ <- Fetch.traverse(List(1, 2, 3))(one)
      _ <- one(1)
    } yield aOne + anotherOne

    val rounds = Fetch.runEnv(fetch, InMemoryCache(
      OneSource.identity(One(1)) -> 1,
      OneSource.identity(One(2)) -> 2,
      OneSource.identity(One(3)) -> 3
    )).value.rounds

    totalFetched(rounds) shouldEqual 0
  }


  case class MyCache(state: Map[Any, Any] = Map.empty[Any, Any]) extends DataSourceCache

  implicit object DC extends Cache[MyCache] {
    override def get[I](c: MyCache, k: DataSourceIdentity): Option[Any] = c.state.get(k)
    override def update[I, A](c: MyCache, k: DataSourceIdentity, v: A): MyCache = c.copy(state = c.state.updated(k, v))
  }

  val fullCache: MyCache = MyCache(Map(
    OneSource.identity(One(1)) -> 1,
    OneSource.identity(One(2)) -> 2,
    OneSource.identity(One(3)) -> 3,
    OneSource.identity(One(1)) -> 1,
    ManySource.identity(Many(2)) -> List(0, 1)
  ))

  "We can use a custom cache" in {
    val fetch = for {
      aOne <- one(1)
      anotherOne <- one(1)
      _ <- one(1)
      _ <- one(2)
      _ <- one(3)
      _ <- one(1)
      _ <- Fetch.traverse(List(1, 2, 3))(one)
      _ <- one(1)
    } yield aOne + anotherOne

    val rounds = Fetch.runEnv(fetch, InMemoryCache(
      OneSource.identity(One(1)) -> 1,
      OneSource.identity(One(2)) -> 2,
      OneSource.identity(One(3)) -> 3,
      ManySource.identity(Many(2)) -> List(0, 1)
    )).value.rounds

    totalFetched(rounds) shouldEqual 0
  }
}

class FetchFutureTests extends AsyncFreeSpec with Matchers{
  import scala.concurrent._
  import scala.concurrent.ExecutionContext.global

  import cats.std.future._

  implicit def executionContext = global
  override def newInstance = new FetchFutureTests

  case class ArticleId(id: Int)
  case class Article(id: Int, content: String) {
    def author: Int = id + 1
  }

  implicit object ArticleFuture extends DataSource[ArticleId, Article, Future] {
    override def name = "ArticleFuture"
    override def fetch(ids: List[ArticleId]): Future[Map[ArticleId, Article]] = {
      Future({
        ids.map(tid => (tid, Article(tid.id, "An article with id " + tid.id))).toMap
      })
    }
  }

  def article(id: Int): Fetch[Article] = Fetch(ArticleId(id))

  case class AuthorId(id: Int)
  case class Author(id: Int, name: String)

  implicit object AuthorFuture extends DataSource[AuthorId, Author, Future] {
    override def name = "AuthorFuture"
    override def fetch(ids: List[AuthorId]): Future[Map[AuthorId, Author]] = {
      Future({
        ids.map(tid => (tid, Author(tid.id, "@egg" + tid.id))).toMap
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
      art <- article(1)
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

    fut.map(_ shouldEqual List(
      Article(1, "An article with id 1"),
      Article(1, "An article with id 1"),
      Article(2, "An article with id 2")
    ))
  }

  "We can use combinators and multiple sources in a for comprehension and interpret a fetch into a future" in {
    val fetch = for {
      articles <- Fetch.traverse(List(1, 1, 2))(article)
      authors <- Fetch.traverse(articles)(author)
    } yield (articles, authors)

    val fut: Future[(List[Article], List[Author])] = Fetch.run(fetch, InMemoryCache.empty)

    fut.map(_ shouldEqual (
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
    ))
  }
}
