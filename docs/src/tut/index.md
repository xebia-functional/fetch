---
layout: home
technologies:
 - scala: ["Scala", "Fetch is written in Scala and supports both Scala (JVM) and Scala.js (JavaScript environments)"]
 - cats: ["Cats", "Fetch uses cats' Free Monad implementation as well as some of its data types."]
 - fp: ["Functional Programming", "Fetch is implemented using the Free Monad and Interpreter pattern."]
---

## Installation

Add the following dependency to your project's build file.

For Scala 2.11.x:

```scala
"com.fortysevendeg" %% "fetch" %% "0.2.0"
```

Or, if using Scala.js (0.6.x):

```scala
"com.fortysevendeg" %%% "fetch" %% "0.2.0"
```

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

## Define your data sources

To tell Fetch how to get the data you want, you must implement the `DataSource` typeclass. Data sources have a `fetch` method that
defines how to fetch such a piece of data.

Data Sources take two type parameters:

<ol>
<li><code>Identity</code> is a type that has enough information to fetch the data</li>
<li><code>Result</code> is the type of data we want to fetch</li>
</ol>

```scala
import monix.eval.Task
import cats.data.NonEmptyList

trait DataSource[Identity, Result]{
  def fetchOne(id: Identity): Task[Option[Result]]
  def fetchMany(ids: NonEmptyList[Identity]): Task[Map[Identity, Result]]
}
```

We'll implement a dummy data source that can convert integers to strings. For convenience, we define a `fetchString` function that lifts identities (`Int` in our dummy data source) to a `Fetch`. 

```tut:silent
import monix.eval.Task
import cats.data.NonEmptyList
import cats.std.list._
import fetch._

implicit object ToStringSource extends DataSource[Int, String]{
  override def fetchOne(id: Int): Task[Option[String]] = {
    Task.now({
      println(s"[${Thread.currentThread.getId}] One ToString $id")
      Option(id.toString)
    })
  }
  override def fetchMany(ids: NonEmptyList[Int]): Task[Map[Int, String]] = {
    Task.now({
      println(s"[${Thread.currentThread.getId}] Many ToString $ids")
      ids.unwrap.map(i => (i, i.toString)).toMap
    })
  }
}

def fetchString(n: Int): Fetch[String] = Fetch(n) // or, more explicitly: Fetch(n)(ToStringSource)
```

## Creating and running a fetch

Now that we can convert `Int` values to `Fetch[String]`, let's try creating a fetch.

```tut:silent
import fetch.syntax._

val fetchOne: Fetch[String] = fetchString(1)
```

Now that we have created a fetch, we can run it to a `Task`. Note that when we create a task we are not computing any value yet. Having a `Task` instance allows us to try to run it synchronously or asynchronously, choosing a scheduler.

```tut:book
val result: Task[String] = fetchOne.runA
```

We can try to run `result` synchronously with `Task#coeval`. 

```tut:book
import monix.execution.Scheduler.Implicits.global

result.coeval.value
```

Since we calculated the results eagerly using `Task#now`, we can run this fetch synchronously.

As you can see in the previous example, the `ToStringSource` is queried once to get the value of 1.

## Batching

Multiple fetches to the same data source are automatically batched. For illustrating it, we are going to compose three independent fetch results as a tuple.

```tut:book
import cats.syntax.cartesian._

val fetchThree: Fetch[(String, String, String)] = (fetchString(1) |@| fetchString(2) |@| fetchString(3)).tupled
val result: Task[(String, String, String)] = fetchThree.runA
```

```tut:invisible
import scala.concurrent._
import scala.concurrent.duration._

def await[A](t: Task[A]): A = Await.result(t.runAsync, Duration.Inf)
```

When executing the above fetch, note how the three identities get batched and the data source is only queried once. Let's pretend we have a function from `Task[A]` to `A` called `await`.

```tut:book
await(result)
```

## Parallelism

If we combine two independent fetches from different data sources, the fetches can be run in parallel. First, let's add a data source that fetches a string's size.

This time, instead of creating the results with `Task#now` we are going to do it with `Task#apply` for emulating an asynchronous data source.

```tut:silent
implicit object LengthSource extends DataSource[String, Int]{
  override def fetchOne(id: String): Task[Option[Int]] = {
    Task({
      println(s"[${Thread.currentThread.getId}] One Length $id")
      Option(id.size)
    })
  }
  override def fetchMany(ids: NonEmptyList[String]): Task[Map[String, Int]] = {
    Task({
      println(s"[${Thread.currentThread.getId}] Many Length $ids")
      ids.unwrap.map(i => (i, i.size)).toMap
    })
  }
}

def fetchLength(s: String): Fetch[Int] = Fetch(s)
```

And now we can easily receive data from the two sources in a single fetch. 

```tut:book
val fetchMulti: Fetch[(String, Int)] = (fetchString(1) |@| fetchLength("one")).tupled
val result = fetchMulti.runA
```

Note how the two independent data fetches are run in parallel, minimizing the latency cost of querying the two data sources.

```tut:book
await(result)
```

## Caching

When fetching an identity, subsequent fetches for the same identity are cached. Let's try creating a fetch that asks for the same identity twice.

```tut:book
val fetchTwice: Fetch[(String, String)] = for {
  one <- fetchString(1)
  two <- fetchString(1)
} yield (one, two)
```

While running it, notice that the data source is only queried once. The next time the identity is requested it's served from the cache.

```tut:book
val result: (String, String) = await(fetchTwice.runA)
```

