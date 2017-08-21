# Fetch

[comment]: # (Start Badges)

[![Join the chat at https://gitter.im/47deg/fetch](https://badges.gitter.im/47deg/fetch.svg)](https://gitter.im/47deg/fetch?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge) [![Build Status](https://travis-ci.org/47deg/fetch.svg?branch=master)](https://travis-ci.org/47deg/fetch) [![codecov.io](http://codecov.io/github/47deg/fetch/coverage.svg?branch=master)](http://codecov.io/github/47deg/fetch?branch=master) [![Maven Central](https://img.shields.io/badge/maven%20central-0.6.3-green.svg)](https://oss.sonatype.org/#nexus-search;gav~com.47deg~fetch*) [![License](https://img.shields.io/badge/license-Apache%202-blue.svg)](https://raw.githubusercontent.com/47deg/fetch/master/LICENSE) [![Latest version](https://img.shields.io/badge/fetch-0.6.3-green.svg)](https://index.scala-lang.org/47deg/fetch) [![Scala.js](http://scala-js.org/assets/badges/scalajs-0.6.15.svg)](http://scala-js.org) [![GitHub Issues](https://img.shields.io/github/issues/47deg/fetch.svg)](https://github.com/47deg/fetch/issues)

[comment]: # (End Badges)

A library for Simple & Efficient data access in Scala and Scala.js

- [Documentation](http://47deg.github.io/fetch/docs)

## Installation

Add the following dependency to your project's build file.

For Scala 2.11.x and 2.12.x:

[comment]: # (Start Replace)

```scala
"com.47deg" %% "fetch" % "0.6.3"
```

Or, if using Scala.js (0.6.x):

```scala
"com.47deg" %%% "fetch" % "0.6.3"
```

[comment]: # (End Replace)




## Remote data

Fetch is a library for making access to data both simple & efficient. Fetch is especially useful when querying data that
has a latency cost, such as databases or web services.

## Define your data sources

To tell `Fetch` how to get the data you want, you must implement the `DataSource` typeclass. Data sources have `fetchOne` and `fetchMany` methods that define how to fetch such a piece of data.

Data Sources take two type parameters:

1. `Identity` is a type that has enough information to fetch the data. For a users data source, this would be a user's unique ID.
2. `Result` is the type of data we want to fetch. For a users data source, this would the `User` type.

```scala
import cats.data.NonEmptyList

trait DataSource[Identity, Result]{
  def name: String
  def fetchOne(id: Identity): Query[Option[Result]]
  def fetchMany(ids: NonEmptyList[Identity]): Query[Map[Identity, Result]]
}
```

We'll implement a dummy data source that can convert integers to strings. For convenience, we define a `fetchString` function that lifts identities (`Int` in our dummy data source) to a `Fetch`. 

```scala
import cats.data.NonEmptyList
import cats.instances.list._
import fetch._

implicit object ToStringSource extends DataSource[Int, String]{
  override def name = "ToString"
  
  override def fetchOne(id: Int): Query[Option[String]] = {
    Query.sync({
      println(s"[${Thread.currentThread.getId}] One ToString $id")
      Option(id.toString)
    })
  }
  override def fetchMany(ids: NonEmptyList[Int]): Query[Map[Int, String]] = {
    Query.sync({
      println(s"[${Thread.currentThread.getId}] Many ToString $ids")
      ids.toList.map(i => (i, i.toString)).toMap
    })
  }
}

def fetchString(n: Int): Fetch[String] = Fetch(n) // or, more explicitly: Fetch(n)(ToStringSource)
```

## Creating and running a fetch

Now that we can convert `Int` values to `Fetch[String]`, let's try creating a fetch.

```scala
import fetch.syntax._

val fetchOne: Fetch[String] = fetchString(1)
```

We'll run our fetches to the ambient `Id` monad in our examples. Note that in real-life scenarios you'll want to run a fetch to a concurrency monad such as `Future` or `Task`, synchronous execution of a fetch is only supported in Scala and not Scala.js and is meant for experimentation purposes.

```scala
import cats.Id
import fetch.unsafe.implicits._
import fetch.syntax._
```

Let's run it and wait for the fetch to complete:

```scala
fetchOne.runA[Id]
// [4089] One ToString 1
// res3: cats.Id[String] = 1
```

## Batching

Multiple fetches to the same data source are automatically batched. For illustrating it, we are going to compose three independent fetch results as a tuple.

```scala
import cats.syntax.cartesian._

val fetchThree: Fetch[(String, String, String)] = (fetchString(1) |@| fetchString(2) |@| fetchString(3)).tupled
```

When executing the above fetch, note how the three identities get batched and the data source is only queried once.

```scala
fetchThree.runA[Id]
// [4089] Many ToString NonEmptyList(3, 1, 2)
// res5: cats.Id[(String, String, String)] = (1,2,3)
```

## Parallelism

If we combine two independent fetches from different data sources, the fetches can be run in parallel. First, let's add a data source that fetches a string's size.

This time, instead of creating the results with `Query#sync` we are going to do it with `Query#async` for emulating an asynchronous data source.

```scala
implicit object LengthSource extends DataSource[String, Int]{
  override def name = "Length"
  
  override def fetchOne(id: String): Query[Option[Int]] = {
    Query.async((ok, fail) => {
      println(s"[${Thread.currentThread.getId}] One Length $id")
      ok(Option(id.size))
    })
  }
  override def fetchMany(ids: NonEmptyList[String]): Query[Map[String, Int]] = {
    Query.async((ok, fail) => {
      println(s"[${Thread.currentThread.getId}] Many Length $ids")
      ok(ids.toList.map(i => (i, i.size)).toMap)
    })
  }
}

def fetchLength(s: String): Fetch[Int] = Fetch(s)
```

And now we can easily receive data from the two sources in a single fetch. 

```scala
val fetchMulti: Fetch[(String, Int)] = (fetchString(1) |@| fetchLength("one")).tupled
```

Note how the two independent data fetches run in parallel, minimizing the latency cost of querying the two data sources.

```scala
fetchMulti.runA[Id]
// [4090] One Length one
// [4089] One ToString 1
// res7: cats.Id[(String, Int)] = (1,3)
```

## Caching

When fetching an identity, subsequent fetches for the same identity are cached. Let's try creating a fetch that asks for the same identity twice.

```scala
val fetchTwice: Fetch[(String, String)] = for {
  one <- fetchString(1)
  two <- fetchString(1)
} yield (one, two)
```

While running it, notice that the data source is only queried once. The next time the identity is requested it's served from the cache.

```scala
fetchTwice.runA[Id]
// [4089] One ToString 1
// res8: cats.Id[(String, String)] = (1,1)
```
## Fetch in the wild

If you wish to add your library here please consider a PR to include it in the list below.

[comment]: # (Start Copyright)
# Copyright

Fetch is designed and developed by 47 Degrees

Copyright (C) 2016-2017 47 Degrees. <http://47deg.com>

[comment]: # (End Copyright)