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

import scala.concurrent._
import scala.concurrent.duration._

import org.scalatest._

import monix.eval._
import monix.execution.Scheduler
import cats.data.NonEmptyList
import cats.std.list._
import fetch._

object TestHelper {
  import fetch.syntax._

  case class NotFound() extends Throwable

  case class One(id: Int)
  implicit object OneSource extends DataSource[One, Int] {
    override def name = "OneSource"
    override def fetchOne(id: One): Task[Option[Int]] = {
      Task.pure(Option(id.id))
    }
    override def fetchMany(ids: NonEmptyList[One]): Task[Map[One, Int]] =
      Task.pure(ids.unwrap.map(one => (one, one.id)).toMap)
  }
  def one(id: Int): Fetch[Int] = Fetch(One(id))

  case class AnotherOne(id: Int)
  implicit object AnotheroneSource extends DataSource[AnotherOne, Int] {
    override def name = "AnotherOneSource"
    override def fetchOne(id: AnotherOne): Task[Option[Int]] =
      Task.pure(Option(id.id))
    override def fetchMany(ids: NonEmptyList[AnotherOne]): Task[Map[AnotherOne, Int]] =
      Task.pure(ids.unwrap.map(anotherone => (anotherone, anotherone.id)).toMap)
  }
  def anotherOne(id: Int): Fetch[Int] = Fetch(AnotherOne(id))

  case class Many(n: Int)
  implicit object ManySource extends DataSource[Many, List[Int]] {
    override def name = "ManySource"
    override def fetchOne(id: Many): Task[Option[List[Int]]] =
      Task.pure(Option(0 until id.n toList))
    override def fetchMany(ids: NonEmptyList[Many]): Task[Map[Many, List[Int]]] =
      Task.pure(ids.unwrap.map(m => (m, 0 until m.n toList)).toMap)
  }

  case class Never()
  implicit object NeverSource extends DataSource[Never, Int] {
    override def name = "NeverSource"
    override def fetchOne(id: Never): Task[Option[Int]] =
      Task.pure(None)
    override def fetchMany(ids: NonEmptyList[Never]): Task[Map[Never, Int]] =
      Task.pure(Map.empty[Never, Int])
  }
  def many(id: Int): Fetch[List[Int]] = Fetch(Many(id))

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
          case ConcurrentRound(ids) => acc + ids.filter(_._2.size > 1).size
      })

  def concurrent(rs: Seq[Round]): Seq[Round] =
    rs.filter(
        r =>
          r.kind match {
            case ConcurrentRound(_) => true
            case other              => false
        }
    )

  def toFuture[A](task: Task[A])(
      implicit s: Scheduler
  ): Future[A] = {
    val promise: Promise[A] = Promise()
    task.runAsync(
        new Callback[A] {
      def onSuccess(value: A): Unit    = { promise.trySuccess(value); () }
      def onError(ex: Throwable): Unit = { promise.tryFailure(ex); () }
    })
    promise.future
  }
}

class FetchSyntaxTests extends AsyncFreeSpec with Matchers {
  import fetch.syntax._
  import TestHelper._

  implicit def executionContext = Scheduler.Implicits.global
  override def newInstance      = new FetchSyntaxTests

  "Cartesian syntax is implicitly concurrent" in {
    import cats.syntax.cartesian._

    val fetch: Fetch[(Int, List[Int])] = (one(1) |@| many(3)).tupled

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      concurrent(env.rounds).size shouldEqual 1
    })
  }

  "Apply syntax is implicitly concurrent" in {
    import cats.syntax.apply._

    val fetch: Fetch[Int] = Fetch.pure((x: Int, y: Int) => x + y).ap2(one(1), one(2))

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      val rounds = env.rounds
      val stats  = (concurrent(rounds).size, totalBatches(rounds), totalFetched(rounds))

      stats shouldEqual (1, 1, 2)
    })
  }

  "`fetch` syntax allows lifting of any value to the context of a fetch" in {
    Fetch.pure(42) shouldEqual 42.fetch
  }

  "`fetch` syntax allows lifting of any `Throwable` as a failure on a fetch" in {
    case object Ex extends RuntimeException

    val e1 = Fetch
      .run(Fetch.error(Ex))
      .onErrorRecoverWith({
        case Ex => Task.now("thrown")
      })

    val e2 = Fetch
      .run(Ex.fetch)
      .onErrorRecoverWith({
        case Ex => Task.now("thrown")
      })

    toFuture(Task.mapBoth(e1, e2)(_ == _)).map(_ shouldEqual true)
  }

  "`join` syntax is equivalent to `Fetch#join`" in {
    val join1 = Fetch.join(one(1), many(3))
    val join2 = one(1).join(many(3))

    toFuture(Task.mapBoth(Fetch.run(join1), Fetch.run(join2))(_ == _)).map(_ shouldEqual true)
  }

  "`runF` syntax is equivalent to `Fetch#runFetch`" in {

    val rf1 = Fetch.runFetch(1.fetch)
    val rf2 = 1.fetch.runF

    toFuture(Task.mapBoth(rf1, rf2)(_ == _)).map(_ shouldEqual true)
  }

  "`runE` syntax is equivalent to `Fetch#runEnv`" in {

    val rf1 = Fetch.runEnv(1.fetch)
    val rf2 = 1.fetch.runE

    toFuture(Task.mapBoth(rf1, rf2)(_ == _)).map(_ shouldEqual true)
  }

  "`runA` syntax is equivalent to `Fetch#run`" in {

    val rf1 = Fetch.run(1.fetch)
    val rf2 = 1.fetch.runA

    toFuture(Task.mapBoth(rf1, rf2)(_ == _)).map(_ shouldEqual true)
  }
}

