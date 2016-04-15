import org.specs2.mutable._

import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import cats.{ MonadError, Comonad }
import cats.syntax.comonad._
import fetch._

class FetchScalaFutureSpec extends Specification {
  implicit def FM: MonadError[Future, Throwable] with Comonad[Future] = new MonadError[Future, Throwable] with Comonad[Future]{
    override def pure[A](x: A): Future[A] = Future.successful(x)

    override def coflatMap[A, B](fa: Future[A])(f: Future[A] => B): Future[B] = Future.successful(f(fa))

    override def extract[A](fa: Future[A]): A = Await.result(fa, 3 seconds)

    override def flatMap[A, B](fa: Future[A])(ff: A => Future[B]): Future[B] = fa.flatMap(ff)

    override def raiseError[A](e: Throwable): Future[A] = Future.failed(e)

    override def handleErrorWith[A](fa: Future[A])(f: Throwable ⇒ Future[A]): Future[A] = fa.recoverWith({
      case e => f(e.asInstanceOf[Throwable])
    })
  }

  "Fetch futures" >> {
    case class One(id: Int)

    implicit object OneSource extends DataSource[One, Int, Future] {
      override def fetchMany(ids: List[One]): Future[Map[One, Int]] =
        Future.successful(ids.map(one => (one, one.id)).toMap)
    }

    case class Many(n: Int)

    implicit object ManySource extends DataSource[Many, List[Int], Future] {
      override def fetchMany(ids: List[Many]): Future[Map[Many, List[Int]]] =
        Future.successful(ids.map(m => (m, 0 until m.n toList)).toMap)
    }

    "We can lift plain values to Fetch" >> {
      val fetch: Fetch[Int] = Fetch.pure(42)
      Fetch.run(fetch).extract must_== 42
    }

    "We can lift plain values to Fetch and run them with a cache" >> {
      val fetch: Fetch[Int] = Fetch.pure(42)
      Fetch.runCached(fetch).extract must_== 42
    }

    "We can lift errors to Fetch" >> {
      case class NotFound() extends Throwable
      val fetch: Fetch[Int] = Fetch.error(NotFound())
      Fetch.run(fetch).extract must throwA[NotFound]
    }

    "We can lift handle and recover from errors in Fetch" >> {
      case class NotFound() extends Throwable
      val fetch: Fetch[Int] = Fetch.error(NotFound())
      FM.handleErrorWith(Fetch.run(fetch))(err => Future.successful(42)).extract must_== 42
    }

    "We can lift errors to Fetch and run them with a cache" >> {
      case class NotFound() extends Throwable
      val fetch: Fetch[Int] = Fetch.error(NotFound())
      FM.handleErrorWith(Fetch.runCached(fetch))(err => Future.successful(42)).extract must_== 42    
    }

    "We can lift values which have a Data Source to Fetch" >> {
      Fetch.run(Fetch(One(1))).extract == 1
    }

    "We can lift values which have a Data Source to Fetch and run them with a cache" >> {
      Fetch.runCached(Fetch(One(1))).extract == 1
    }

    "We can map over Fetch values" >> {
      val fetch = Fetch(One(1)).map(_ + 1)
      Fetch.run(fetch).extract must_== 2
    }

    "We can map over Fetch values and run them with a cache" >> {
      val fetch = Fetch(One(1)).map(_ + 1)
      Fetch.run(fetch).extract must_== 2
    }

    "We can use fetch inside a for comprehension" >> {
      val ftch = for {
        one <- Fetch(One(1))
        two <- Fetch(One(2))
      } yield (one, two)

      Fetch.run(ftch).extract == (1, 2)
    }

    "We can mix data sources" >> {
      val ftch = for {
        one <- Fetch(One(1))
        many <- Fetch(Many(3))
      } yield (one, many)

      Fetch.run(ftch).extract == (1, List(0, 1, 2))
    }

    "We can use Fetch as an applicative" >> {
      import cats.syntax.cartesian._

      val ftch = (Fetch(One(1)) |@| Fetch(Many(3))).map { case (a, b) => (a, b) }

      Fetch.run(ftch).extract == (1, List(0, 1, 2))
    }

    "We can depend on previous computations of Fetch values" >> {
      val fetch = for {
        one <- Fetch(One(1))
        two <- Fetch(One(one + 1))
      } yield one + two

      Fetch.run(fetch).extract must_== 3
    }

    "We can collect a list of Fetch into one" >> {
      val sources = List(One(1), One(2), One(3))
      val fetch = Fetch.collect(sources)
      Fetch.run(fetch).extract must_== List(1, 2, 3)
    }

    "We can collect the results of a traversal" >> {
      val expected = List(1, 2, 3)
      val fetch = Fetch.traverse(expected)((x: Int) => One(x))
      Fetch.run(fetch).extract must_== expected
    }

    "We can coalesce the results of two fetches into one" >> {
      val expected = (1, 2)
      val fetch = Fetch.coalesce(One(1), One(2))
      Fetch.run(fetch).extract must_== expected
    }

    "We can join the results of two fetches with different data sources into one" >> {
      val expected = (1, List(0, 1, 2))
      val fetch = Fetch.join(One(1), Many(3))
      Fetch.run(fetch).extract must_== expected
    }

    // deduplication

    "Duplicated sources are only fetched once" >> {
      var batchCount = 0

      case class TrackedOne(x: Int)

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Future] {
        override def fetchMany(ids: List[TrackedOne]): Future[Map[TrackedOne, Int]] = {
          batchCount += 1
          Future.successful(ids.map(t => (t, t.x)).toMap)
        }
      }

      val fetch = Fetch.traverse(List(1, 2, 1))(TrackedOne(_))

      Fetch.run(fetch).extract must_== List(1, 2, 1)
      batchCount must_== 1
    }

