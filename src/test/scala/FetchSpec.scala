import org.specs2.mutable._

import cats.Eval
import cats.{ MonadError }
import cats.data.{ State, Xor }
import cats.syntax.comonad._

import fetch._
import fetch.types._
import fetch.cache._

class FetchSpec extends Specification {
  implicit def ISM: MonadError[Eval, Throwable] = new MonadError[Eval, Throwable] {
    override def pure[A](x: A): Eval[A] = Eval.now(x)

    override def map[A, B](fa: Eval[A])(f: A ⇒ B): Eval[B] = fa.map(f)

    override def flatMap[A, B](fa: Eval[A])(ff: A => Eval[B]): Eval[B] = fa.flatMap(ff)

    override def raiseError[A](e: Throwable): Eval[A] = Eval.later({ throw e })

    override def handleErrorWith[A](fa: Eval[A])(f: Throwable ⇒ Eval[A]): Eval[A] = Eval.now({
      try{
        fa.value
      } catch {
        case e: Throwable => f(e).value
      }
    })
  }

  "Fetch" >> {
    case class NotFound() extends Throwable

    case class One(id: Int)
    implicit object OneSource extends DataSource[One, Int, Eval] {
      override def name = "OneSource"
      override def fetch(ids: List[One]): Eval[Map[One, Int]] =
        ISM.pure(ids.map(one => (one, one.id)).toMap)
    }
    def one(id: Int): Fetch[Int] = Fetch(One(id))

    case class AnotherOne(id: Int)
    implicit object AnotheroneSource extends DataSource[AnotherOne, Int, Eval] {
      override def name = "AnotherOneSource"

      override def fetch(ids: List[AnotherOne]): Eval[Map[AnotherOne, Int]] =
        ISM.pure(ids.map(anotherone => (anotherone, anotherone.id)).toMap)
    }
    def anotherOne(id: Int): Fetch[Int] = Fetch(AnotherOne(id))

    case class Many(n: Int)
    implicit object ManySource extends DataSource[Many, List[Int], Eval] {
      override def name = "ManySource"
      override def fetch(ids: List[Many]): Eval[Map[Many, List[Int]]] =
        ISM.pure(ids.map(m => (m, 0 until m.n toList)).toMap)
    }

    case class Never()
    implicit object NeverSource extends DataSource[Never, Int, Eval] {
      override def name = "NeverSource"
      override def fetch(ids: List[Never]): Eval[Map[Never, Int]] =
        ISM.pure(Map.empty[Never, Int])
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

    "We can lift plain values to Fetch" >> {
      val fetch: Fetch[Int] = Fetch.pure(42)
      Fetch.run(fetch).value must_== 42
    }

    "Data sources with errors throw fetch failures" >> {
      val fetch: Fetch[Int] = Fetch(Never())

      Fetch.runEnv(fetch).value must throwA[Throwable].like {
        case FetchFailure(env: Env[_]) => {
          env.rounds.headOption match {
            case Some(Round(_, _, OneRound(id), _, _, _)) => id must_== Never()
          }
        }
      }
    }

    "Data sources with errors throw fetch failures that can be handled" >> {
      val fetch: Fetch[Int] = Fetch(Never())

      ISM.handleErrorWith(
        Fetch.run(fetch)
      )(err => Eval.now({ 42 })).value must_== 42
    }

    "Data sources with errors and cached values throw fetch failures with the cache" >> {
      val fetch: Fetch[Int] = Fetch(Never())
      val cache = InMemoryCache(
        OneSource.identity(One(1)) -> 1
      )
      Fetch.run(fetch, cache).value must throwA[Throwable].like {
        case FetchFailure(env: Env[_]) => env.cache must_== cache
      }
    }

    "Data sources with errors won't fail if they're cached" >> {
      val fetch: Fetch[Int] = Fetch(Never())
      val cache = InMemoryCache(
        NeverSource.identity(Never()) -> 1
      )
      Fetch.run(fetch, cache).value must_== 1
    }

    "We can lift errors to Fetch" >> {
      val fetch: Fetch[Int] = Fetch.error(NotFound())
      Fetch.run(fetch).value must throwA[NotFound]
    }

    "We can lift handle and recover from errors in Fetch" >> {
      val fetch: Fetch[Int] = Fetch.error(NotFound())
      ISM.handleErrorWith(
        Fetch.run(fetch)
      )(err => ISM.pure(42)
      ).value must_== 42
    }

    "We can lift values which have a Data Source to Fetch" >> {
      Fetch.run(Fetch(One(1))).value == 1
    }

    "We can map over Fetch values" >> {
      val fetch = Fetch(One(1)).map(_ + 1)
      Fetch.run(fetch).value must_== 2
    }

    "We can use fetch inside a for comprehension" >> {
      val fetch = for {
        one <- Fetch(One(1))
        two <- Fetch(One(2))
      } yield (one, two)

      Fetch.run(fetch).value == (1, 2)
    }

    "Monadic bind implies sequential execution" >> {
      val fetch = for {
        one <- Fetch(One(1))
        two <- Fetch(One(2))
      } yield (one, two)

      Fetch.runEnv(fetch).value.rounds.size must_== 2
    }

    "We can mix data sources" >> {
      val fetch: Fetch[(Int, List[Int])] = for {
        o <- one(1)
        m <- many(3)
      } yield (o, m)

      Fetch.run(fetch).value == (1, List(0, 1, 2))
    }

    "We can use Fetch as an applicative" >> {
      import cats.syntax.cartesian._

      val fetch: Fetch[(Int, List[Int])] = (one(1) |@| many(3)).map { case (a, b) => (a, b) }

      Fetch.run(fetch).value == (1, List(0, 1, 2))
    }

    "The product of two fetches implies concurrent fetching" >> {
      import cats.syntax.cartesian._

      val fetch: Fetch[(Int, List[Int])] = Fetch.join(one(1), many(3))

      val rounds = Fetch.runEnv(fetch).value.rounds

      concurrent(rounds).size must_== 1
    }

    "If a fetch fails in the left hand of a product the product will fail" >> {
      import cats.syntax.cartesian._

      val fetch: Fetch[(Int, List[Int])] = Fetch.join(Fetch.error(NotFound()), many(3))

      Fetch.run(fetch).value must throwA[NotFound]
    }

    "If a fetch fails in the right hand of a product the product will fail" >> {
      import cats.syntax.cartesian._

      val fetch: Fetch[(List[Int], Int)] = Fetch.join(many(3), Fetch.error(NotFound()))

      Fetch.run(fetch).value must throwA[NotFound]
    }

    "The product of concurrent fetches implies everything fetched concurrently" >> {
      import cats.syntax.cartesian._

      val fetch = Fetch.join(
        Fetch.join(
          one(1),
          Fetch.join(one(2), one(3))
        ),
        many(3)
      )

      val env = Fetch.runEnv(fetch).value
      val rounds = env.rounds

      concurrent(rounds).size must_== 1
      totalBatches(rounds) must_== 2
    }

    "The product of concurrent fetches of the same type implies everything fetched in a single batch" >> {
      import cats.syntax.cartesian._

      val fetch = Fetch.join(
        Fetch.join(
          for {
            a <- one(1)
            b <- one(2)
            c <- one(b)
          } yield c,
          for {
            a <- one(1)
            m <- many(4)
            c <- one(m(1))
          } yield c
        ),
        one(1)
      )

      val env = Fetch.runEnv(fetch).value
      val rounds = env.rounds

      concurrent(rounds).size must_== 1
      totalBatches(rounds) must_== 1
      totalFetched(rounds) must_== 3
    }

    "Every level of joined concurrent fetches is combined and batched" >> {
      import cats.syntax.cartesian._

      val fetch = Fetch.join(
        Fetch.join(
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
        ),
        one(1)
      )

      val env = Fetch.runEnv(fetch).value
      val rounds = env.rounds

      concurrent(rounds).size must_== 3
      totalBatches(rounds) must_== 3
      totalFetched(rounds) must_== 7
    }

    "Every level of collected concurrent of concurrent fetches is batched" >> {
      import cats.syntax.cartesian._

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

      concurrent(rounds).size must_== 3
      totalBatches(rounds) must_== 3
      totalFetched(rounds) must_== 9 + 4 + 6
    }

    "The product of two fetches from the same data source implies batching" >> {
      import cats.syntax.cartesian._

      val fetch: Fetch[(Int, Int)] = Fetch.join(one(1), one(3))

      val rounds = Fetch.runEnv(fetch).value.rounds

      concurrent(rounds).size must_== 1
      totalBatches(concurrent(rounds)) must_== 1
    }

    // todo
    // "Applicative syntax is implicitly concurrent" >> {
    //   import cats.syntax.cartesian._

    //   val fetch: Fetch[(Int, List[Int])] = (Fetch(One(1)) |@| Fetch(Many(3))).tupled

    //   val env = Fetch.runEnv(fetch).value
    //   val rounds = env.rounds

    //   concurrent(rounds).size must_== 1
    // }

    "We can depend on previous computations of Fetch values" >> {
      val fetch: Fetch[Int] = for {
        o <- one(1)
        t <- one(o + 1)
      } yield o + t

      Fetch.run(fetch).value must_== 3
    }

    "We can collect a list of Fetch into one" >> {
      val sources: List[Fetch[Int]] = List(one(1), one(2), one(3))
      val fetch: Fetch[List[Int]] = Fetch.collect(sources)
      Fetch.run(fetch).value must_== List(1, 2, 3)
    }

    "We can collect a list of Fetches with heterogeneous sources" >> {
      val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), anotherOne(4), anotherOne(5))
      val fetch: Fetch[List[Int]] = Fetch.collect(sources)
      Fetch.run(fetch).value must_== List(1, 2, 3, 4, 5)
    }

