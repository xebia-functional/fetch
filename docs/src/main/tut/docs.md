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

[comment]: # (Start Replace)

```scala
"com.47deg" %% "fetch" % "0.7.3"
```

Or, if using Scala.js:

```scala
"com.47deg" %%% "fetch" % "0.7.3"
```

[comment]: # (End Replace)

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

- [Clump](https://github.com/getclump/clump) it's been around for a long time and is used in production systems at SoundCloud and LinkedIn. You can use it with Scala's or Twitter's Futures.

If something is missing in Fetch that stops you from using it we'd appreciate if you [open an issue in our repository](https://github.com/47deg/fetch/issues).

# Usage

In order to tell Fetch how to retrieve data, we must implement the `DataSource` typeclass.

```scala
import cats.data.NonEmptyList

trait DataSource[Identity, Result]{
  def name: String
  
  def fetch(id: Identity): IO[Option[Result]]
  
  /* `batch` is implemented in terms of `fetch` by default */
  def batch(ids: NonEmptyList[Identity]): IO[Map[Identity, Result]]
}
```

It takes two type parameters:

 - `Identity`: the identity we want to fetch (a `UserId` if we were fetching users)
 - `Result`: the type of the data we retrieve (a `User` if we were fetching users)

There are two methods: `fetch` and `batch`. `fetch` receives one identity and must return
an `IO` containing
an optional result. Returning an `Option` Fetch can detect whether an identity couldn't be fetched or no longer exists.

The `batch` method takes a non-empty list of identities and must return an `IO` containing
a map from identities to results. Accepting a list of identities gives Fetch the ability to batch requests to
the same data source, and returning a mapping from identities to results, Fetch can detect whenever an identity
couldn't be fetched or no longer exists.

## Writing your first data source

Now that we know about the `DataSource` typeclass, let's write our first data source! We'll start by implementing a data
source for fetching users given their id. The first thing we'll do is define the types for user ids and users.

```tut:silent
type UserId = Int
case class User(id: UserId, username: String)
```

We'll simulate unpredictable latency with this function.

```tut:silent
import cats.effect._
import cats.syntax.all._

import java.util.concurrent._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

val executor = new ScheduledThreadPoolExecutor(2)
val executionContext: ExecutionContext = ExecutionContext.fromExecutor(executor)
implicit val timer: Timer[IO] = IO.timer(executionContext)
implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

def latency[A](result: A, msg: String): IO[A] = for {
  _ <- IO(println(s"--> [${Thread.currentThread.getId}] $msg"))
  _ <- IO.sleep(100.milliseconds)
  _ <- IO(println(s"<-- [${Thread.currentThread.getId}] $msg"))
} yield result
```

And now we're ready to write our user data source; we'll emulate a database with an in-memory map.

```tut:silent
import cats.data.NonEmptyList
import cats.instances.list._

import fetch._

val userDatabase: Map[UserId, User] = Map(
  1 -> User(1, "@one"),
  2 -> User(2, "@two"),
  3 -> User(3, "@three"),
  4 -> User(4, "@four")
)

implicit object UserSource extends DataSource[UserId, User]{
  override def name = "User"

  override def fetch(id: UserId): IO[Option[User]] =
    latency(userDatabase.get(id), s"One User $id")

  override def batch(ids: NonEmptyList[UserId]): IO[Map[UserId, User]] =
    latency(userDatabase.filterKeys(ids.toList.toSet), s"Batch Users $ids")
}
```

Now that we have a data source we can write a function for fetching users
given an id, we just have to pass a `UserId` as an argument to `Fetch`.

```tut:silent
def getUser(id: UserId): Fetch[User] = Fetch(id) // or, more explicitly: Fetch(id)(UserSource)
```

### TODO Data sources that don't support batching

If your data source doesn't support batching, you can simply leave the `batch` method unimplemented. Note that it will use the `fetch` implementation for requesting identities one at a time.

```tut:silent
implicit object UnbatchedSource extends DataSource[Int, Int]{
  override def name = "Unbatched"

  override def fetch(id: Int): IO[Option[Int]] =
    IO(Option(id))
}
```

### TODO Data sources that only support batching

If your data source only supports querying it in batches, you can implement `fetch` in terms of `fetchMany` using `DataSource#batchingOnly`.

```tut:silent
implicit object OnlyBatchedSource extends DataSource[Int, Int]{
  override def name = "OnlyBatched"

  override def fetch(id: Int): IO[Option[Int]] =
    batch(NonEmptyList(id, List())).map(_.get(id))

  override def batch(ids: NonEmptyList[Int]): IO[Map[Int, Int]] =
    IO(ids.map(x => (x, x)).toList.toMap)
}
```

## Creating and running a fetch

We are now ready to create and run fetches. Note the distinction between Fetch creation and execution.
When we are creating and combining `Fetch` values, we are just constructing a recipe of our data
dependencies.

```tut:silent
val fetchUser: Fetch[User] = getUser(1)
```

A `Fetch` is just a value, and in order to be able to get its value we need to run it to an `IO` first. 

```tut:book
import cats.effect.IO

Fetch.run(fetchUser)
```

We can now run the `IO` and see its result:

```tut:book
Fetch.run(fetchUser).unsafeRunSync
```

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
Fetch.run(fetchTwoUsers).unsafeRunSync
```

### Batching

If we combine two independent requests to the same data source, Fetch will
automatically batch them together into a single request. Applicative operations like the product of two fetches
help us tell the library that those fetches are independent, and thus can be batched if they use the same data source:

```tut:silent
import cats.syntax.cartesian._

val fetchProduct: Fetch[(User, User)] = getUser(1).product(getUser(2))
```

Note how both ids (1 and 2) are requested in a single query to the data source when executing the fetch.

```tut:book
Fetch.run(fetchProduct).unsafeRunSync
```

### Deduplication

If two independent requests ask for the same identity, Fetch will detect it and deduplicate the id.

```tut:silent
val fetchDuped: Fetch[(User, User)] = getUser(1).product(getUser(1))
```

Note that when running the fetch, the identity 1 is only requested once even when it is needed by both fetches.

```tut:book
Fetch.run(fetchDuped).unsafeRunSync
```

### Caching

During the execution of a fetch, previously requested results are implicitly cached. This allows us to write
fetches in a very modular way, asking for all the data they need as if it
was in memory; furthermore, it also avoids re-fetching an identity that may have changed
during the course of a fetch execution, which can lead to inconsistencies in the data.

```tut:silent
val fetchCached: Fetch[(User, User)] = for {
  aUser <- getUser(1)
  anotherUser <- getUser(1)
} yield (aUser, anotherUser)
```

The above fetch asks for the same identity multiple times. Let's see what happens when executing it.

```tut:book
Fetch.run(fetchCached).unsafeRunSync
```

As you can see, the `User` with id 1 was fetched only once in a single round-trip. The next
time it was needed we used the cached versions, thus avoiding another request to the user data
source.

## Combining data from multiple sources

Now that we know about some of the optimizations that Fetch can perform to read data efficiently,
let's look at how we can combine more than one data source.


Imagine that we are rendering a blog and have the following types for posts:

```tut:silent
type PostId = Int
case class Post(id: PostId, author: UserId, content: String)
```

As you can see, every `Post` has an author, but it refers to the author by its id. We'll implement a data source for retrieving a post given a post id.

```tut:silent
val postDatabase: Map[PostId, Post] = Map(
  1 -> Post(1, 2, "An article"),
  2 -> Post(2, 3, "Another article"),
  3 -> Post(3, 4, "Yet another article")
)

implicit object PostSource extends DataSource[PostId, Post]{
  override def name = "Post"

  override def fetch(id: PostId): IO[Option[Post]] =
    latency(postDatabase.get(id), s"One Post $id")

  override def batch(ids: NonEmptyList[PostId]): IO[Map[PostId, Post]] =
    latency(postDatabase.filterKeys(ids.toList.toSet), s"Batch Posts $ids")
}

def getPost(id: PostId): Fetch[Post] = Fetch(id)
```

We can also implement a function for fetching a post's author given a post:

```tut:silent
def getAuthor(p: Post): Fetch[User] = Fetch(p.author)
```

Apart from posts, we are going to add another data source: one for post topics.

```tut:silent
type PostTopic = String
```

We'll implement a data source for retrieving a post topic given a post id.

```tut:silent
implicit object PostTopicSource extends DataSource[Post, PostTopic]{
  override def name = "Post topic"

  override def fetch(id: Post): IO[Option[PostTopic]] = {
    val topic = if (id.id % 2 == 0) "monad" else "applicative"
    latency(Option(topic), s"One Post Topic $id")
  }

  override def batch(ids: NonEmptyList[Post]): IO[Map[Post, PostTopic]] = {
    val result = ids.toList.map(id => (id, if (id.id % 2 == 0) "monad" else "applicative")).toMap
    latency(result, s"Batch Post Topics $ids")
  }
}

def getPostTopic(post: Post): Fetch[PostTopic] = Fetch(post)
```

Now that we have multiple sources let's mix them in the same fetch.

```tut:silent
val fetchMulti: Fetch[(Post, PostTopic)] = for {
  post <- getPost(1)
  topic <- getPostTopic(post)
} yield (post, topic)
```

We can now run the previous fetch, querying the posts data source first and the user data source afterwards.

```tut:book
Fetch.run(fetchMulti).unsafeRunSync
```

In the previous example, we fetched a post given its id and then fetched its topic. This
data could come from entirely different places, but Fetch makes working with heterogeneous sources
of data very easy.

### Concurrency

Combining multiple independent requests to the same data source can have two outcomes:

 - if the data sources are the same, the request is batched
 - otherwise, both data sources are queried at the same time

In the following example we are fetching from different data sources so both requests will be
evaluated together.

```tut:silent
val fetchConcurrent: Fetch[(Post, User)] = getPost(1).product(getUser(2))
```

The above example combines data from two different sources, and the library knows they are independent.

```tut:book
Fetch.run(fetchConcurrent).unsafeRunSync
```

## Combinators

Besides `flatMap` for sequencing fetches and `product` for running them concurrently, Fetch provides a number of
other combinators.

### Sequence

Whenever we have a list of fetches of the same type and want to run them concurrently, we can use the `sequence`
combinator. It takes a `List[Fetch[A]]` and gives you back a `Fetch[List[A]]`, batching the fetches to the same
data source and running fetches to different sources in parallel. Note that the `sequence` combinator is more general and works not only on lists but on any type that has a [Traverse](http://typelevel.org/cats/tut/traverse.html) instance.

```tut:silent
import cats.instances.list._
import cats.syntax.traverse._

val fetchSequence: Fetch[List[User]] = List(getUser(1), getUser(2), getUser(3)).sequence
```

Since `sequence` uses applicative operations internally, the library is able to perform optimizations across all the sequenced fetches.

```tut:book
Fetch.run(fetchSequence).unsafeRunSync
```

As you can see, requests to the user data source were batched, thus fetching all the data in one round.

### Traverse

Another interesting combinator is `traverse`, which is the composition of `map` and `sequence`.

```tut:silent
val fetchTraverse: Fetch[List[User]] = List(1, 2, 3).traverse(getUser)
```

As you may have guessed, all the optimizations made by `sequence` still apply when using `traverse`.

```tut:book
Fetch.run(fetchTraverse).unsafeRunSync
```

# Caching

As we have learned, Fetch caches intermediate results implicitly. You can
provide a prepopulated cache for running a fetch, replay a fetch with the cache of a previous
one, and even implement a custom cache.

## Prepopulating a cache

We'll be using the default in-memory cache, prepopulated with some data. The cache key of an identity
is calculated with the `DataSource`'s `name` method and the request identity.

```tut:book
val cache = InMemoryCache.from(
 (UserSource.name, 1) -> User(1, "@dialelo")
)
```

We can pass a cache as the second argument when running a fetch with `Fetch.run`.

```tut:book
Fetch.run(fetchUser, cache).unsafeRunSync
```

As you can see, when all the data is cached, no query to the data sources is executed since the results are available
in the cache.

```tut:silent
val fetchManyUsers: Fetch[List[User]] = List(1, 2, 3).traverse(getUser)
```

If only part of the data is cached, the cached data won't be asked for:

```tut:book
Fetch.run(fetchManyUsers, cache).unsafeRunSync
```

## TODO Replaying a fetch without querying any data source

When running a fetch, we are generally interested in its final result. However, we also have access to the cache
and information about the executed rounds once we run a fetch. Fetch's interpreter keeps its state in an environment
(implementing the `Env` trait), and we can get both the environment and result after running a fetch using `Fetch.runFetch`
instead of `Fetch.run` or `value.runF` via it's implicit syntax.

Knowing this, we can replay a fetch reusing the cache of a previous one. The replayed fetch won't have to call any of the
data sources.

```tut:book
val (cache, result) = Fetch.runCache(fetchManyUsers).unsafeRunSync

Fetch.run(fetchManyUsers, cache).unsafeRunSync
```

## Implementing a custom cache

The default cache is implemented as an immutable in-memory map, but users are free to use their own caches when running a fetch. Your cache should implement the `DataSourceCache` trait, and after that you can pass it to Fetch's `run` methods.

There is no need for the cache to be mutable since fetch executions run in an interpreter that uses the state monad. Note that the `update` method in the `DataSourceCache` trait yields a new, updated cache.

```scala
trait DataSourceCache {
  def insert[I, A](i: I, ds: DataSource[I, A], v: A): DataSourceIdentity, v: A): IO[DataSourceCache]
  def lookup[I, A](i: I, ds: DataSource[I, A]): IO[Option[A]]
}
```

Let's implement a cache that forgets everything we store in it.

```tut:silent
final case class ForgetfulCache() extends DataSourceCache {
  def insert[I, A](i: I, ds: DataSource[I, A], v: A): IO[ForgetfulCache] = IO(this)
  def lookup[I, A](i: I, ds: DataSource[I, A]): IO[Option[A]] = IO(None)
}
```

We can now use our implementation of the cache when running a fetch.

```tut:book
val fetchSameTwice: Fetch[(User, User)] = for {
  one <- getUser(1)
  another <- getUser(1)
} yield (one, another)