class FetchTests extends AsyncFreeSpec with Matchers {
  import TestHelper._

  implicit def executionContext = Scheduler.Implicits.global
  override def newInstance      = new FetchTests

  "We can lift plain values to Fetch" in {
    val fetch: Fetch[Int] = Fetch.pure(42)
    Fetch.run(fetch).coeval.value shouldEqual Right(42)
  }

  "Data sources with errors throw fetch failures" in {
    val fetch: Fetch[Int] = Fetch(Never())

    intercept[FetchFailure] {
      Fetch.runEnv(fetch).coeval.value
    } match {
      case FetchFailure(env) => {
          env.rounds.headOption match {
            case Some(Round(_, _, OneRound(id), _, _, _)) => id shouldEqual Never()
            case _                                        => fail("Expected Some(Round(_,_, Oneround(id),_,_,_))")
          }
        }
    }
  }

  "Data sources with errors throw fetch failures that can be handled" in {
    val fetch: Fetch[Int] = Fetch(Never())

    Fetch.run(fetch).onErrorHandleWith(err => Task.now(42)).coeval.value shouldEqual Right(42)
  }

  "Data sources with errors and cached values throw fetch failures with the cache" in {
    val fetch: Fetch[Int] = Fetch(Never())
    val cache = InMemoryCache(
        OneSource.identity(One(1)) -> 1
    )

    intercept[FetchFailure] {
      Fetch.run(fetch, cache).coeval.value
    } match {
      case FetchFailure(env) => env.cache shouldEqual cache
    }
  }

  "Data sources with errors won't fail if they're cached" in {
    val fetch: Fetch[Int] = Fetch(Never())
    val cache = InMemoryCache(
        NeverSource.identity(Never()) -> 1
    )
    Fetch.run(fetch, cache).coeval.value shouldEqual Right(1)
  }

  "We can lift errors to Fetch" in {
    val fetch: Fetch[Int] = Fetch.error(NotFound())
    intercept[NotFound] {
      Fetch.run(fetch).coeval.value
    }
  }

  "We can lift handle and recover from errors in Fetch" in {
    val fetch: Fetch[Int] = Fetch.error(NotFound())

    Fetch.run(fetch).onErrorHandleWith(err => Task.pure(42)).coeval.value shouldEqual Right(42)
  }

  "We can lift values which have a Data Source to Fetch" in {
    Fetch.run(one(1)).coeval.value shouldEqual Right(1)
  }

  "We can map over Fetch values" in {
    val fetch = one(1).map(_ + 1)
    Fetch.run(fetch).coeval.value shouldEqual Right(2)
  }

  "We can use fetch inside a for comprehension" in {
    val fetch = for {
      o <- one(1)
      t <- one(2)
    } yield (o, t)

    Fetch.run(fetch).coeval.value shouldEqual Right((1, 2))
  }

  "Monadic bind implies sequential execution" in {
    val fetch = for {
      o <- one(1)
      t <- one(2)
    } yield (o, t)

    Fetch.runEnv(fetch).coeval.value.right.map(_.rounds.size) shouldEqual Right(2)
  }

  "We can mix data sources" in {
    val fetch: Fetch[(Int, List[Int])] = for {
      o <- one(1)
      m <- many(3)
    } yield (o, m)

    Fetch.run(fetch).coeval.value shouldEqual Right((1, List(0, 1, 2)))
  }

