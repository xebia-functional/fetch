package bench

import fetch._
import fetch.unsafe.implicits._
import fetch.syntax._
import cats.{Id, Eval}
import cats.data.NonEmptyList

import org.scalameter.api._
import org.scalameter.Measurer._

object FetchBench {
  implicit object ImmediateDataSource extends DataSource[Int, Int] {
    def name                                  = "Immediate"
    def fetchOne(id: Int): Query[Option[Int]] = Query.eval(Eval.now(Option(id)))
    def fetchMany(ids: NonEmptyList[Int]): Query[Map[Int, Int]] =
      Query.eval(Eval.now(ids.toList.map((i) => (i, i)).toMap))
  }

  def deepFetch(depth: Int): Fetch[Int] =
    (0 to depth)
      .map(Fetch(_)(ImmediateDataSource))
      .foldLeft(Fetch.pure(42))((acc, f) => acc.flatMap((x) => f))
}

object TimeBench extends Bench.ForkedTime {
  import FetchBench._

  val depths = Gen.range("depth")(1, 10000, 1000)

  performance of "Interpreter overhead" in {
    measure method "runA" in {
      using(depths) in { (d) =>
        deepFetch(d).runA[Id]
      }
    }
  }
}

object MemBench extends Bench.ForkedTime {
  import FetchBench._
  override def measurer = new Executor.Measurer.MemoryFootprint

  val depths = Gen.range("depth")(1, 10000, 1000)

  performance of "Memory" in {
    measure method "runA" in {
      using(depths) in { (d) =>
        deepFetch(d).runA[Id]
      }
    }
  }
}