Fetch.run(fetchSameTwice, ForgetfulCache()).unsafeRunSync
```

# Batching

As we have learned, Fetch performs batched requests whenever it can. It also exposes a couple knobs
for tweaking the maximum batch size and whether multiple batches are run in parallel or sequentially.

## Maximum batch size

When implementing a `DataSource`, there is a method we can override called `maxBatchSize`. When implementing it
we can specify the maximum size of the batched requests to this data source, let's try it out:

```tut:silent
implicit object BatchedUserSource extends DataSource[UserId, User]{
  override def name = "BatchedUser"

  override def maxBatchSize: Option[Int] = Some(2)

  override def fetch(id: UserId): IO[Option[User]] =
    latency(userDatabase.get(id), s"One User $id")

  override def batch(ids: NonEmptyList[UserId]): IO[Map[UserId, User]] =
    latency(userDatabase.filterKeys(ids.toList.toSet), s"Batch Users $ids")
}

def getBatchedUser(id: Int): Fetch[User] = Fetch(id)(BatchedUserSource, cs)
```

We have defined the maximum batch size to be 2, let's see what happens when running a fetch that needs more
than two users:


```tut:book
val fetchManyBatchedUsers: Fetch[List[User]] = List(1, 2, 3, 4).traverse(getBatchedUser)