    "Collected fetches are run concurrently" >> {
      val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), anotherOne(4), anotherOne(5))
      val fetch: Fetch[List[Int]] = Fetch.collect(sources)

      val rounds = Fetch.runEnv(fetch).value.rounds

      concurrent(rounds).size must_== 1
      totalBatches(rounds) must_== 2
    }

    "Collected fetches are deduped" >> {
      val sources: List[Fetch[Int]] = List(one(1), one(2), one(1))
      val fetch: Fetch[List[Int]] = Fetch.collect(sources)

      val rounds = Fetch.runEnv(fetch).value.rounds

      totalFetched(concurrent(rounds)) must_== 2
      concurrent(rounds).size must_== 1
    }

    "Collected fetches are not asked for when cached" >> {
      val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), one(4))
      val fetch: Fetch[List[Int]] = Fetch.collect(sources)

      val rounds = Fetch.runEnv(fetch, InMemoryCache(
        OneSource.identity(One(1)) -> 1,
        OneSource.identity(One(2)) -> 2
      )).value.rounds

      totalFetched(concurrent(rounds)) must_== 2
      concurrent(rounds).size must_== 1
    }

    "We can collect the results of a traversal" >> {
      val expected = List(1, 2, 3)

      val fetch = Fetch.traverse(expected)(one)

      Fetch.run(fetch).value must_== expected
    }

    "Traversals are run concurrently" >> {
      val fetch = Fetch.traverse(List(1, 2, 3))(one)

      val rounds = Fetch.runEnv(fetch).value.rounds

      concurrent(rounds).size must_== 1
    }

    // deduplication

    "Duplicated sources are only fetched once" >> {
      val fetch = Fetch.traverse(List(1, 2, 1))(one)

      val rounds = Fetch.runEnv(fetch).value.rounds

      concurrent(rounds).size must_== 1
      totalFetched(concurrent(rounds)) must_== 2
    }

    // batching & deduplication

    "Sources that can be fetched concurrently inside a for comprehension will be" >> {
      val fetch = for {
        v <- Fetch.pure(List(1, 2, 1))
        result <- Fetch.traverse(v)(one)
      } yield result

      val rounds = Fetch.runEnv(fetch).value.rounds

      concurrent(rounds).size must_== 1
      totalFetched(concurrent(rounds)) must_== 2
    }

    // caching

    "Elements are cached and thus not fetched more than once" >> {
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

      totalFetched(rounds) must_== 3
    }

    "Elements that are cached won't be fetched" >> {
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

      totalFetched(rounds) must_== 0
    }

    // caching with custom caches

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

    "we can use a custom cache" >> {
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

      totalFetched(rounds) must_== 0
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

  implicit object ArticleFuture extends DataSource[ArticleId, Article, Future] {
    override def name = "ArticleFuture"
    override def fetch(ids: List[ArticleId]): Future[Map[ArticleId, Article]] = {
      Future({
        // val threadId = Thread.currentThread().getId()
        // val wait = scala.util.Random.nextInt(100)

        // println("->[" + threadId + "] ArticleFuture: " + ids + " will take " + wait)
        // Thread.sleep(wait)
        // println("<-[" + threadId + "] ArticleFuture: " + ids)

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
        // val threadId = Thread.currentThread().getId()
        // val wait = scala.util.Random.nextInt(100)

        // println("->[" + threadId + "] AuthorFuture: " + ids + " will take " + wait)
        // Thread.sleep(wait)
        // println("<-[" + threadId + "] AuthorFuture: " + ids)

        ids.map(tid => (tid, Author(tid.id, "@egg" + tid.id))).toMap
      })
    }
  }

  def author(a: Article): Fetch[Author] = Fetch(AuthorId(a.author))


  "Fetch futures" >> {
    "We can interpret a fetch into a future" >> {
      val fetch: Fetch[Article] = article(1)

      val fut: Future[Article] = Fetch.run(fetch)

      Await.result(fut, 1 seconds) must_== Article(1, "An article with id 1")
    }

    "We can combine several data sources and interpret a fetch into a future" >> {
      val fetch: Fetch[(Article, Author)] = for {
        art <- article(1)
        author <- author(art)
      } yield (art, author)

      val fut: Future[(Article, Author)] = Fetch.run(fetch)

      Await.result(fut, 1 seconds) must_== (Article(1, "An article with id 1"), Author(2, "@egg2"))
    }


    "We can use combinators in a for comprehension and interpret a fetch into a future" >> {
      val fetch: Fetch[List[Article]] = for {
        articles <- Fetch.traverse(List(1, 1, 2))(article)
      } yield articles

      val fut: Future[List[Article]] = Fetch.run(fetch)

      Await.result(fut, 1 seconds) must_== List(
        Article(1, "An article with id 1"),
        Article(1, "An article with id 1"),
        Article(2, "An article with id 2")
      )
    }

    "We can use combinators and multiple sources in a for comprehension and interpret a fetch into a future" >> {
      val fetch = for {
        articles <- Fetch.traverse(List(1, 1, 2))(article)
        authors <- Fetch.traverse(articles)(author)
      } yield (articles, authors)

      val fut: Future[(List[Article], List[Author])] = Fetch.run(fetch, InMemoryCache.empty)

      Await.result(fut, 1 seconds) must_== (
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