  "We can use Fetch as a cartesian" in {
    import cats.syntax.cartesian._

    val fetch: Fetch[(Int, List[Int])] = (one(1) |@| many(3)).tupled
    val task                           = Fetch.run(fetch)

    toFuture(task).map(_ shouldEqual (1, List(0, 1, 2)))
  }

  "We can use Fetch as an applicative" in {
    import cats.syntax.cartesian._

    val fetch: Fetch[Int] = (one(1) |@| one(2) |@| one(3)).map(_ + _ + _)
    val task              = Fetch.run(fetch)

    toFuture(task).map(_ shouldEqual 6)
  }

  "We can traverse over a list with a Fetch for each element" in {
    import cats.std.list._
    import cats.syntax.traverse._

    val fetch: Fetch[List[Int]] = for {
      manies <- many(3)
      ones   <- manies.traverse(one)
    } yield ones

    val task = Fetch.run(fetch)
    toFuture(task).map(_ shouldEqual List(0, 1, 2))
  }

  "Traversals are implicitly concurrent" in {
    import cats.std.list._
    import cats.syntax.traverse._

    val fetch: Fetch[List[Int]] = for {
      manies <- many(3)
      ones   <- manies.traverse(one)
    } yield ones

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      concurrent(env.rounds).size shouldEqual 1
    })
  }

  "The product of two fetches implies parallel fetching" in {
    val fetch: Fetch[(Int, List[Int])] = Fetch.join(one(1), many(3))

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      concurrent(env.rounds).size shouldEqual 1
    })
  }

  "Concurrent fetching calls batches only wen it can" in {
    val fetch: Fetch[(Int, List[Int])] = Fetch.join(one(1), many(3))

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      totalBatches(env.rounds) shouldEqual 0
    })
  }

  "If a fetch fails in the left hand of a product the product will fail" in {
    val fetch: Fetch[(Int, List[Int])] = Fetch.join(Fetch.error(NotFound()), many(3))
    val task                           = Fetch.run(fetch)

    toFuture(task.onErrorRecoverWith({
      case ex: NotFound => Task.now("not found")
    })).map(_ shouldEqual "not found")
  }

  "If a fetch fails in the right hand of a product the product will fail" in {
    val fetch: Fetch[(List[Int], Int)] = Fetch.join(many(3), Fetch.error(NotFound()))
    val task                           = Fetch.run(fetch)

    toFuture(task.onErrorRecoverWith({
      case ex: NotFound => Task.now("not found")
    })).map(_ shouldEqual "not found")
  }

  "If there is a missing identity in the left hand of a product the product will fail" in {
    val fetch: Fetch[(Int, List[Int])] = Fetch.join(Fetch(Never()), many(3))
    val task                           = Fetch.run(fetch)

    toFuture(task.onErrorRecoverWith({
      case ex: FetchFailure => Task.now("fail!")
    })).map(_ shouldEqual "fail!")
  }

  "If there is a missing identity in the right hand of a product the product will fail" in {
    val fetch: Fetch[(List[Int], Int)] = Fetch.join(many(3), Fetch(Never()))
    val task                           = Fetch.run(fetch)

    toFuture(task.onErrorRecoverWith({
      case ex: FetchFailure => Task.now("fail!")
    })).map(_ shouldEqual "fail!")
  }

  "The product of concurrent fetches implies everything fetched concurrently" in {
    val fetch = Fetch.join(
        Fetch.join(
            one(1),
            Fetch.join(one(2), one(3))
        ),
        one(4)
    )

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      val rounds = env.rounds
      val stats  = (concurrent(rounds).size, totalBatches(rounds), totalFetched(rounds))

      stats shouldEqual (1, 1, 4)
    })
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

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      val rounds = env.rounds
      val stats  = (concurrent(rounds).size, totalBatches(rounds), totalFetched(rounds))

      stats shouldEqual (2, 1, 4)
    })
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

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      val rounds = env.rounds
      val stats  = (concurrent(rounds).size, totalBatches(rounds), totalFetched(rounds))

      stats shouldEqual (3, 3, 6)
    })
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

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      val rounds = env.rounds
      val stats  = (concurrent(rounds).size, totalBatches(rounds), totalFetched(rounds))

      stats shouldEqual (3, 3, 9 + 4 + 6)
    })
  }

  "The product of two fetches from the same data source implies batching" in {
    val fetch: Fetch[(Int, Int)] = Fetch.join(one(1), one(3))

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      val rounds = env.rounds
      val stats  = (concurrent(rounds).size, totalBatches(rounds))

      stats shouldEqual (1, 1)
    })
  }

  "We can depend on previous computations of Fetch values" in {
    val fetch: Fetch[Int] = for {
      o <- one(1)
      t <- one(o + 1)
    } yield o + t

    Fetch.run(fetch).coeval.value shouldEqual Right(3)
  }

  "We can collect a list of Fetch into one" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3))
    val fetch: Fetch[List[Int]]   = Fetch.sequence(sources)

    val task = Fetch.run(fetch)

    toFuture(task).map(_ shouldEqual List(1, 2, 3))
  }

  "We can collect a list of Fetches with heterogeneous sources" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), anotherOne(4), anotherOne(5))
    val fetch: Fetch[List[Int]]   = Fetch.sequence(sources)

    val task = Fetch.run(fetch)

    toFuture(task).map(_ shouldEqual List(1, 2, 3, 4, 5))
  }

  "Sequenced fetches are run concurrently" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), anotherOne(4), anotherOne(5))
    val fetch: Fetch[List[Int]]   = Fetch.sequence(sources)

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      val rounds = env.rounds
      val stats  = (concurrent(rounds).size, totalBatches(rounds))

      stats shouldEqual (1, 2)
    })
  }

  "Sequenced fetches are deduped" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(1))
    val fetch: Fetch[List[Int]]   = Fetch.sequence(sources)

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      val rounds = env.rounds
      val stats  = (concurrent(rounds).size, totalFetched(rounds))

      stats shouldEqual (1, 2)
    })
  }

  "Sequenced fetches are not asked for when cached" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), one(4))
    val fetch: Fetch[List[Int]]   = Fetch.sequence(sources)

    val task = Fetch.runEnv(
        fetch,
        InMemoryCache(
            OneSource.identity(One(1)) -> 1,
            OneSource.identity(One(2)) -> 2
        )
    )

    toFuture(task).map(env => {
      val rounds = env.rounds
      val stats  = (concurrent(rounds).size, totalFetched(rounds))

      stats shouldEqual (1, 2)
    })
  }

  "We can collect the results of a traversal" in {
    val fetch = Fetch.traverse(List(1, 2, 3))(one)

    val task = Fetch.run(fetch)

    toFuture(task).map(_ shouldEqual List(1, 2, 3))
  }

  "Traversals are run concurrently" in {
    val fetch = Fetch.traverse(List(1, 2, 3))(one)

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      concurrent(env.rounds).size shouldEqual 1
    })
  }

  "Duplicated sources are only fetched once" in {
    val fetch = Fetch.traverse(List(1, 2, 1))(one)

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      val rounds = env.rounds
      val stats  = (concurrent(rounds).size, totalFetched(rounds))

      stats shouldEqual (1, 2)
    })
  }

  "Sources that can be fetched concurrently inside a for comprehension will be" in {
    val fetch = for {
      v      <- Fetch.pure(List(1, 2, 1))
      result <- Fetch.traverse(v)(one)
    } yield result

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      val rounds = env.rounds
      val stats  = (concurrent(rounds).size, totalFetched(rounds))

      stats shouldEqual (1, 2)
    })
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

    val task = Fetch.runEnv(fetch)

    toFuture(task).map(env => {
      val rounds = env.rounds

      totalFetched(rounds) shouldEqual 3
    })
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

    val task = Fetch.runEnv(
        fetch,
        InMemoryCache(
            OneSource.identity(One(1)) -> 1,
            OneSource.identity(One(2)) -> 2,
            OneSource.identity(One(3)) -> 3
        )
    )

    toFuture(task).map(env => {
      val rounds = env.rounds

      totalFetched(rounds) shouldEqual 0
    })
  }

  case class MyCache(state: Map[Any, Any] = Map.empty[Any, Any]) extends DataSourceCache {
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

    val task = Fetch.runEnv(
        fetch,
        InMemoryCache(
            OneSource.identity(One(1))   -> 1,
            OneSource.identity(One(2))   -> 2,
            OneSource.identity(One(3))   -> 3,
            ManySource.identity(Many(2)) -> List(0, 1)
        )
    )

    toFuture(task).map(env => {
      val rounds = env.rounds

      totalFetched(rounds) shouldEqual 0
    })
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

    val task = Fetch.runEnv(fetch, ForgetfulCache())

    toFuture(task).map(env => {
      totalFetched(env.rounds) shouldEqual 7
    })
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

    val task = Fetch.runEnv(fetch, ForgetfulCache())

    toFuture(task).map(env => {
      totalFetched(env.rounds) shouldEqual 10
    })
  }
}
