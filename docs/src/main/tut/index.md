---
layout: home
technologies:
 - first: ["Scala", "Fetch is written in Scala and supports both Scala (JVM) and Scala.js (JavaScript environments)"]
 - second: ["Cats", "Fetch uses cats' Free Monad implementation as well as some of its data types."]
 - third: ["Functional Programming", "Fetch is implemented using the Free Monad and Interpreter pattern."]
---

## Installation

Add the following dependency to your project's build file.

For Scala 2.11.x and 2.12.x:

[comment]: # (Start Replace)

```scala
"com.47deg" %% "fetch" % "0.7.3"
```

Or, if using Scala.js (0.6.x):

```scala
"com.47deg" %%% "fetch" % "0.7.3"
```

[comment]: # (End Replace)

```tut:invisible
val out = Console.out

def println(msg: String): Unit = {
  Console.withOut(out) {
    Console.println(msg)
  }
}
```

## Remote data

Fetch is a library for making access to data both simple & efficient. Fetch is especially useful when querying data that
has a latency cost, such as databases or web services.

## Create a runtime

Since `Fetch` relies on `IO` from the `cats-effect` library, we'll need a runtime for executing our `IO` instances. This includes a `ContextShift[IO]` used for running the `IO` instances and a `Timer[IO]` that is used for scheduling, let's go ahead and create them, we'll use a `java.util.concurrent.ScheduledThreadPoolExecutor` with a couple of threads to run our fetches.

```tut:silent
import java.util.concurrent._
import scala.concurrent.ExecutionContext
import cats.effect.{ IO, Timer, ContextShift }

val executor = new ScheduledThreadPoolExecutor(2)
val executionContext: ExecutionContext = ExecutionContext.fromExecutor(executor)

implicit val timer: Timer[IO] = IO.timer(executionContext)
implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)
```

## Define your data sources

To tell Fetch how to get the data you want, you must implement the `DataSource` typeclass. Data sources have `fetch` and `batch` methods that define how to fetch such a piece of data.

Data Sources take two type parameters:

<ol>
<li><code>Identity</code> is a type that has enough information to fetch the data</li>
<li><code>Result</code> is the type of data we want to fetch</li>
</ol>

```scala
import cats.data.NonEmptyList

trait DataSource[Identity, Result]{
  def name: String
  def fetch(id: Identity): IO[Option[Result]]
  def batch(ids: NonEmptyList[Identity]): IO[Map[Identity, Result]]
}
```

Returning `IO` instances from the fetch methods allows us to specify if the fetch must run synchronously or asynchronously and use all the goodies available in `cats` and `cats-effect`.

We'll implement a dummy data source that can convert integers to strings. For convenience, we define a `fetchString` function that lifts identities (`Int` in our dummy data source) to a `Fetch`.

```tut:silent
import scala.concurrent.duration._
import cats.data.NonEmptyList
import cats.instances.list._
import cats.syntax.all._
import fetch._

implicit object ToStringSource extends DataSource[Int, String]{
  override def name = "ToString"

  override def fetch(id: Int): IO[Option[String]] = {
    IO(println(s"--> [${Thread.currentThread.getId}] One ToString $id")) >>
    IO.sleep(10.milliseconds) >>
    IO(println(s"<-- [${Thread.currentThread.getId}] One ToString $id")) >>
    IO(Option(id.toString))
  }

  override def batch(ids: NonEmptyList[Int]): IO[Map[Int, String]] = {
    IO(println(s"--> [${Thread.currentThread.getId}] Batch ToString $ids")) >>
    IO.sleep(10.milliseconds) >>
    IO(println(s"<-- [${Thread.currentThread.getId}] Batch ToString $ids")) >>
    IO(ids.toList.map(i => (i, i.toString)).toMap)
  }
}

def fetchString(n: Int): Fetch[String] = Fetch(n, ToStringSource)
```

## Creating and running a fetch

Now that we can convert `Int` values to `Fetch[String]`, let's try creating a fetch.

```tut:silent
val fetchOne: Fetch[String] = fetchString(1)
```

