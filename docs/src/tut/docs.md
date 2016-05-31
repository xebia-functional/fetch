---
layout: docs
---

# Introduction

Fetch is a library that allows your data fetches to be written in a concise,
composable way while executing efficiently. You don't need to use any explicit
concurrency construct but existing idioms: applicative for concurrency and
monad for sequencing.

Oftentimes, our applications read and manipulate data from a variety of
different sources such as databases, web services or file systems. These data
sources are subject to latency, and we'd prefer to query them efficiently.

If we are just reading data, we can make a series of optimizations such as:

 - batching requests to the same data source
 - requesting independent data from different sources in parallel
 - caching previously seen results

However, if we mix these optimizations with the code that fetches the data
we may end up trading clarity for performance. Furthermore, we are
mixing low-level (optimization) and high-level (business logic with the data
we read) concerns.

# Installation

To begin, add the following dependency to your SBT build file:

```scala
"com.fortysevendeg" %% "fetch" %% "0.2.0"
```

Or, if using Scala.js:

```scala
"com.fortysevendeg" %%% "fetch" %% "0.2.0"
```

Now you'll have Fetch available in both Scala and Scala.js.

```tut:invisible
val out = Console.out

def println(msg: String): Unit = {
  Console.withOut(out) {
    Console.println(msg)
  }
}
```

## Alternatives

There are other libraries in Scala that implement the same optimizations as Fetch does and have different design decisions. If Fetch is not suitable for you these alternatives may be a better fit:

- [Clump](http://getclump.io/) it's been around for a long time and is used in production systems at SoundCloud and LinkedIn. You can use it with Scala's or Twitter's Futures.
- [Resolvable](https://github.com/resolvable/resolvable) can be used with Scala Futures.

If something is missing in Fetch that stops you from using it we'd appreciate if you [open an issue in our repository](https://github.com/47deg/fetch/issues).

# Usage

In order to tell Fetch how to retrieve data, we must implement the `DataSource` typeclass.

```scala
import cats.data.NonEmptyList

trait DataSource[Identity, Result]{
  def fetchOne(id: Identity): Query[Option[Result]]
  def fetchMany(ids: NonEmptyList[Identity]): Query[Map[Identity, Result]]
}
```

It takes two type parameters:

 - `Identity`: the identity we want to fetch (a `UserId` if we were fetching users)
 - `Result`: the type of the data we retrieve (a `User` if we were fetching users)

There are two methods: `fetchOne` and `fetchMany`. `fetchOne` receives one identity and must return
a `Query` containing
an optional result. Returning an `Option` Fetch can detect whether an identity couldn't be fetched or no longer exists.

`fetchMany` method takes a non-empty list of identities and must return a `Query` containing
a map from identities to results. Accepting a list of identities gives Fetch the ability to batch requests to
the same data source, and returning a mapping from identities to results, Fetch can detect whenever an identity
couldn't be fetched or no longer exists.

Returning `Query` makes it possible to run a fetch independently of the target monad.

## Writing your first data source

Now that we know about the `DataSource` typeclass, let's write our first data source! We'll start by implementing a data
source for fetching users given their id. The first thing we'll do is define the types for user ids and users.

```tut:silent
type UserId = Int
case class User(id: UserId, username: String)
```

And now we're ready to write our user data source; we'll emulate a database with an in-memory map.

```tut:silent
import cats.data.NonEmptyList
import cats.std.list._

import fetch._

val userDatabase: Map[UserId, User] = Map(
  1 -> User(1, "@one"),
  2 -> User(2, "@two"),
  3 -> User(3, "@three")
)

implicit object UserSource extends DataSource[UserId, User]{
  override def fetchOne(id: UserId): Query[Option[User]] = {
    Query.now({
      println(s"Fetching one user $id")
      userDatabase.get(id)
    })
  }
  override def fetchMany(ids: NonEmptyList[UserId]): Query[Map[UserId, User]] = {
    Query.now({
      println(s"Fetching many users $ids")
      userDatabase.filterKeys(ids.unwrap.contains)
    })
  }
}
```

Now that we have a data source we can write a function for fetching users
given an id, we just have to pass a `UserId` as an argument to `Fetch`.

```tut
def getUser(id: UserId): Fetch[User] = Fetch(id) // or, more explicitly: Fetch(id)(UserSource)
```

### Data sources that don't support batching

If your data source doesn't support batching, you can use the `DataSource#batchingNotSupported` method as the implementation
of `fetchMany`. Note that it will use the `fetchOne` implementation for requesting identities one at a time.

```scala
implicit object UnbatchedSource extends DataSource[Int, Int]{
  override def fetchOne(id: Int): Query[Option[Int]] = {
    Query(Option(id))
  }
  override def fetchMany(ids: NonEmptyList[Int]): Query[Map[Int, Int]] = {
    batchingNotSupported(ids)
  }
}
```

## Creating and running a fetch

We are now ready to create and run fetches. Note the distinction between Fetch creation and execution.
When we are creating and combining `Fetch` values, we are just constructing a recipe of our data
dependencies.

```tut:book
val fetchUser: Fetch[User] = getUser(1)
```

A `Fetch` is just a value, and in order to be able to execute it we need to run it to a `Future` first. Running `fetchUser` will give as a `Future[User]`.

```tut:book
import scala.concurrent._
import ExecutionContext.Implicits.global

import fetch.implicits._
import fetch.syntax._

val user: Future[User] = fetchUser.runA[Future]
```

TODO

### Sequencing

When we have two fetches that depend on each other, we can use `flatMap` to combine them. The most straightforward way is to use a for comprehension:

```tut:silent
val fetchTwoUsers: Fetch[(User, User)] = for {
  aUser <- getUser(1)
  anotherUser <- getUser(aUser.id + 1)
} yield (aUser, anotherUser)
```

When composing fetches with `flatMap` we are telling Fetch that the second one depends on the previous one, so it isn't able to make any optimizations. When running the above fetch, we will query the user data source in two rounds: one for the user with id 1 and another for the user with id 2.

```tut:book
val result: Future[(User, User)] = fetchTwoUsers.runA[Future]
```

Althought `fetchTwoUsers` needs two rounds to complete we still can execute it synchronously:

