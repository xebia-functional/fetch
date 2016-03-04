import org.specs2.mutable._

import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import fetch._

case class One(a: Int) extends DataSource[Int] {
  override def identity = a.toString
  override def fetch = Future.successful(a)
}

/** This is the "Unit" style for specifications */
class FetchSpec extends Specification {

  def deref[A](f: Future[A]): A =
    Await.result(
      f,
      1 seconds
    )

  "We can lift values to Fetch" >> {
    val fetch = Fetch.pure(42)
    deref(Fetch.run(fetch)) must_== 42
  }

  "We can lift data sources to Fetch" >> {
    val fetch = Fetch(One(1))
    deref(Fetch.run(fetch)) must_== 1
  }

  "We can map over Fetch values" >> {
    val fetch = Fetch(One(1)).map((x: Int) => x + 1)
    deref(Fetch.run(fetch)) must_== 2
  }

  "We can flatmap over Fetch values" >> {
    val fetch = for {
      one <- Fetch(One(1))
      two <- Fetch(One(one + 1))
    } yield one + two
    deref(Fetch.run(fetch)) must_== 3
  }

  "We can collect a list of Fetch into one" >> {
    val sources = List(One(1), One(2), One(3))
    val fetch = Fetch.collect(sources.map(Fetch(_)))
    deref(Fetch.run(fetch)) must_== List(1, 2, 3)
  }

  "We can collect the results of a traversal" >> {
    val expected = List(1, 2, 3)
    val fetch = Fetch.traverse(expected)((x: Int) => Fetch(One(x)))
    deref(Fetch.run(fetch)) must_== expected
  }

  // deduplication

  "Duplicated sources are only fetched once" >> {
    var fetchCount = 0

    case class TrackedOne(x: Int) extends DataSource[Int] {
      def identity = x.toString
      def fetch = {
        fetchCount += 1
        Future.successful(x)
      }
    }

    val fetch = Fetch.traverse(List(1, 2, 1))((x: Int) => Fetch(TrackedOne(x)))

    deref(Fetch.run(fetch)) must_== List(1, 2, 1)
    fetchCount must_== 2
  }

  // batching & deduplication

  "Sources that can be fetched in batches will be" >> {
    var batchedCount = 0

    case class BatchedOne(x: Int) extends DataSource[Int] {
      override def identity = x.toString
      override def fetch = {
        Future.successful(x)
      }
      override def fetchMulti(sources: List[DataSource[Int]]): Future[List[Int]] = {
        batchedCount += sources.size
        Future.successful(sources.asInstanceOf[List[BatchedOne]].map(_.x))
      }
    }

    val fetch = Fetch.traverse(List(1, 2, 1))((x: Int) => Fetch(BatchedOne(x)))

    deref(Fetch.run(fetch)) must_== List(1, 2, 1)
    batchedCount must_== 2
  }

  // caching

  "Elements are cached and thus not fetched more than once" >> {
    var fetchCount = 0

    case class CachedValue(x: Int) extends DataSource[Int] {
      override def identity = x.toString
      override def fetch = {
        fetchCount += 1
        Future.successful(x)
      }
    }

    val fetch = for {
      aOne <- Fetch(CachedValue(1))
      anotherOne <- Fetch(CachedValue(1))
      _ <- Fetch.traverse(List(1, 1, 1))((x: Int) => Fetch(CachedValue(x)))
    } yield aOne + anotherOne

    deref(Fetch.run(fetch)) must_== 2
    fetchCount must_== 1
  }

}