Let's run it and wait for the fetch to complete, we'll use `IO#unsafeRunTimed` for testing purposes, which will run an `IO[A]` to `Option[A]` and return `None` if it didn't complete in time:

```tut:book
Fetch.run(fetchOne).unsafeRunTimed(5.seconds)
```

As you can see in the previous example, the `ToStringSource` is queried once to get the value of 1.

## Batching

Multiple fetches to the same data source are automatically batched. For illustrating it, we are going to compose three independent fetch results as a tuple.

```tut:silent
import cats.syntax.apply._

val fetchThree: Fetch[(String, String, String)] = (fetchString(1), fetchString(2), fetchString(3)).tupled
```

When executing the above fetch, note how the three identities get batched and the data source is only queried once.

```tut:book
Fetch.run(fetchThree).unsafeRunTimed(5.seconds)
```

Note that the `DataSource#batch` method is not mandatory, it will be implemented in terms of `DataSource#fetch` if you don't provide an implementation.

```tut:silent
implicit object UnbatchedToStringSource extends DataSource[Int, String]{
  override def name = "UnbatchedToString"

  override def fetch(id: Int): IO[Option[String]] = {
    IO(println(s"--> [${Thread.currentThread.getId}] One UnbatchedToString $id")) >>
    IO.sleep(10.milliseconds) >>
    IO(println(s"<-- [${Thread.currentThread.getId}] One UnbatchedToString $id")) >>
    IO(Option(id.toString))
  }
}

def unbatchedString(n: Int): Fetch[String] = Fetch(n, UnbatchedToStringSource)
```

Let's create a tuple of unbatched string requests.

```tut:silent
val fetchUnbatchedThree: Fetch[(String, String, String)] = (unbatchedString(1), unbatchedString(2), unbatchedString(3)).tupled
```

When executing the above fetch, note how the three identities get requested in parallel. You can override `batch` to execute queries sequentially if you need to.

```tut:book
Fetch.run(fetchUnbatchedThree).unsafeRunTimed(5.seconds)
```


## Parallelism

If we combine two independent fetches from different data sources, the fetches can be run in parallel. First, let's add a data source that fetches a string's size.

```tut:silent
implicit object LengthSource extends DataSource[String, Int]{
  override def name = "Length"

  override def fetch(id: String): IO[Option[Int]] = {
    IO(println(s"--> [${Thread.currentThread.getId}] One Length $id")) >>
    IO.sleep(10.milliseconds) >>
    IO(println(s"<-- [${Thread.currentThread.getId}] One Length $id")) >>
    IO(Option(id.size))
  }
  override def batch(ids: NonEmptyList[String]): IO[Map[String, Int]] = {
    IO(println(s"--> [${Thread.currentThread.getId}] Batch Length $ids")) >>
    IO.sleep(10.milliseconds) >>
    IO(println(s"<-- [${Thread.currentThread.getId}] Batch Length $ids")) >>
    IO(ids.toList.map(i => (i, i.size)).toMap)
  }
}

def fetchLength(s: String): Fetch[Int] = Fetch(s, LengthSource)
```

And now we can easily receive data from the two sources in a single fetch.

```tut:silent
val fetchMulti: Fetch[(String, Int)] = (fetchString(1), fetchLength("one")).tupled
```

Note how the two independent data fetches run in parallel, minimizing the latency cost of querying the two data sources.

```tut:book
Fetch.run(fetchMulti).unsafeRunTimed(5.seconds)
```

## Caching

When fetching an identity, subsequent fetches for the same identity are cached. Let's try creating a fetch that asks for the same identity twice.

```tut:silent
import cats.syntax.all._

val fetchTwice: Fetch[(String, String)] = for {
  one <- fetchString(1)
  two <- fetchString(1)
} yield (one, two)
```

While running it, notice that the data source is only queried once. The next time the identity is requested it's served from the cache.

```tut:book
Fetch.run(fetchTwice).unsafeRunTimed(5.seconds)
```


```tut:invisible
executor.shutdownNow()
```
---

For more in-depth information take a look at our [documentation](http://47deg.github.io/fetch/docs.html).
