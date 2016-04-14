import org.specs2.mutable._

import cats._
import fetch._


class FetchSpec extends Specification {
  implicit def applicativeErrorId(
    implicit
      I: Applicative[cats.Id]
  ): ApplicativeError[Id, Throwable] = new ApplicativeError[Id, Throwable](){
    override def pure[A](x: A): Id[A] = I.pure(x)

    override def ap[A, B](ff: Id[A ⇒ B])(fa: Id[A]): Id[B] = I.ap(ff)(fa)

    override def map[A, B](fa: Id[A])(f: Id[A ⇒ B]): Id[B] = I.map(fa)(f)

    override def product[A, B](fa: Id[A], fb: Id[B]): Id[(A, B)] = I.product(fa, fb)

    override def raiseError[A](e: Throwable): Id[A] =
      throw e

    override def handleErrorWith[A](fa: Id[A])(f: Throwable ⇒ Id[A]): Id[A] = {
      try {
        fa
      } catch {
        case e: Exception ⇒ f(e)
      }
    }
  }

  "Fetch" >> {
    case class One(id: Int)

    implicit object OneSource extends DataSource[One, Int, Id] {
      override def fetchMany(ids: List[One]): Id[Map[One, Int]] =
        ids.map(one => (one, one.id)).toMap
    }

    case class Many(n: Int)

    implicit object ManySource extends DataSource[Many, List[Int], Id] {
      override def fetchMany(ids: List[Many]): Id[Map[Many, List[Int]]] =
        ids.map(m => (m, 0 until m.n toList)).toMap
    }

    "We can lift plain values to Fetch" >> {
      val fetch = Fetch.pure(42)
      Fetch.run(fetch) must_== 42
    }

    "We can lift errors to Fetch" >> {
      val exception = new Exception("OH NOES")
      val fetch = Fetch.error[Int](exception)
      Fetch.run(fetch) must throwA(exception)
    }

    "We can lift values which have a Data Source to Fetch" >> {
      Fetch.run(Fetch(One(1))) == 1
    }

    "We can map over Fetch values" >> {
      val fetch = Fetch(One(1)).map((x: Int) => x + 1)
      Fetch.run(fetch) must_== 2
    }

    "We can use fetch inside a for comprehension" >> {
      val ftch = for {
        one <- Fetch(One(1))
        two <- Fetch(One(2))
      } yield (one, two)

      Fetch.run(ftch) == (1, 2)
    }

    "We can mix data sources" >> {
      val ftch = for {
        one <- Fetch(One(1))
        many <- Fetch(Many(3))
      } yield (one, many)

      Fetch.run(ftch) == (1, List(0, 1, 2))
    }

    "We can use Fetch as an applicative" >> {
      import cats.syntax.cartesian._

      val ftch = (Fetch(One(1)) |@| Fetch(Many(3))).map { case (a, b) => (a, b) }

      Fetch.run(ftch) == (1, List(0, 1, 2))
    }

    "We can depend on previous computations of Fetch values" >> {
      val fetch = for {
        one <- Fetch(One(1))
        two <- Fetch(One(one + 1))
      } yield one + two

      Fetch.run(fetch) must_== 3
    }

    "We can collect a list of Fetch into one" >> {
      val sources = List(One(1), One(2), One(3))
      val fetch = Fetch.collect(sources)
      Fetch.run(fetch) must_== List(1, 2, 3)
    }

    "We can collect the results of a traversal" >> {
      val expected = List(1, 2, 3)
      val fetch = Fetch.traverse(expected)((x: Int) => One(x))
      Fetch.run(fetch) must_== expected
    }

    "We can join the results of two fetches into one" >> {
      val expected = (1, 2)
      val fetch = Fetch.join(One(1), One(2))
      Fetch.run(fetch) must_== expected
    }

    "We can join the results of two fetches with different data sources into one" >> {
      val expected = (1, List(0, 1, 2))
      val fetch = Fetch.join(One(1), Many(3))
      Fetch.run(fetch) must_== expected
    }

    // deduplication

    "Duplicated sources are only fetched once" >> {
      var batchCount = 0

      case class TrackedOne(x: Int)

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Id] {
        override def fetchMany(ids: List[TrackedOne]): Id[Map[TrackedOne, Int]] = {
          batchCount += 1
          ids.map(t => (t, t.x)).toMap
        }
      }

      val fetch = Fetch.traverse(List(1, 2, 1))((x: Int) => TrackedOne(x))

      Fetch.run(fetch) must_== List(1, 2, 1)
      batchCount must_== 1
    }

    // batching & deduplication

    "Sources that can be fetched in batches will be" >> {
      var fetchCount = 0

      case class TrackedOne(x: Int)

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Id] {
        override def fetchMany(ids: List[TrackedOne]): Id[Map[TrackedOne, Int]] = {
          fetchCount += ids.size
          ids.map(t => (t, t.x)).toMap
        }
      }

      val fetch = Fetch.traverse(List(1, 2, 1))((x: Int) => TrackedOne(x))
      Fetch.run(fetch) must_== List(1, 2, 1)
      fetchCount must_== 2
    }

    "Sources that can be fetched in batches inside a for comprehension will be" >> {
      var fetchCount = 0

      case class TrackedOne(x: Int)

      implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Id] {
        override def fetchMany(ids: List[TrackedOne]): Id[Map[TrackedOne, Int]] = {
          fetchCount += ids.size
          ids.map(t => (t, t.x)).toMap
        }
      }

      val fetch = for {
        v <- Fetch.pure(List(1, 2, 1))
        result <- Fetch.traverse(v)((x: Int) => TrackedOne(x))
      } yield result

      Fetch.run(fetch) must_== List(1, 2, 1)
      fetchCount must_== 2
    }

    // "Joined fetches are run concurrently" >> {
    //   var batchCount = 0

    //   case class TrackedOne(x: Int)

    //   implicit object TrackedOneSource extends DataSource[TrackedOne, Int, Id] {
    //     override def fetchMany(ids: List[TrackedOne]): Id[Map[TrackedOne, Int]] = {
    //       batchCount += 1
    //       ids.map(t => (t, t.x)).toMap
    //     }
    //   }

    //   val fetch = Fetch.join(TrackedOne(1), TrackedOne(2))
    //   Fetch.run(fetch) must_== (1, 2)
    //   batchCount must_== 1
    // }

  // // caching

  // "Elements are cached and thus not fetched more than once" >> {
  //   var fetchCount = 0

  //   case class CachedValue(x: Int) extends DataSource[Int] {
  //     override def identity = x.toString
  //     override def fetch = {
  //       fetchCount += 1
  //       Future.successful(x)
  //     }
  //   }

  //   val fetch = for {
  //     aOne <- Fetch(CachedValue(1))
  //     anotherOne <- Fetch(CachedValue(1))
  //     _ <- Fetch.traverse(List(1, 1, 1))((x: Int) => Fetch(CachedValue(x)))
  //   } yield aOne + anotherOne

  //   deref(Fetch.run(fetch)) must_== 2
  //   fetchCount must_== 1
  // }
  }
}