Fetch.run(fetchManyBatchedUsers).unsafeRunSync
```

## Batch execution strategy

In the presence of multiple concurrent batches, we can choose between a sequential or parallel execution strategy. By default batches will be run in parallel, but you can tweak this behaviour by overriding `DataSource#batchExection`.

```tut:silent
implicit object SequentialUserSource extends DataSource[UserId, User]{
  override def name = "SequentialUser"

  override def maxBatchSize: Option[Int] = Some(2)

  override def batchExecution: ExecutionType = Sequential

  override def fetch(id: UserId): IO[Option[User]] =
    latency(userDatabase.get(id), s"One User $id")

  override def batch(ids: NonEmptyList[UserId]): IO[Map[UserId, User]] =
    latency(userDatabase.filterKeys(ids.toList.toSet), s"Batch Users $ids")
}

def getSequentialUser(id: Int): Fetch[User] = Fetch(id)(SequentialUserSource, cs)
```

We have defined the maximum batch size to be 2 and the batch execution to be sequential, let's see what happens when running a fetch that needs more than one batch:


```tut:book
val fetchManySeqBatchedUsers: Fetch[List[User]] = List(1, 2, 3, 4).traverse(getSequentialUser)

Fetch.run(fetchManySeqBatchedUsers).unsafeRunSync
```

