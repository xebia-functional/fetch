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

    "We can lift plain values to Fetch" >> {
      val fetch = Fetch.pure(42)
      Fetch.run(fetch) must_== 42
    }

    // "We can lift values which have a Data Source to Fetch" >> {
    //   Fetch.run(Fetch(One(1))) == 1
    // }

  //   "We can map over Fetch values" >> {
  //     val fetch = Fetch(One(1)).map((x: Int) => x + 1)
  //     Fetch.run(fetch) must_== Some(2)
  //   }

  //   "many" >> {
  //     import cats.implicits._

  //     println("DEPS " + Fetch.dependencies(Fetch.many(List(1, 2, 3))).groupBy(_.ds))

  //     Fetch.run(Fetch.many(List(1, 2, 3))) == List(1, 2, 3)
  //   }

  //   "for comprehension" >> {
  //     val ftch = for {
  //       one <- Fetch.one(1)
  //       many <- Fetch.many(List(1, 2, 3))
  //     } yield (one, many)

  //     Fetch.run(ftch) == (Some(1), List(1, 2, 3))
  //   }

  //   "mixing data sources" >> {
  //     val ftch = for {
  //       one <- Fetch.one(1)
  //       two <- Fetch.one("yolo")
  //     } yield (one, two)

  //     Fetch.run(ftch) == (Some(1), Some("yolo"))
  //   }

  //   "Fetch as an applicative" >> {
  //     import cats.syntax.all._

  //     val ftch = (Fetch.one(1) |@| Fetch.one("yolo")).map { case (a, b) => (a, b) }

  //     import cats.implicits._
  //     println("DEPS " + Fetch.dependencies(ftch).groupBy(_.ids))

  //     Fetch.run(ftch) == (Some(1), Some("yolo"))
  //   }
  // }

  // "We can flatmap over Fetch values" >> {
  //   val fetch = for {
  //     one <- Fetch(One(1))
  //     two <- Fetch(One(one + 1))
  //   } yield one + two
  //   deref(Fetch.run(fetch)) must_== 3
  // }

  // "We can collect a list of Fetch into one" >> {
  //   val sources = List(One(1), One(2), One(3))
  //   val fetch = Fetch.collect(sources.map(Fetch(_)))
  //   deref(Fetch.run(fetch)) must_== List(1, 2, 3)
  // }

  // "We can collect the results of a traversal" >> {
  //   val expected = List(1, 2, 3)
  //   val fetch = Fetch.traverse(expected)((x: Int) => Fetch(One(x)))
  //   deref(Fetch.run(fetch)) must_== expected
  // }

  // "We can join the results of two fetches into one" >> {
  //   val expected = (1, 2)
  //   val fetch = Fetch.join(Fetch(One(1)), Fetch(One(2)))
  //   deref(Fetch.run(fetch)) must_== expected
  // }

  // // deduplication

  // "Duplicated sources are only fetched once" >> {
  //   var fetchCount = 0

  //   case class TrackedOne(x: Int) extends DataSource[Int] {
  //     def identity = x.toString
  //     def fetch = {
  //       fetchCount += 1
  //       Future.successful(x)
  //     }
  //   }

  //   val fetch = Fetch.traverse(List(1, 2, 1))((x: Int) => Fetch(TrackedOne(x)))

  //   deref(Fetch.run(fetch)) must_== List(1, 2, 1)
  //   fetchCount must_== 2
  // }

  // // batching & deduplication

  // "Sources that can be fetched in batches will be" >> {
  //   var batchedCount = 0

  //   case class BatchedOne(x: Int) extends DataSource[Int] {
  //     override def identity = x.toString
  //     override def fetch = {
  //       Future.successful(x)
  //     }
  //     override def fetchMulti(sources: List[DataSource[Int]]): Future[List[Int]] = {
  //       batchedCount += sources.size
  //       Future.successful(sources.asInstanceOf[List[BatchedOne]].map(_.x))
  //     }
  //   }

  //   val fetch = Fetch.traverse(List(1, 2, 1))((x: Int) => Fetch(BatchedOne(x)))

  //   deref(Fetch.run(fetch)) must_== List(1, 2, 1)
  //   batchedCount must_== 2
  // }

  // "Joined fetches are run concurrently" >> {
  //   var batchedCount = 0

  //   case class BatchedOne(x: Int) extends DataSource[Int] {
  //     override def identity = x.toString
  //     override def fetch = {
  //       Future.successful(x)
  //     }
  //     override def fetchMulti(sources: List[DataSource[Int]]): Future[List[Int]] = {
  //       batchedCount += sources.size
  //       Future.successful(sources.asInstanceOf[List[BatchedOne]].map(_.x))
  //     }
  //   }

  //   val fetch = Fetch.join(Fetch(BatchedOne(1)), Fetch(BatchedOne(2)))
  //   deref(Fetch.run(fetch)) must_== (1, 2)
  //   batchedCount must_== 2
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


