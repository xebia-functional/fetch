import org.specs2.mutable._

import cats.{ MonadError }
import cats.data.{ State, Xor }
import cats.syntax.comonad._

import fetch._
import fetch.types._

class FetchSpec extends Specification {
  type Count[A] = State[Int, Xor[Throwable, A]]

  implicit def ISM: MonadError[Count, Throwable] = new MonadError[Count, Throwable] {
    override def pure[A](x: A): Count[A] = State.pure(Xor.Right(x))

    override def map[A, B](fa: Count[A])(f: A ⇒ B): Count[B] = fa.map(_.map(f))

    override def flatMap[A, B](fa: Count[A])(ff: A => Count[B]): Count[B] = State(s => {
      fa.run(s).map(result => {
        val state = result._1
        val xor: Xor[Throwable, A] = result._2
        xor.fold(
          (left: Throwable) => (state, Xor.Left(left)),
          (a: A) => ff(a).run(state).value
        )
      }).value
    })

    override def raiseError[A](e: Throwable): Count[A] = State.pure(Xor.Left(e))

    override def handleErrorWith[A](fa: Count[A])(f: Throwable ⇒ Count[A]): Count[A] = State(s => {
      val (state, result) = fa.run(s).value
      result.fold(
        (left: Throwable) => f(left).run(s).value,
        (a: A) => (state, result)
      )
    })
  }