# Error handling

Fetch is used for reading data from remote sources and the queries we perform can and will fail at some point. There are many things that can
go wrong:
 - an exception can be thrown by client code of certain data sources
 - an identity may be missing
 - the data source may be temporarily available

Since the error cases are plenty and can't be anticipated Fetch errors are represented by the `FetchException` trait, which extends `Throwable`.
Currently fetch defines `FetchException` cases for missing identities and arbitrary exceptions but you can extend `FetchException` with any error
you want.

## Exceptions

What happens if we run a fetch and fails with an exception? We'll create a fetch that always fails to learn about it.

```tut:silent
val fetchException: Fetch[User] = Fetch.error(new Exception("Oh noes"))
```

If we try to execute to `IO` the exception will be thrown wrapped in a `FetchException`.

```tut:fail
Fetch.run(fetchException).unsafeRunSync
```

A safer version would use Cats' `.attempt` method:

```tut:book
import cats.syntax.applicativeError._

Fetch.run(fetchException).attempt.unsafeRunSync
```

### Debugging exceptions

Using fetch's debugging facilities, we can visualize a failed fetch's execution up until the point where it failed. Let's create
a fetch that fails after a couple rounds to see it in action:

```tut:silent
val failingFetch: Fetch[String] = for {
  a <- getUser(1)
  b <- getUser(2)
  c <- fetchException
} yield s"${a.username} loves ${b.username}"

val result: IO[Either[Throwable, String]] = Fetch.run(failingFetch).attempt
```