    // batching & deduplication

    "Sources that can be fetched in batches will be" >> {
      var fetchCount = 0

      case class TrackedOne(x: Int)

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Future] {
        override def fetchMany(ids: List[TrackedOne]): Future[Map[TrackedOne, Int]] = {
          fetchCount += ids.size
          Future.successful(ids.map(t => (t, t.x)).toMap)
        }
      }

      val fetch = Fetch.traverse(List(1, 2, 1))(TrackedOne(_))
      Fetch.run(fetch).extract must_== List(1, 2, 1)
      fetchCount must_== 2
    }

    "Sources that can be fetched in batches inside a for comprehension will be" >> {
      var fetchCount = 0

      case class TrackedOne(x: Int)

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Future] {
        override def fetchMany(ids: List[TrackedOne]): Future[Map[TrackedOne, Int]] = {
          fetchCount += ids.size
          Future.successful(ids.map(t => (t, t.x)).toMap)
        }
      }

      val fetch = for {
        v <- Fetch.pure(List(1, 2, 1))
        result <- Fetch.traverse(v)(TrackedOne(_))
      } yield result

      Fetch.run(fetch).extract must_== List(1, 2, 1)
      fetchCount must_== 2
    }

    "Coalesced fetches are run concurrently" >> {
      var batchCount = 0

      case class TrackedOne(x: Int)

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Future] {
        override def fetchMany(ids: List[TrackedOne]): Future[Map[TrackedOne, Int]] = {
          batchCount += 1
          Future.successful(ids.map(t => (t, t.x)).toMap)
        }
      }

      val fetch = Fetch.coalesce(TrackedOne(1), TrackedOne(2))
      Fetch.run(fetch).extract must_== (1, 2)
      batchCount must_== 1
    }

    "Coalesced fetches are deduped" >> {
      var fetchCount = 0

      case class TrackedOne(x: Int)

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Future] {
        override def fetchMany(ids: List[TrackedOne]): Future[Map[TrackedOne, Int]] = {
          fetchCount += ids.size
          Future.successful(ids.map(t => (t, t.x)).toMap)
        }
      }

      val fetch = Fetch.coalesce(TrackedOne(1), TrackedOne(1))
      Fetch.run(fetch).extract must_== (1, 1)
      fetchCount must_== 1
    }

    // caching

    "Elements are cached and thus not fetched more than once" >> {
      var fetchCount = 0

      case class TrackedOne(x: Int)

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Future] {
        override def fetchMany(ids: List[TrackedOne]): Future[Map[TrackedOne, Int]] = {
          fetchCount += ids.size
          Future.successful(ids.map(t => (t, t.x)).toMap)
        }
      }

      case class CachedValue(x: Int)

      implicit object CachedValueSource extends DataSource[CachedValue, Int, Future] {
        override def fetchMany(ids: List[CachedValue]): Future[Map[CachedValue, Int]] = {
          fetchCount += ids.size
          Future.successful(ids.map(t => (t, t.x)).toMap)
        }
      }

      val fetch = for {
        aOne <- Fetch(CachedValue(1))
        anotherOne <- Fetch(CachedValue(1))
        _ <- Fetch(TrackedOne(1))
        _ <- Fetch(TrackedOne(2))
        _ <- Fetch(TrackedOne(1))
        _ <- Fetch.collect(List(CachedValue(1)))
        _ <- Fetch(CachedValue(1))
      } yield aOne + anotherOne

      Fetch.runCached(fetch).extract must_== 2
      fetchCount must_== 3
    }

    "Elements that are cached won't be fetched" >> {
      var fetchCount = 0

      case class TrackedOne(x: Int)

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Future] {
        override def fetchMany(ids: List[TrackedOne]): Future[Map[TrackedOne, Int]] = {
          fetchCount += ids.size
          Future.successful(ids.map(t => (t, t.x)).toMap)
        }
      }

      case class CachedValue(x: Int)

      implicit object CachedValueSource extends DataSource[CachedValue, Int, Future] {
        override def fetchMany(ids: List[CachedValue]): Future[Map[CachedValue, Int]] = {
          fetchCount += ids.size
          Future.successful(ids.map(t => (t, t.x)).toMap)
        }
      }

      val fetch = for {
        aOne <- Fetch(CachedValue(1))
        anotherOne <- Fetch(CachedValue(1))
        _ <- Fetch(TrackedOne(1))
        _ <- Fetch(TrackedOne(2))
        _ <- Fetch(TrackedOne(1))
        _ <- Fetch.collect(List(CachedValue(1)))
        _ <- Fetch(CachedValue(1))
      } yield aOne + anotherOne

      Fetch.runCached(fetch, Fetch.Cache(
        CachedValue(1) -> 1,
        TrackedOne(1) -> 1,
        TrackedOne(2) -> 2
      )).extract must_== 2
      fetchCount must_== 0
    }
  }
}


