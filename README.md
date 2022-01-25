# Fetch

[![Join the chat at https://gitter.im/47deg/fetch](https://badges.gitter.im/47deg/fetch.svg)](https://gitter.im/47deg/fetch?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge) [![Maven Central](https://img.shields.io/badge/maven%20central-1.2.1-green.svg)](https://oss.sonatype.org/#nexus-search;gav~com.47deg~fetch*) [![License](https://img.shields.io/badge/license-Apache%202-blue.svg)](https://raw.githubusercontent.com/47deg/fetch/master/LICENSE) [![Latest version](https://img.shields.io/badge/fetch-1.2.1-green.svg)](https://index.scala-lang.org/47deg/fetch) [![Scala.js](http://scala-js.org/assets/badges/scalajs-0.6.15.svg)](http://scala-js.org) [![GitHub Issues](https://img.shields.io/github/issues/47deg/fetch.svg)](https://github.com/47deg/fetch/issues)

A library for Simple & Efficient data access in Scala and Scala.js

- [Documentation](https://47degrees.github.io/fetch/docs)

---

- [Installation](#installation)
- [Remote data](#remote-data)
- [Define your data sources](#define-your-data-sources)
- [Creating a runtime](#creating-a-runtime)
- [Creating and running a fetch](#creating-and-running-a-fetch)
- [Batching](#batching)
- [Parallelism](#parallelism)
- [Deduplication & Caching](#deduplication--caching)

## Installation

Add the following dependency to your project's build file.

For Scala 2.12.x through 3.x:

```scala
"com.47deg" %% "fetch" % "3.0.0"
```

Or, if using Scala.js (0.6.x):

```scala
"com.47deg" %%% "fetch" % "2.1.1"
```


## Remote data

Fetch is a library for making access to data both simple and efficient. Fetch is especially useful when querying data that
has a latency cost, such as databases or web services.

## Define your data sources

To tell Fetch how to get the data you want, you must implement the `DataSource` typeclass. Data sources have `fetch` and `batch` methods that define how to fetch such a piece of data.

Data Sources take two type parameters:

<ol>
<li><code>Identity</code> is a type that has enough information to fetch the data</li>
<li><code>Result</code> is the type of data we want to fetch</li>
</ol>

```scala
import cats.data.NonEmptyList
import cats.effect.Concurrent

trait DataSource[F[_], Identity, Result]{
  def data: Data[Identity, Result]
  def CF: Concurrent[F]
  def fetch(id: Identity): F[Option[Result]]
  def batch(ids: NonEmptyList[Identity]): F[Map[Identity, Result]]
}
```

Returning `Concurrent` instances from the fetch methods allows us to specify if the fetch must run synchronously or asynchronously, and use all the goodies available in `cats` and `cats-effect`.

We'll implement a dummy data source that can convert integers to strings. For convenience, we define a `fetchString` function that lifts identities (`Int` in our dummy data source) to a `Fetch`.

```scala
import cats._
import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._

import fetch._

def latency[F[_] : Sync](milis: Long): F[Unit] =
  Sync[F].delay(Thread.sleep(milis))

object ToString extends Data[Int, String] {
  def name = "To String"

  def source[F[_] : Async]: DataSource[F, Int, String] = new DataSource[F, Int, String]{
    override def data = ToString

    override def CF = Concurrent[F]

    override def fetch(id: Int): F[Option[String]] = for {
      _ <- CF.delay(println(s"--> [${Thread.currentThread.getId}] One ToString $id"))
      _ <- latency(100)
      _ <- CF.delay(println(s"<-- [${Thread.currentThread.getId}] One ToString $id"))
    } yield Option(id.toString)

    override def batch(ids: NonEmptyList[Int]): F[Map[Int, String]] = for {
      _ <- CF.delay(println(s"--> [${Thread.currentThread.getId}] Batch ToString $ids"))
      _ <- latency(100)
      _ <- CF.delay(println(s"<-- [${Thread.currentThread.getId}] Batch ToString $ids"))
    } yield ids.toList.map(i => (i, i.toString)).toMap
  }
}

def fetchString[F[_] : Async](n: Int): Fetch[F, String] =
  Fetch(n, ToString.source)
```

## Creating a runtime

Since `Fetch` relies on `Concurrent` from the `cats-effect` library, we'll need a runtime for executing our effects. We'll be using `IO` from `cats-effect` to run fetches, but you can use any type that has a `Concurrent` instance.

For executing `IO`, we need a `ContextShift[IO]` used for running `IO` instances and a `Timer[IO]` that is used for scheduling. Let's go ahead and create them. We'll use a `java.util.concurrent.ScheduledThreadPoolExecutor` with a couple of threads to run our fetches.

```scala
import java.util.concurrent._
import scala.concurrent.ExecutionContext

val executor = new ScheduledThreadPoolExecutor(4)
val executionContext: ExecutionContext = ExecutionContext.fromExecutor(executor)

import cats.effect.unsafe.implicits.global
```

## Creating and running a fetch

Now that we can convert `Int` values to `Fetch[F, String]`, let's try creating a fetch.

```scala
def fetchOne[F[_] : Async]: Fetch[F, String] =
  fetchString(1)
```

Let's run it and wait for the fetch to complete. We'll use `IO#unsafeRunTimed` for testing purposes, which will run an `IO[A]` to `Option[A]` and return `None` if it didn't complete in time:

```scala
import scala.concurrent.duration._

Fetch.run[IO](fetchOne).unsafeRunTimed(5.seconds)
// --> [177] One ToString 1
// <-- [177] One ToString 1
// res0: Option[String] = Some(value = "1")
```

As you can see in the previous example, the `ToStringSource` is queried once to get the value of 1.

## Batching

Multiple fetches to the same data source are automatically batched. For illustrating this, we are going to compose three independent fetch results as a tuple.

```scala
def fetchThree[F[_] : Async]: Fetch[F, (String, String, String)] =
  (fetchString(1), fetchString(2), fetchString(3)).tupled
```

When executing the above fetch, note how the three identities get batched, and the data source is only queried once.

```scala
Fetch.run[IO](fetchThree).unsafeRunTimed(5.seconds)
// --> [177] Batch ToString NonEmptyList(1, 2, 3)
// <-- [177] Batch ToString NonEmptyList(1, 2, 3)
// res1: Option[(String, String, String)] = Some(value = ("1", "2", "3"))
```

Note that the `DataSource#batch` method is not mandatory. It will be implemented in terms of `DataSource#fetch` if you don't provide an implementation.

```scala
object UnbatchedToString extends Data[Int, String] {
  def name = "Unbatched to string"

  def source[F[_]: Async] = new DataSource[F, Int, String] {
    override def data = UnbatchedToString

    override def CF = Concurrent[F]

    override def fetch(id: Int): F[Option[String]] = 
      CF.delay(println(s"--> [${Thread.currentThread.getId}] One UnbatchedToString $id")) >>
      latency(100) >>
      CF.delay(println(s"<-- [${Thread.currentThread.getId}] One UnbatchedToString $id")) >>
      CF.pure(Option(id.toString))
  }
}

def unbatchedString[F[_]: Async](n: Int): Fetch[F, String] =
  Fetch(n, UnbatchedToString.source)
```

Let's create a tuple of unbatched string requests.

```scala
def fetchUnbatchedThree[F[_] : Async]: Fetch[F, (String, String, String)] =
  (unbatchedString(1), unbatchedString(2), unbatchedString(3)).tupled
```

When executing the above fetch, note how the three identities get requested in parallel. You can override `batch` to execute queries sequentially if you need to.

```scala
Fetch.run[IO](fetchUnbatchedThree).unsafeRunTimed(5.seconds)
// --> [177] One UnbatchedToString 1
// --> [178] One UnbatchedToString 2
// <-- [177] One UnbatchedToString 1
// --> [177] One UnbatchedToString 3
// <-- [178] One UnbatchedToString 2
// <-- [177] One UnbatchedToString 3
// res2: Option[(String, String, String)] = Some(value = ("1", "2", "3"))
```

## Parallelism

If we combine two independent fetches from different data sources, the fetches can be run in parallel. First, let's add a data source that fetches a string's size.

```scala
object Length extends Data[String, Int] {
  def name = "Length"

  def source[F[_] : Async] = new DataSource[F, String, Int] {
    override def data = Length

    override def CF = Concurrent[F]

    override def fetch(id: String): F[Option[Int]] = for {
      _ <- CF.delay(println(s"--> [${Thread.currentThread.getId}] One Length $id"))
      _ <- latency(100)
      _ <- CF.delay(println(s"<-- [${Thread.currentThread.getId}] One Length $id"))
    } yield Option(id.size)

    override def batch(ids: NonEmptyList[String]): F[Map[String, Int]] = for {
      _ <- CF.delay(println(s"--> [${Thread.currentThread.getId}] Batch Length $ids"))
      _ <- latency(100)
      _ <- CF.delay(println(s"<-- [${Thread.currentThread.getId}] Batch Length $ids"))
    } yield ids.toList.map(i => (i, i.size)).toMap
  }
}

def fetchLength[F[_] : Async](s: String): Fetch[F, Int] =
  Fetch(s, Length.source)
```

And now we can easily receive data from the two sources in a single fetch.

```scala
def fetchMulti[F[_] : Async]: Fetch[F, (String, Int)] =
  (fetchString(1), fetchLength("one")).tupled
```

Note how the two independent data fetches run in parallel, minimizing the latency cost of querying the two data sources.

```scala
Fetch.run[IO](fetchMulti).unsafeRunTimed(5.seconds)
// --> [178] One Length one
// --> [177] One ToString 1
// <-- [177] One ToString 1
// <-- [178] One Length one
// res3: Option[(String, Int)] = Some(value = ("1", 3))
```

## Deduplication & Caching

The Fetch library supports deduplication and optional caching.
By default, fetches that are chained together will share the same cache backend, providing some deduplication.

When fetching an identity twice within the same `Fetch`, such as a batch of fetches or when you `flatMap` one fetch into another, subsequent fetches for the same identity are cached.
Let's try creating a fetch that asks for the same identity twice, by using `flatMap` (in a for-comprehension) to chain the requests together:

```scala
def fetchTwice[F[_] : Async]: Fetch[F, (String, String)] = for {
  one <- fetchString(1)
  two <- fetchString(1)
} yield (one, two)
```

While running it, notice that the data source is only queried once.
The next time the identity is requested, it's served from the internal cache.

```scala
val runFetchTwice = Fetch.run[IO](fetchTwice)
```
```scala
runFetchTwice.unsafeRunTimed(5.seconds)
// --> [177] One ToString 1
// <-- [177] One ToString 1
// res4: Option[(String, String)] = Some(value = ("1", "1"))
```

This will still fetch the data again, however, if we call it once more:
```scala
runFetchTwice.unsafeRunTimed(5.seconds)
// --> [178] One ToString 1
// <-- [178] One ToString 1
// res5: Option[(String, String)] = Some(value = ("1", "1"))
```

If we want to cache between multiple individual fetches, you should use `Fetch.runCache` or `Fetch.runAll` to return the cache for reusing later.
Here is an example where we fetch four separate times, and explicitly share the cache to keep the deduplication functionality:

```scala
//We get the cache from the first run and pass it to all subsequent fetches
val runFetchFourTimesSharedCache = for {
  (cache, one) <- Fetch.runCache[IO](fetchString(1))
  two <- Fetch.run[IO](fetchString(1), cache)
  three <- Fetch.run[IO](fetchString(1), cache)
  four <- Fetch.run[IO](fetchString(1), cache)
} yield (one, two, three, four)
```
```scala
runFetchFourTimesSharedCache.unsafeRunTimed(5.seconds)
// --> [177] One ToString 1
// <-- [177] One ToString 1
// res6: Option[(String, String, String, String)] = Some(
//   value = ("1", "1", "1", "1")
// )
```

As you can see above, the cache will now work between calls and can be used to deduplicate requests over a period of time.
Note that this does not support any kind of automatic cache invalidation, so you will need to keep track of which values you want to re-fetch if you plan on sharing the cache.

---

For more in-depth information, take a look at our [documentation](https://47degrees.github.io/fetch/docs.html).

# Copyright

Fetch is designed and developed by 47 Degrees

Copyright (C) 2016-2021 47 Degrees. <http://47deg.com>