Now let's use the `fetch.debug.describe` function for describing the error if we find one:

```tut:book
import fetch.debug.describe

val value: Either[Throwable, String] = result.unsafeRunSync

println(value.fold(describe, identity _))
```

As you can see in the output from `describe`, the fetch stopped due to a `java.lang.Exception` after succesfully executing two
rounds for getting users 1 and 2.

## Missing identities

You've probably noticed that `DataSource.fetchOne` and `DataSource.fetchMany` return types help Fetch know if any requested
identity was not found. Whenever an identity cannot be found, the fetch execution will fail with an instance of `FetchException`.

The requests can be of different types, each of which is described below.

### One request

When a single identity is being fetched the request will be a `FetchOne`; it contains the data source and the identity to fetch so you
should be able to easily diagnose the failure. For ilustrating this scenario we'll ask for users that are not in the database.

```tut:silent
import fetch.debug.describe

val missingUser = getUser(5)

val result: IO[Either[Throwable, User]] = Fetch.run(missingUser).attempt
```

And now we can execute the fetch and describe its execution:

```tut:book
val value: Either[Throwable, User] = result.unsafeRunSync

println(value.fold(describe, _.toString))
```

As you can see in the output, the identity `5` for the user source was not found, thus the fetch failed without executing any rounds.
`MissingIdentity` also allows you to access the fetch request that was in progress when the error happened.

```tut:book
value match {
  case Left(mi @ MissingIdentity(id, q)) => {
    println("Data Source: " + q.dataSource.name)
    println("Identity: " + id)
  }
  case _ =>
}
```

### Multiple requests

When multiple requests to the same data source are batched and/or multiple requests are performed at the same time, is possible that more than one identity was missing. There is another error case for such situations: `MissingIdentities`, which contains a mapping from data source names to the list of missing identities.

```scala
TODO
import fetch.debug.describe

val missingUsers = List(3, 4, 5, 6).traverse(getUser)

val result: Eval[Either[FetchException, List[User]]] = missingUsers.runA[Eval].attempt
```

And now we can execute the fetch and describe its execution:

```scala
TODO
val value: Either[FetchException, List[User]] = result.value

println(value.fold(describe, _.toString))
```

The `.missing` attribute will give us the mapping from data source name to missing identities, and `.env` will give us the environment so we can track the execution of the fetch.

```scala
TODO
value match {
  case Left(mi @ MissingIdentities(_, _)) => {
    println("Missing identities " + mi.missing)
    println("Environment " + mi.env)
  }
  case _ =>
}
```

## Your own errors

# Syntax

## Implicit syntax

Fetch provides implicit syntax to lift any value to the context of a `Fetch` in addition to the most common used
combinators active within `Fetch` instances.

### pure

Plain values can be lifted to the Fetch monad with `value.fetch`:

```scala
TODO
val fetchPure: Fetch[Int] = 42.fetch
```

Executing a pure fetch doesn't query any data source, as expected.

```scala
TODO
fetchPure.runA[Id]
```

### error

Errors can also be lifted to the Fetch monad via `exception.fetch`.

```scala
TODO
val fetchFail: Fetch[Int] = new Exception("Something went terribly wrong").fetch
```

Note that interpreting an errorful fetch to `Id` will throw the exception.

```scala
TODO
fetchFail.runA[Id]
```

### join

We can compose two independent fetches with `fetch1.join(fetch2)`.

```scala
TODO
val fetchJoined: Fetch[(Post, User)] = getPost(1).join(getUser(2))
```

If the fetches are to the same data source they will be batched; if they aren't, they will be evaluated at the same time.

```scala
TODO
fetchJoined.runA[Id]
```

### runA

Run directly any fetch with `fetch1.runA`.

```scala
TODO
getPost(1).runA[Id]
```

### runE

Run a fetch an get it's runtime environment `fetch1.runE`.

```scala
TODO
getPost(1).runE[Id]
```

### runF

Run a fetch obtaining the environment and final value `fetch1.runF`.

```scala
TODO
getPost(1).runF[Id]
```

## Companion object

We've been using Cats' syntax and `fetch.syntax` throughout the examples since it's more concise and general than the
methods in the `Fetch` companion object. However, you can use the methods in the companion object
directly.

Note that using cats syntax gives you a plethora of combinators, much richer that what the companion object provides.

### pure

Plain values can be lifted to the Fetch monad with `Fetch#pure`:

```tut:silent
val fetchPure: Fetch[Int] = Fetch.pure(42)
```

Executing a pure fetch doesn't query any data source, as expected.

```scala
TODO
Fetch.run[Id](fetchPure)
```

### error

Errors can also be lifted to the Fetch monad via `Fetch#error`.

```scala
TODO
val fetchFail: Fetch[Int] = Fetch.error(new Exception("Something went terribly wrong"))
```

Note that interpreting an errorful fetch to `Id` will throw the exception.

```scala
TODO
Fetch.run[Id](fetchFail)
```

### join

We can compose two independent fetches with `Fetch#join`.

```scala
TODO
val fetchJoined: Fetch[(Post, User)] = Fetch.join(getPost(1), getUser(2))
```

If the fetches are to the same data source they will be batched; if they aren't, they will be evaluated at the same time.

```scala
TODO
Fetch.run[Id](fetchJoined)
```

### sequence

The `Fetch#sequence` combinator turns a `List[Fetch[A]]` into a `Fetch[List[A]]`, running all the fetches concurrently
and batching when possible.

```scala
TODO
val fetchSequence: Fetch[List[User]] = Fetch.sequence(List(getUser(1), getUser(2), getUser(3)))
```

```scala
TODO
Fetch.run[Id](fetchSequence)
```

### traverse

The `Fetch#traverse` combinator is a combination of `map` and `sequence`.

```scala
TODO
val fetchTraverse: Fetch[List[User]] = Fetch.traverse(List(1, 2, 3))(getUser)
```

Note that `Fetch#traverse` is not as general as the `traverse` method from `Traverse`, but the resulting `Fetch` can be parallelized more safely if the number of elements is very large.

```scala
TODO
Fetch.run[Id](fetchTraverse)
```

## cats