  "Fetch" >> {
    case object NotFound extends Throwable

    case class One(id: Int)
    implicit object OneSource extends DataSource[One, Int, Count] {
      override def fetchMany(ids: List[One]): Count[Map[One, Int]] =
        ISM.pure(ids.map(one => (one, one.id)).toMap)
    }

    case class Many(n: Int)
    implicit object ManySource extends DataSource[Many, List[Int], Count] {
      override def fetchMany(ids: List[Many]): Count[Map[Many, List[Int]]] =
        ISM.pure(ids.map(m => (m, 0 until m.n toList)).toMap)
    }

    case class Never()
    implicit object NeverSource extends DataSource[Never, Int, Count] {
      override def fetchMany(ids: List[Never]): Count[Map[Never, Int]] =
        ISM.pure(Map.empty[Never, Int])
    }

    case class BatchCountOne(x: Int)

    implicit object BatchCountOneSource extends DataSource[BatchCountOne, Int, Count] {
      override def fetchMany(ids: List[BatchCountOne]): Count[Map[BatchCountOne, Int]] = {
        State.modify((x: Int) => x + 1).flatMap(_ => ISM.pure(ids.map(t => (t, t.x)).toMap))
      }
    }

    case class CountedOne(x: Int)

    implicit object CountedOneSource extends DataSource[CountedOne, Int, Count] {
      override def fetchMany(ids: List[CountedOne]): Count[Map[CountedOne, Int]] = {
        State.modify((x: Int) => x + ids.size).flatMap(_ => ISM.pure(ids.map(t => (t, t.x)).toMap))
      }
    }

    def run[A](f: Count[A]): (Int, Xor[Throwable, A]) = f.run(0).value

    def right[A](f: Count[A]): A = run(f)._2.toOption.get

    def left[A](f: Count[A]): Throwable = run(f)._2.fold(identity, foo => { new Exception() })

    "We can lift plain values to Fetch" >> {
      val fetch: Fetch[Int] = Fetch.pure(42)
      right(Fetch.run(fetch)) must_== 42
    }

    "We can lift plain values to Fetch and run them with a cache" >> {
      val fetch = Fetch.pure(42)
      right(Fetch.runCached(fetch, InMemoryCache.empty)) must_== 42
    }

    "Data sources with errors throw fetch failures" >> {
      val fetch: Fetch[Int] = Fetch(Never())
      left(Fetch.run(fetch)).asInstanceOf[FetchFailure[_, _]].ids must_== List(Never())
    }

    "Data sources with errors and cached values throw fetch failures with the cache" >> {
      val fetch: Fetch[Int] = Fetch(Never())
      val cache = InMemoryCache(
        (OneSource.toString, One(1)) -> 1
      )
      left(Fetch.runCached(fetch, cache)) must_== FetchFailure(Option(cache), List(Never()))
    }

    "Data sources with errors won't fail if they're cached" >> {
      val fetch: Fetch[Int] = Fetch(Never())
      val cache = InMemoryCache(
        (NeverSource.toString, Never()) -> 1
      )
      right(Fetch.runCached(fetch, cache)) must_== 1
    }

    "We can lift errors to Fetch" >> {
      val fetch: Fetch[Int] = Fetch.error(NotFound)
      left(Fetch.run(fetch)) must_== NotFound
    }

    "We can lift errors to Fetch and run them with a cache" >> {
      val fetch: Fetch[Int] = Fetch.error(NotFound)
      left(Fetch.runCached(fetch, InMemoryCache.empty)) must_== NotFound
    }

    "We can lift handle and recover from errors in Fetch" >> {
      val fetch: Fetch[Int] = Fetch.error(NotFound)
      right(
        ISM.handleErrorWith(
          Fetch.run(fetch)
        )(err => ISM.pure(42))
      ) must_== 42
    }

    "We can lift errors to Fetch and run them with a cache" >> {
      val fetch: Fetch[Int] = Fetch.error(NotFound)
      right(
        ISM.handleErrorWith(
          Fetch.run(fetch)
        )(err => ISM.pure(42))
      ) must_== 42
    }

    "We can lift values which have a Data Source to Fetch" >> {
      right(Fetch.run(Fetch(One(1)))) == 1
    }

    "We can lift values which have a Data Source to Fetch and run them with a cache" >> {
      right(Fetch.runCached(Fetch(One(1)), InMemoryCache.empty)) == 1
    }

    "We can map over Fetch values" >> {
      val fetch = Fetch(One(1)).map(_ + 1)
      right(Fetch.run(fetch)) must_== 2
    }

    "We can map over Fetch values and run them with a cache" >> {
      val fetch = Fetch(One(1)).map(_ + 1)
      right(Fetch.run(fetch)) must_== 2
    }

    "We can use fetch inside a for comprehension" >> {
      val ftch = for {
        one <- Fetch(One(1))
        two <- Fetch(One(2))
      } yield (one, two)

      right(Fetch.run(ftch)) == (1, 2)
    }

    "We can mix data sources" >> {
      val ftch = for {
        one <- Fetch(One(1))
        many <- Fetch(Many(3))
      } yield (one, many)

      right(Fetch.run(ftch)) == (1, List(0, 1, 2))
    }

    "We can use Fetch as an applicative" >> {
      import cats.syntax.cartesian._

      val ftch = (Fetch(One(1)) |@| Fetch(Many(3))).map { case (a, b) => (a, b) }

      right(Fetch.run(ftch)) == (1, List(0, 1, 2))
    }

    "We can depend on previous computations of Fetch values" >> {
      val fetch = for {
        one <- Fetch(One(1))
        two <- Fetch(One(one + 1))
      } yield one + two

      right(Fetch.run(fetch)) must_== 3
    }

    "We can collect a list of Fetch into one" >> {
      val sources = List(One(1), One(2), One(3))
      val fetch = Fetch.collect(sources)
      right(Fetch.run(fetch)) must_== List(1, 2, 3)
    }

    "We can collect the results of a traversal" >> {
      val expected = List(1, 2, 3)
      val fetch = Fetch.traverse(expected)(One(_))
      right(Fetch.run(fetch)) must_== expected
    }

    "We can coalesce the results of two fetches into one" >> {
      val expected = (1, 2)
      val fetch = Fetch.coalesce(One(1), One(2))
      right(Fetch.run(fetch)) must_== expected
    }

    "We can join the results of two fetches with different data sources into one" >> {
      val expected = (1, List(0, 1, 2))
      val fetch = Fetch.join(One(1), Many(3))
      right(Fetch.run(fetch)) must_== expected
    }

    // deduplication

    "Duplicated sources are only fetched once" >> {
      val fetch = Fetch.traverse(List(1, 2, 1))(CountedOne(_))

      val (count, result) = run(Fetch.run(fetch))

      result must_== Xor.Right(List(1, 2, 1))
      count must_== 2
    }

    // batching & deduplication

    "Sources that can be fetched in batches will be" >> {
      val fetch = Fetch.traverse(List(1, 2, 1))(BatchCountOne(_))

      val (batches, result) = run(Fetch.run(fetch))

      result must_== Xor.Right(List(1, 2, 1))
      batches must_== 1
    }

    "Sources that can be fetched in batches inside a for comprehension will be" >> {
      val fetch = for {
        v <- Fetch.pure(List(1, 2, 1))
        result <- Fetch.traverse(v)(BatchCountOne(_))
      } yield result

      val (batches, result) = run(Fetch.run(fetch))

      result must_== Xor.Right(List(1, 2, 1))
      batches must_== 1
    }

    "Coalesced fetches are run in a batch" >> {
      val fetch = Fetch.coalesce(BatchCountOne(1), BatchCountOne(2))

      val (batches, result) = run(Fetch.run(fetch))

      result must_== Xor.Right((1, 2))
      batches must_== 1
    }

    "Coalesced fetches are deduped" >> {
      val fetch = Fetch.coalesce(CountedOne(1), CountedOne(1))

      val (count, result) = run(Fetch.run(fetch))

      result must_== Xor.Right((1, 1))
      count must_== 1
    }

    // caching

    "Elements are cached and thus not fetched more than once" >> {
      val fetch = for {
        aOne <- Fetch(CountedOne(1))
        anotherOne <- Fetch(CountedOne(1))
        _ <- Fetch(CountedOne(1))
        _ <- Fetch(CountedOne(2))
        _ <- Fetch(CountedOne(3))
        _ <- Fetch(One(1))
        _ <- Fetch(Many(3))
        _ <- Fetch.collect(List(CountedOne(1), CountedOne(2), CountedOne(3)))
        _ <- Fetch(CountedOne(1))
      } yield aOne + anotherOne

      val (count, result) = run(Fetch.runCached(fetch, InMemoryCache.empty))

      result must_== Xor.Right(2)
      count must_== 3
    }

    "Elements that are cached won't be fetched" >> {
      val fetch = for {
        aOne <- Fetch(CountedOne(1))
        anotherOne <- Fetch(CountedOne(1))
        _ <- Fetch(CountedOne(1))
        _ <- Fetch(CountedOne(2))
        _ <- Fetch(CountedOne(3))
        _ <- Fetch(One(1))
        _ <- Fetch(Many(3))
        _ <- Fetch.collect(List(CountedOne(1), CountedOne(2), CountedOne(3)))
        _ <- Fetch(CountedOne(1))
      } yield aOne + anotherOne

      val (count, result) = run(Fetch.runCached(fetch, InMemoryCache(
        (CountedOneSource.toString, CountedOne(1)) -> 1,
        (CountedOneSource.toString, CountedOne(2)) -> 2,
        (CountedOneSource.toString, CountedOne(3)) -> 3
      )))

      result must_== Xor.Right(2)
      count must_== 0
    }

    // caching with custom caches

    case class FullCache()

    val fullcache: Map[Any, Any] = Map(
      (CountedOneSource.toString, CountedOne(1)) -> 1,
      (CountedOneSource.toString, CountedOne(2)) -> 2,
      (CountedOneSource.toString, CountedOne(3)) -> 3,
      (OneSource.toString, One(1)) -> 1,
      (ManySource.toString, Many(2)) -> List(0, 1)
    )

    implicit object DC extends Cache[FullCache] {
      override def get[I](c: FullCache, k: (String, I)): Option[Any] = fullcache.get(k)
      override def update[I, A](c: FullCache, k: (String, I), v: A): FullCache = c
    }

    "we can use a custom cache" >> {
      val fetch = for {
        aOne <- Fetch(CountedOne(1))
        anotherOne <- Fetch(CountedOne(1))
        _ <- Fetch(CountedOne(1))
        _ <- Fetch(One(1))
        _ <- Fetch(Many(2))
        _ <- Fetch(CountedOne(2))
        _ <- Fetch(CountedOne(3))
        _ <- Fetch.collect(List(CountedOne(1), CountedOne(2), CountedOne(3)))
        _ <- Fetch(CountedOne(1))
      } yield aOne + anotherOne

      val (count, result) = run(Fetch.runCached(fetch, FullCache()))

      result must_== Xor.Right(2)
      count must_== 0
    }
  }
}


