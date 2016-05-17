---
layout: home
technologies:
 - scala: ["Scala", "Fetch is written in Scala and supports both Scala (JVM) and Scala.js (JavaScript environments)"]
 - cats: ["Cats", "Fetch uses cats' Free Monad implementation as well as some of its data types."]
 - fp: ["Functional Programming", "Fetch is implemented using the Free Monad and Interpreter pattern."]
---

## Installation

Add the following dependency to your project's build file.

```scala
"com.fortysevendeg" %%% "fetch" %% "0.1.0"
```

## Remote data

Fetch is a library for making access to data both simple & efficient. Fetch is specially useful when querying data that
has a latency cost, such as databases or web services. We'll be emulating latency with this little function:

```scala
import scala.util.Random
import cats.Eval

def latency[A](result: A, msg: String, wait: Int = Random.nextInt(100)): Eval[A] =
  Eval.later({
    val threadId = Thread.currentThread().getId()
    println(s"~~~>[thread: $threadId] $msg")
    Thread.sleep(wait)
    println(s"<~~~[thread: $threadId] $msg")
    result
  })
```

## Define your data sources

  For telling `Fetch` how to get the data you want, you must implement the `DataSource` typeclass. Data sources have a `fetch` method that
  defines how to fetch such a piece of data.

  Data Sources take two type parameters:

<ol>
<li><code>Identity</code> is a type that has enough information to fetch the data</li>
<li><code>Result</code> is the type of data we want to fetch</li>
</ol>

```scala
trait DataSource[Identity, Result]{
  def fetch(ids: List[Identity]): Eval[Map[Identity, Result]]
}
```

We'll implement a dummy data source that can convert integers to strings. For convenience we define a `fetchString` function that lifts identities (`Int` in our dummy data source) to a `Fetch`. 

```scala
import fetch._

implicit object ToStringSource extends DataSource[Int, String]{
  override def fetch(ids: List[Int]): Eval[Map[Int, String]] = {
    latency(ids.map(i => (i, i.toString)).toMap, s"ToStringSource $ids")
  }
}

def fetchString(n: Int): Fetch[String] = Fetch(n) // or, more explicitly: Fetch(n)(ToStringSource)
```

## Declare and run fetches

Now that we can convert `Int` values to `Fetch[String]`, let's try running a fetch and see what happens.

Note that the target monad (`Future` in our example) needs to implement `MonadError[M, Throwable]`, that's
why we import `cats.std.future._`.

```scala
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import cats.std.future._

def await[A](f: Future[A]): A = Await.result(f, 5 seconds)
```

And now we can interpret a fetch into a `Future`

```scala
val fch: Fetch[String] = fetchString(1)

val fut: Future[String] = Fetch.run(fch)

await(fut)
// ~~~>[thread: 67] ToStringSource List(1)
// <~~~[thread: 67] ToStringSource List(1)

//=> 1
```

## Batching

Multiple fetches to the same data source are automatically batched:

```scala
import cats.syntax.cartesian._

val fch: Fetch[(String, String, String)] = (fetchString(1) |@| fetchString(2) |@| fetchString(3)).tupled

val fut: Future[(String, String, String)] = Fetch.run(fch)

await(fut)
// ~~~>[thread: 42] ToStringSource List(1, 2, 3)
// <~~~[thread: 42] ToStringSource List(1, 2, 3)

//=> (1,2,3)
```

## Parallelism

If we combine two independent fetches from different data sources, the fetches will be run in parallel. Let's first add a data source that fetches a string's size.

```scala
implicit object LengthSource extends DataSource[String, Int]{
  override def fetch(ids: List[String]): Eval[Map[String, Int]] = {
    latency(ids.map(i => (i, i.size)).toMap, s"LengthSource $ids")
  }
}

def fetchLength(s: String): Fetch[Int] = Fetch(s)
```

And now we can combine the two data sources in a single fetch. Note how the two independent data fetches are run in parallel, minimizing the latency cost of querying the two data sources.

```scala
val fch: Fetch[(String, Int)] = (fetchString(1) |@| fetchLength("one")).tupled

val fut: Future[(String, Int)] = Fetch.run(fch)
await(fut)
// ~~~>[thread: 45] ToStringSource List(1)
// ~~~>[thread: 46] LengthSource List(one)
// <~~~[thread: 46] LengthSource List(one)
// <~~~[thread: 45] ToStringSource List(1)

//=> (1,3)
```

## Caching

When fetching an identity, subsequents fetches for the same identity are cached:

```scala
val fch: Fetch[(String, String)] = for {
  one <- fetchString(1)
  two <- fetchString(1)
} yield (one, two)

val fut: Future[(String, String)] = Fetch.run(fch)

await(fut)
// ~~~>[thread: 48] ToStringSource List(1)
// <~~~[thread: 48] ToStringSource List(1)

//=> (1,1)
```

## Bring your own concurrency

Albeit the examples use `Future` as the concurrency Monad, `Fetch` is not limited to just `Future`,
any monad `M` that implements `MonadError[M, Throwable]` will do.

Fetch provides `MonadError` instances for some existing monads like `Id` and `Eval` and is easy to write your own.