Fetch is built using Cats' Free monad construction and thus works out of the box with
cats syntax. Using Cats' syntax, we can make fetch declarations more concise, without
the need to use the combinators in the `Fetch` companion object.

Fetch provides its own instance of `Applicative[Fetch]`. Whenever we use applicative
operations on more than one `Fetch`, we know that the fetches are independent meaning
we can perform optimizations such as batching and concurrent requests.

If we were to use the default `Applicative[Fetch]` operations, which are implemented in terms of `flatMap`,
we wouldn't have information about the independency of multiple fetches.

### Applicative

The tuple apply syntax allows us to combine multiple independent fetches, even when they
are from different types, and apply a pure function to their results. We can use it
as a more powerful alternative to the `product` method or `Fetch#join`:

```scala
TODO
import cats.syntax.apply._

val fetchThree: Fetch[(Post, User, Post)] = (getPost(1), getUser(2), getPost(2)).tupled
```

Notice how the queries to posts are batched.

```scala
TODO
fetchThree.runA[Id]
```

More interestingly, we can use it to apply a pure function to the results of various
fetches.

```scala
TODO
val fetchFriends: Fetch[String] = (getUser(1), getUser(2)).mapN { (one, other) =>
  s"${one.username} is friends with ${other.username}"
}

fetchFriends.runA[Id]
```

The above example is equivalent to the following using the `Fetch#join` method:

```scala
TODO
val fetchFriends: Fetch[String] = Fetch.join(getUser(1), getUser(2)).map { case (one, other) =>
  s"${one.username} is friends with ${other.username}"
}

fetchFriends.runA[Id]
```

# Debugging

We have introduced the handy `fetch.debug.describe` function for debugging errors, but it can do more than that. It can also give you a detailed description of
a fetch execution given an environment.

Add the following line to your dependencies for including Fetch's debugging facilities:

```scala
"com.47deg" %% "fetch-debug" % "0.6.0"
```

## Fetch execution

We are going to create an interesting fetch that applies all the optimizations available (caching, batching and concurrent request) for ilustrating how we can
visualize fetch executions using the environment.

```scala
TODO
val batched: Fetch[List[User]] = Fetch.multiple(1, 2)(UserSource)
val cached: Fetch[User] = getUser(2)
val concurrent: Fetch[(List[User], List[Post])] = (List(1, 2, 3).traverse(getUser), List(1, 2, 3).traverse(getPost)).tupled

val interestingFetch = for {
  users <- batched
  anotherUser <- cached
  _ <- concurrent
} yield "done"
```

Now that we have the fetch let's run it, get the environment and visualize its execution using the `describe` function:

```scala
TODO
import fetch.debug.describe

val env = interestingFetch.runE[Id]

println(describe(env))
```

Let's break down the output from `describe`:

 - The first line shows the total time that took to run the fetch
 - The nested lines represent the different rounds of execution
  + "Fetch one" rounds are executed for getting an identity from one data source
  + "Fetch many" rounds are executed for getting a batch of identities from one data source
  + "Concurrent" rounds are multiple "one" or "many" rounds for different data sources executed concurrently

# Resources

- [Code](https://github.com/47deg/fetch) on GitHub.
- [Documentation site](http://47deg.github.io/fetch/)
- [Fetch: Simple & Efficient data access](https://www.youtube.com/watch?v=45fcKYFb0EU) talk at [Typelevel Summit in Oslo](http://typelevel.org/event/2016-05-summit-oslo/)

# Acknowledgements

Fetch stands on the shoulders of giants:

- [Haxl](https://github.com/facebook/haxl) is Facebook's implementation (Haskell) of the [original paper Fetch is based on](http://community.haskell.org/~simonmar/papers/haxl-icfp14.pdf).
- [Clump](https://github.com/getclump/clump) has inspired the signature of the `DataSource#fetch*` methods.
- [Stitch](https://engineering.twitter.com/university/videos/introducing-stitch) is an in-house Twitter library that is not open source but has inspired Fetch's high-level API.
- [Cats](http://typelevel.org/cats/), a library for functional programming in Scala.
- [Monix](https://monix.io) high-performance and multiplatform (Scala / Scala.js) asynchronous programming library.