class FetchFutureSpec extends Specification {
  import scala.concurrent._
  import scala.concurrent.duration._
  import scala.concurrent.ExecutionContext.Implicits.global

  import cats.std.future._

  case class ArticleId(id: Int)
  case class Article(id: Int, content: String) {
    def author: Int = id + 1
  }
  def article(id: Int): ArticleId = ArticleId(id)

  implicit object ArticleFuture extends DataSource[ArticleId, Article, Future] {
    override def fetchMany(ids: List[ArticleId]): Future[Map[ArticleId, Article]] = {
      Future({
        ids.map(tid => (tid, Article(tid.id, "An article with id " + tid.id))).toMap
      })
    }
  }

  case class AuthorId(id: Int)
  case class Author(id: Int, name: String)
  def author(a: Article): AuthorId = AuthorId(a.author)
  implicit object AuthorFuture extends DataSource[AuthorId, Author, Future] {
    override def fetchMany(ids: List[AuthorId]): Future[Map[AuthorId, Author]] = {
      Future({
        ids.map(tid => (tid, Author(tid.id, "@egg" + tid.id))).toMap
      })
    }
  }

  val fetchArticleAndAuthor: Fetch[(Article, Author)] = for {
    art <- Fetch(article(1))
    author <- Fetch(author(art))
  } yield (art, author)

  val fetchAuthors: Fetch[List[Article]] = for {
    articles <- Fetch.collect(List(article(1), article(1), article(3)))
  } yield articles

  "Fetch futures" >> {
    "We can interpret a fetch into a future" >> {
      val fetchArticle: Fetch[Article] = Fetch(ArticleId(1))
      val article: Future[Article] = Fetch.run(fetchArticle)
      Await.result(article, 1 seconds) must_== Article(1, "An article with id 1")
    }

    "We can combine several data sources and interpret a fetch into a future" >> {
      val fetchArticleAndAuthor: Fetch[(Article, Author)] = for {
        art <- Fetch(article(1))
        author <- Fetch(author(art))
      } yield (art, author)
      val articleAndAuthor: Future[(Article, Author)] = Fetch.run(fetchArticleAndAuthor)
      Await.result(articleAndAuthor, 1 seconds) must_== (Article(1, "An article with id 1"), Author(2, "@egg2"))
    }

    "We can use combinators in a for comprehension and interpret a fetch into a future" >> {
      val fetchArticles: Fetch[List[Article]] = for {
        articles <- Fetch.collect(List(article(1), article(1), article(2)))
      } yield articles
      val articles: Future[List[Article]] = Fetch.run(fetchArticles)
      Await.result(articles, 1 seconds) must_== List(
        Article(1, "An article with id 1"),
        Article(1, "An article with id 1"),
        Article(2, "An article with id 2")
      )
    }

    "We can interpret a fetch into a future with an in-memory cache" >> {
      val fetchArticle: Fetch[Article] = Fetch(ArticleId(1))
      val article: Future[Article] = Fetch.runCached(fetchArticle, InMemoryCache.empty)
      Await.result(article, 1 seconds) must_== Article(1, "An article with id 1")
    }

    "We can combine several data sources and interpret a fetch into a future with an in-memory cache" >> {
      val fetchArticleAndAuthor: Fetch[(Article, Author)] = for {
        art <- Fetch(article(1))
        author <- Fetch(author(art))
      } yield (art, author)
      val articleAndAuthor: Future[(Article, Author)] = Fetch.runCached(fetchArticleAndAuthor, InMemoryCache.empty)
      Await.result(articleAndAuthor, 1 seconds) must_== (Article(1, "An article with id 1"), Author(2, "@egg2"))
    }

    "We can use combinators in a for comprehension and interpret a fetch into a future with an in-memory cache" >> {
      val fetchArticles: Fetch[List[Article]] = for {
        articles <- Fetch.collect(List(article(1), article(1), article(2)))
      } yield articles
      val articles: Future[List[Article]] = Fetch.runCached(fetchArticles, InMemoryCache.empty)
      Await.result(articles, 1 seconds) must_== List(
        Article(1, "An article with id 1"),
        Article(1, "An article with id 1"),
        Article(2, "An article with id 2")
      )
    }

    "We can use combinators and multiple sources in a for comprehension and interpret a fetch into a future with an in-memory cache" >> {
      val fetchArticleAndAuthor = for {
        articles <- Fetch.collect(List(article(1), article(1), article(2)))
        authors <- Fetch.traverse(articles)(author)
      } yield (articles, authors)
      val articleAndAuthor: Future[(List[Article], List[Author])] = Fetch.runCached(fetchArticleAndAuthor, InMemoryCache.empty)
      Await.result(articleAndAuthor, 1 seconds) must_== (
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
    }
  }
}
