# Fetch

[![Join the chat at https://gitter.im/47deg/fetch](https://badges.gitter.im/47deg/fetch.svg)](https://gitter.im/47deg/fetch?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Build status](https://img.shields.io/travis/47deg/fetch.svg)](https://travis-ci.org/47deg/fetch)
[![codecov.io](http://codecov.io/github/47deg/fetch/coverage.svg?branch=master)](http://codecov.io/github/47deg/fetch?branch=master)

A library for Simple & Efficient data access in Scala and Scala.js

- [Documentation](http://47deg.github.io/fetch/docs)

## Installation

Add the following dependency to your project's build file.

For Scala 2.11.x:

```scala
"com.fortysevendeg" %% "fetch" % "0.2.0"
```

Or, if using Scala.js (0.6.x):

```scala
"com.fortysevendeg" %%% "fetch" % "0.2.0"
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

To tell `Fetch` how to get the data you want, you must implement the `DataSource` typeclass. Data sources have `fetchOne` and `fetchMany` methods that define how to fetch such a piece of data.

Data Sources take two type parameters:

<ol>
<li><code>Identity</code> is a type that has enough information to fetch the data</li>
<li><code>Result</code> is the type of data we want to fetch</li>
</ol>

```scala
import cats.data.NonEmptyList

trait DataSource[Identity, Result]{
  def fetchOne(id: Identity): Query[Option[Result]]
  def fetchMany(ids: NonEmptyList[Identity]): Query[Map[Identity, Result]]
}
```

We'll implement a dummy data source that can convert integers to strings. For convenience, we define a `fetchString` function that lifts identities (`Int` in our dummy data source) to a `Fetch`. 

```tut:silent
import cats.data.NonEmptyList
import cats.std.list._
import fetch._

implicit object ToStringSource extends DataSource[Int, String]{
  override def fetchOne(id: Int): Query[Option[String]] = {
    Query.later({
      println(s"[${Thread.currentThread.getId}] One ToString $id")
      Option(id.toString)
    })
  }
  override def fetchMany(ids: NonEmptyList[Int]): Query[Map[Int, String]] = {
    Query.later({
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

We'll run our fetches to the ambien `Id` monad in our examples, let's do some imports.

```tut:silent
import cats.Id
import fetch.unsafe.implicits._
import fetch.syntax._
```

Note that in real-life scenarios you'll want to run a fetch to a concurrency monad such as `Future` or `Task`, synchronous execution of a fetch
is only supported in Scala and not Scala.js and is meant for experimentation purposes.

Let's run it and wait for the fetch to complete:

```tut:book
fetchOne.runA[Id]
```

## Batching

Multiple fetches to the same data source are automatically batched. For illustrating it, we are going to compose three independent fetch results as a tuple.

```tut:silent
import cats.syntax.cartesian._

val fetchThree: Fetch[(String, String, String)] = (fetchString(1) |@| fetchString(2) |@| fetchString(3)).tupled
```

When executing the above fetch, note how the three identities get batched and the data source is only queried once.

```tut:book
fetchThree.runA[Id]
```

## Parallelism

If we combine two independent fetches from different data sources, the fetches can be run in parallel. First, let's add a data source that fetches a string's size.

This time, instead of creating the results with `Query#later` we are going to do it with `Query#async` for emulating an asynchronous data source.

```tut:silent
implicit object LengthSource extends DataSource[String, Int]{
  override def fetchOne(id: String): Query[Option[Int]] = {
    Query.async((ok, fail) => {
      println(s"[${Thread.currentThread.getId}] One Length $id")
      ok(Option(id.size))
    })
  }
  override def fetchMany(ids: NonEmptyList[String]): Query[Map[String, Int]] = {
    Query.async((ok, fail) => {
      println(s"[${Thread.currentThread.getId}] Many Length $ids")
      ok(ids.unwrap.map(i => (i, i.size)).toMap)
    })
  }
}

def fetchLength(s: String): Fetch[Int] = Fetch(s)
```

And now we can easily receive data from the two sources in a single fetch. 

```tut:silent
val fetchMulti: Fetch[(String, Int)] = (fetchString(1) |@| fetchLength("one")).tupled
```

Note how the two independent data fetches run in parallel, minimizing the latency cost of querying the two data sources.

```tut:book
fetchMulti.runA[Id]
```

## Caching

When fetching an identity, subsequent fetches for the same identity are cached. Let's try creating a fetch that asks for the same identity twice.

```tut:silent
val fetchTwice: Fetch[(String, String)] = for {
  one <- fetchString(1)
  two <- fetchString(1)
} yield (one, two)
```

While running it, notice that the data source is only queried once. The next time the identity is requested it's served from the cache.

```tut:book
fetchTwice.runA[Id]
```
