# Fetch

[![Join the chat at https://gitter.im/47deg/fetch](https://badges.gitter.im/47deg/fetch.svg)](https://gitter.im/47deg/fetch?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Build status](https://img.shields.io/travis/47deg/fetch.svg)](https://travis-ci.org/47deg/fetch)

A library for Simple & Efficient data access in Scala and Scala.js

- [Documentation](http://47deg.github.io/fetch/docs)

## Installation

Add the following dependency to your project's build file.

```scala
"com.fortysevendeg" %% "fetch" %% "0.2.0"
```

Or, if using Scala.js:

```scala
"com.fortysevendeg" %%% "fetch" %% "0.2.0"
```

Fetch is available for the following Scala and Scala.js versions:

- Scala 2.11.x
- Scala.js 0.6.x

## Remote data

Fetch is a library for making access to data both simple & efficient. Fetch is especially useful when querying data that
has a latency cost, such as databases or web services.

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
  def fetch(ids: NonEmptyList[Identity]): Eval[Map[Identity, Result]]
}
```

We'll implement a dummy data source that can convert integers to strings. For convenience, we define a `fetchString` function that lifts identities (`Int` in our dummy data source) to a `Fetch`. 

```tut:silent
import cats.Eval
import cats.data.NonEmptyList
import cats.std.list._

import fetch._

implicit object ToStringSource extends DataSource[Int, String]{
  override def fetch(ids: NonEmptyList[Int]): Eval[Map[Int, String]] = {
    Eval.later({
      println(s"ToStringSource $ids")
      ids.unwrap.map(i => (i, i.toString)).toMap
    })
  }
}

def fetchString(n: Int): Fetch[String] = Fetch(n) // or, more explicitly: Fetch(n)(ToStringSource)
```

## Creating and running a fetch

Now that we can convert `Int` values to `Fetch[String]`, let's try creating a fetch.

```tut:silent
import fetch.implicits._
import fetch.syntax._

val fetchOne: Fetch[String] = fetchString(1)
```

Now that we have created a fetch, we can run it to a target monad. Note that the target monad (`Eval` in our example) needs to implement `MonadError[M, Throwable]`, we provide an instance for `Eval` in `fetch.implicits._`, that's why we imported it.

```tut:book
val result: String = fetchOne.runA[Eval].value
```

As you can see in the previous example, the `ToStringSource` is queried once to get the value of 1.

## Batching

Multiple fetches to the same data source are automatically batched. For illustrating it, we are going to compose three independent fetch results as a tuple.

```tut:silent
import cats.syntax.cartesian._

val fetchThree: Fetch[(String, String, String)] = (fetchString(1) |@| fetchString(2) |@| fetchString(3)).tupled
```

When executing the above fetch, note how the three identities get batched and the data source is only queried once.

```tut:book
val result: (String, String, String) = fetchThree.runA[Eval].value
```

## Concurrency

If we combine two independent fetches from different data sources, the fetches will be run concurrently. First, let's add a data source that fetches a string's size.

```tut:silent
implicit object LengthSource extends DataSource[String, Int]{
  override def fetch(ids: NonEmptyList[String]): Eval[Map[String, Int]] = {
    Eval.later({
      println(s"LengthSource $ids")
      ids.unwrap.map(i => (i, i.size)).toMap
    })
  }
}

def fetchLength(s: String): Fetch[Int] = Fetch(s)
```

And now we can easily receive data from the two sources in a single fetch. 

```tut:silent
val fetchMulti: Fetch[(String, Int)] = (fetchString(1) |@| fetchLength("one")).tupled
```

Note how the two independent data fetches are run concurrently, minimizing the latency cost of querying the two data sources. If our target monad was a concurrency monad like `Future`, they'd run in parallel, each in its own logical thread.

```tut:book
val result: (String, Int) = fetchMulti.runA[Eval].value
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
val result: (String, String) = fetchTwice.runA[Eval].value
```

