import org.specs2.mutable._

import cats.{ MonadError, Eval }
import cats.syntax.comonad._
import fetch._
import fetch.cache._

class FetchSpec extends Specification {
  implicit def IM: MonadError[Eval, Throwable] = new MonadError[Eval, Throwable] {
    override def pure[A](x: A): Eval[A] = Eval.now(x)

    override def ap[A, B](ff: Eval[A ⇒ B])(fa: Eval[A]): Eval[B] = Eval.now(ff.value(fa.value))

    override def map[A, B](fa: Eval[A])(f: A ⇒ B): Eval[B] = fa.map(f)

    override def product[A, B](fa: Eval[A], fb: Eval[B]): Eval[(A, B)] = Eval.now(fa.value, fb.value)

    override def flatMap[A, B](fa: Eval[A])(ff: A => Eval[B]): Eval[B] = fa.flatMap(ff)

    override def raiseError[A](e: Throwable): Eval[A] = Eval.later({
      throw e
    })

    override def handleErrorWith[A](fa: Eval[A])(f: Throwable ⇒ Eval[A]): Eval[A] = {
      try {
        Eval.now(fa.value)
      } catch {
        case e: Throwable ⇒ f(e)
      }
    }
  }

  "Fetch" >> {
    case class One(id: Int)

    implicit object OneSource extends DataSource[One, Int, Eval] {
      override def fetchMany(ids: List[One]): Eval[Map[One, Int]] =
        Eval.now(ids.map(one => (one, one.id)).toMap)
    }

    case class Many(n: Int)

    implicit object ManySource extends DataSource[Many, List[Int], Eval] {
      override def fetchMany(ids: List[Many]): Eval[Map[Many, List[Int]]] =
        Eval.now(ids.map(m => (m, 0 until m.n toList)).toMap)
    }

    "We can lift plain values to Fetch" >> {
      val fetch: Fetch[Int] = Fetch.pure(42)
      Fetch.run(fetch).extract must_== 42
    }

    "We can lift plain values to Fetch and run them with a cache" >> {
      val fetch = Fetch.pure(42)
      Fetch.runCached(fetch).extract must_== 42
    }

    "Data sources with errors throw fetch failures" >> {
      case class Never()

      implicit object NeverSource extends DataSource[Never, Int, Eval] {
        override def fetchMany(ids: List[Never]): Eval[Map[Never, Int]] =
          Eval.now(Map.empty[Never, Int])
      }

      val fetch: Fetch[Int] = Fetch(Never())
      Fetch.run(fetch).extract must throwA(FetchFailure(Cache.empty, List(Never())))
    }

    "Data sources with errors and cached values throw fetch failures with the cache" >> {
      case class Never()

      implicit object NeverSource extends DataSource[Never, Int, Eval] {
        override def fetchMany(ids: List[Never]): Eval[Map[Never, Int]] =
          Eval.now(Map.empty[Never, Int])
      }

      val fetch: Fetch[Int] = Fetch(Never())
      val cache = Cache(One(1) -> 1)
      Fetch.runCached(fetch, cache).extract must throwA(FetchFailure(cache, List(Never())))
    }

    "Data sources with errors won't fail if they're cached" >> {
      case class Never()

      implicit object NeverSource extends DataSource[Never, Int, Eval] {
        override def fetchMany(ids: List[Never]): Eval[Map[Never, Int]] =
          Eval.now(Map.empty[Never, Int])
      }

      val fetch: Fetch[Int] = Fetch(Never())
      val cache = Cache(Never() -> 1)
      Fetch.runCached(fetch, cache).extract must_== 1
    }

    "We can lift errors to Fetch" >> {
      case class NotFound() extends Throwable
      val fetch: Fetch[Int] = Fetch.error(NotFound())
      Fetch.run(fetch).extract must throwA[NotFound]
    }

    "We can lift errors to Fetch and run them with a cache" >> {
      case class NotFound() extends Throwable
      val fetch: Fetch[Int] = Fetch.error(NotFound())
      Fetch.runCached(fetch).extract must throwA[NotFound]
    }

    "We can lift handle and recover from errors in Fetch" >> {
      case class NotFound() extends Throwable
      val fetch: Fetch[Int] = Fetch.error(NotFound())
      IM.handleErrorWith(Fetch.run(fetch))(err => IM.pure(42)).extract must_== 42
    }

    "We can lift errors to Fetch and run them with a cache" >> {
      case class NotFound() extends Throwable
      val fetch: Fetch[Int] = Fetch.error(NotFound())
      IM.handleErrorWith(Fetch.runCached(fetch))(err => IM.pure(42)).extract must_== 42
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
      val fetch = Fetch.traverse(expected)(One(_))
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

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Eval] {
        override def fetchMany(ids: List[TrackedOne]): Eval[Map[TrackedOne, Int]] = {
          batchCount += 1
          Eval.now(ids.map(t => (t, t.x)).toMap)
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

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Eval] {
        override def fetchMany(ids: List[TrackedOne]): Eval[Map[TrackedOne, Int]] = {
          fetchCount += ids.size
          Eval.now(ids.map(t => (t, t.x)).toMap)
        }
      }

      val fetch = Fetch.traverse(List(1, 2, 1))(TrackedOne(_))
      Fetch.run(fetch).extract must_== List(1, 2, 1)
      fetchCount must_== 2
    }

    "Sources that can be fetched in batches inside a for comprehension will be" >> {
      var fetchCount = 0

      case class TrackedOne(x: Int)

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Eval] {
        override def fetchMany(ids: List[TrackedOne]): Eval[Map[TrackedOne, Int]] = {
          fetchCount += ids.size
          Eval.now(ids.map(t => (t, t.x)).toMap)
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

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Eval] {
        override def fetchMany(ids: List[TrackedOne]): Eval[Map[TrackedOne, Int]] = {
          batchCount += 1
          Eval.now(ids.map(t => (t, t.x)).toMap)
        }
      }

      val fetch = Fetch.coalesce(TrackedOne(1), TrackedOne(2))
      Fetch.run(fetch).extract must_== (1, 2)
      batchCount must_== 1
    }

    "Coalesced fetches are deduped" >> {
      var fetchCount = 0

      case class TrackedOne(x: Int)

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Eval] {
        override def fetchMany(ids: List[TrackedOne]): Eval[Map[TrackedOne, Int]] = {
          fetchCount += ids.size
          Eval.now(ids.map(t => (t, t.x)).toMap)
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

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Eval] {
        override def fetchMany(ids: List[TrackedOne]): Eval[Map[TrackedOne, Int]] = {
          fetchCount += ids.size
          Eval.now(ids.map(t => (t, t.x)).toMap)
        }
      }

      case class CachedValue(x: Int)

      implicit object CachedValueSource extends DataSource[CachedValue, Int, Eval] {
        override def fetchMany(ids: List[CachedValue]): Eval[Map[CachedValue, Int]] = {
          fetchCount += ids.size
          Eval.now(ids.map(t => (t, t.x)).toMap)
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

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Eval] {
        override def fetchMany(ids: List[TrackedOne]): Eval[Map[TrackedOne, Int]] = {
          fetchCount += ids.size
          Eval.now(ids.map(t => (t, t.x)).toMap)
        }
      }

      case class CachedValue(x: Int)

      implicit object CachedValueSource extends DataSource[CachedValue, Int, Eval] {
        override def fetchMany(ids: List[CachedValue]): Eval[Map[CachedValue, Int]] = {
          fetchCount += ids.size
          Eval.now(ids.map(t => (t, t.x)).toMap)
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

      Fetch.runCached(fetch, Cache(
        CachedValue(1) -> 1,
        TrackedOne(1) -> 1,
        TrackedOne(2) -> 2
      )).extract must_== 2
      fetchCount must_== 0
    }
  }
}


