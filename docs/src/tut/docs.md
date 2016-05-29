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
import monix.eval.Task
import cats.data.NonEmptyList

trait DataSource[Identity, Result]{
  def fetchOne(id: Identity): Task[Option[Result]]
  def fetchMany(ids: NonEmptyList[Identity]): Task[Map[Identity, Result]]
}
```

It takes two type parameters:

 - `Identity`: the identity we want to fetch (a `UserId` if we were fetching users)
 - `Result`: the type of the data we retrieve (a `User` if we were fetching users)

There are two methods: `fetchOne` and `fetchMany`. `fetchOne` receives one identity and must return
a [Task](https://github.com/monixio/monix/blob/dd6e47b7b870b38825d516f846f6e074d78d5c40/monix-eval/shared/src/main/scala/monix/eval/Task.scala) containing
an optional result. Returning an `Option` Fetch can detect whether an identity couldn't be fetched or no longer exists.

`fetchMany` method takes a non-empty list of identities and must return a `Task` containing
a map from identities to results. Accepting a list of identities gives Fetch the ability to batch requests to
the same data source, and returning a mapping from identities to results, Fetch can detect whenever an identity
couldn't be fetched or no longer exists.

Returning `Task` makes it possible to try to run a fetch synchronously or asynchronously, choose a scheduler for the I/O bound nature of reading remote data, error handling, memoization and composability.

## Writing your first data source

Now that we know about the `DataSource` typeclass, let's write our first data source! We'll start by implementing a data
source for fetching users given their id. The first thing we'll do is define the types for user ids and users.

```tut:silent
type UserId = Int
case class User(id: UserId, username: String)
```

And now we're ready to write our user data source; we'll emulate a database with an in-memory map.

```tut:silent
import monix.eval.Task
import cats.data.NonEmptyList
import cats.std.list._

import fetch._

val userDatabase: Map[UserId, User] = Map(
  1 -> User(1, "@one"),
  2 -> User(2, "@two"),
  3 -> User(3, "@three")
)

implicit object UserSource extends DataSource[UserId, User]{
  override def fetchOne(id: UserId): Task[Option[User]] = {
    Task.now({
      println(s"Fetching one user $id")
      userDatabase.get(id)
    })
  }
  override def fetchMany(ids: NonEmptyList[UserId]): Task[Map[UserId, User]] = {
    Task.now({
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

```tut:silent
implicit object UnbatchedSource extends DataSource[Int, Int]{
  override def fetchOne(id: Int): Task[Option[Int]] = {
    Task(Option(id))
  }
  override def fetchMany(ids: NonEmptyList[Int]): Task[Map[Int, Int]] = {
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

A `Fetch` is just a value, and in order to be able to execute it we need to run it to a `Task` first. Running `fetchUser` will give as a `Task[User]`, which we can later execute for the performing the effects of the fetch.

```tut:book
import fetch.syntax._

val user: Task[User] = fetchUser.runA
```

We'll try to evaluate the Fetch synchronously with `Task#coeval`. `Coeval` is a type similar to `Task` but which can be evaluated synchronously with its `.value` method. Note that for executing tasks a [monix Scheduler](http://monix.io/docs/2x/execution/scheduler.html) must be implicitly found.

```tut:book
import monix.execution.Scheduler.Implicits.global

val co = user.coeval

co.value
```

In the previous examples, we:

- created a fetch for a `User` using the `getUser` function
- interpreted the fetch to a `Task[User]` using the syntax `runA` that delegate to `Fetch.run`
- converted `Task[User]` to `Coeval[Either[CancelableFuture[User], User]]` and evaluated it to a `Right[User]`

As you can see, the fetch was executed in one round to fetch the user and was finished after that.

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
val result: Task[(User, User)] = fetchTwoUsers.runA
```

Althought `fetchTwoUsers` needs two rounds to complete we still can execute it synchronously:

```tut:book
result.coeval.value
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
val result: Task[(User, User)] = fetchProduct.runA
```

```tut:invisible
import scala.concurrent._
import scala.concurrent.duration._

def await[A](t: Task[A]): A = Await.result(t.runAsync, Duration.Inf)
```

Let's pretend we have a function from `Task[A]` to `A` called `await`.

```tut:book
await(result)
```

### Deduplication

If two independent requests ask for the same identity, Fetch will detect it and deduplicate the id.

```tut:silent
val fetchDuped: Fetch[(User, User)] = getUser(1).product(getUser(1))
```

Note that when running the fetch, the identity 1 is only requested once even when it is needed by both fetches.

```tut:book
val result: Task[(User, User)] = fetchDuped.runA

await(result)
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
val result: Task[(User, User)] = fetchCached.runA

await(result)
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
  override def fetchOne(id: PostId): Task[Option[Post]] = {
    Task({
      println(s"Fetching one post $id")
      postDatabase.get(id)
    })
  }
  override def fetchMany(ids: NonEmptyList[PostId]): Task[Map[PostId, Post]] = {
    Task({
      println(s"Fetching many posts $ids")
      postDatabase.filterKeys(ids.unwrap.contains)
    })
  }
}

def getPost(id: PostId): Fetch[Post] = Fetch(id)
```

We can also implement a function for fetching a post's author given a post:

```tut:silent
def getAuthor(p: Post): Fetch[User] = Fetch(p.author)
```

Now that we have multiple sources let's mix them in the same fetch.

```tut:silent
val fetchMulti: Fetch[(Post, User)] = for {
  post <- getPost(1)
  user <- getAuthor(post)
} yield (post, user)
```

We can now run the previous fetch, querying the posts data source first and the user data source afterwards.

```tut:book
val result: Task[(Post, User)] = fetchMulti.runA

await(result)
```

In the previous example, we fetched a post given its id and then fetched its author. This
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
val result: Task[(Post, User)] = fetchConcurrent.runA

await(result)
```

Since we are interpreting the fetch to the `Id` monad, that doesn't give us any parallelism; the fetches
will be run sequentially. However, if we interpret it to a `Future` each request will run in its own logical
thread.

## Combinators

Besides `flatMap` for sequencing fetches and `product` for running them concurrently, Fetch provides a number of
other combinators.

### Sequence

Whenever we have a list of fetches of the same type and want to run them concurrently, we can use the `sequence`
combinator. It takes a `List[Fetch[A]]` and gives you back a `Fetch[List[A]]`, batching the fetches to the same
data source and running fetches to different sources in parallel. Note that the `sequence` combinator is more general and works not only on lists but on any type that has a [Traverse](http://typelevel.org/cats/tut/traverse.html) instance.

```tut:silent
import cats.std.list._
import cats.syntax.traverse._

val fetchSequence: Fetch[List[User]] = List(getUser(1), getUser(2), getUser(3)).sequence
```

Since `sequence` uses applicative operations internally, the library is able to perform optimizations across all the sequenced fetches.

```tut:book
await(fetchSequence.runA)
```

As you can see, requests to the user data source were batched, thus fetching all the data in one round.

### Traverse

Another interesting combinator is `traverse`, which is the composition of `map` and `sequence`.

```tut:silent
val fetchTraverse: Fetch[List[User]] = List(1, 2, 3).traverse(getUser)
```

As you may have guessed, all the optimizations made by `sequence` still apply when using `traverse`.

```tut:book
await(fetchTraverse.runA)
```

# Caching

As we have learned, Fetch caches intermediate results implicitly using a cache. You can
provide a prepopulated cache for running a fetch, replay a fetch with the cache of a previous
one, and even implement a custom cache.

## Prepopulating a cache

We'll be using the default in-memory cache, prepopulated with some data. The cache key of an identity
is calculated with the `DataSource`'s `identity` method.

```tut:book
val cache = InMemoryCache(UserSource.identity(1) -> User(1, "@dialelo"))
```

We can pass a cache as the second argument when running a fetch with `Fetch.run`.

```tut:book
await(fetchUser.runA(cache))
```

As you can see, when all the data is cached, no query to the data sources is executed since the results are available
in the cache.

```tut:silent
val fetchManyUsers: Fetch[List[User]] = List(1, 2, 3).traverse(getUser)
```

If only part of the data is cached, the cached data won't be asked for:

```tut:book
await(fetchManyUsers.runA(cache))
```

## Replaying a fetch without querying any data source

When running a fetch, we are generally interested in its final result. However, we also have access to the cache
and information about the executed rounds once we run a fetch. Fetch's interpreter keeps its state in an environment
(implementing the `Env` trait), and we can get both the environment and result after running a fetch using `Fetch.runFetch`
instead of `Fetch.run` or `value.runF` via it's implicit syntax.

Knowing this, we can replay a fetch reusing the cache of a previous one. The replayed fetch won't have to call any of the
data sources.

```tut:book
val populatedCache = await(fetchManyUsers.runE.map(_.cache))

val result: List[User] =  await(fetchManyUsers.runA(populatedCache))
```

## Implementing a custom cache

The default cache is implemented as an immutable in-memory map, but users are free to use their own caches when running a fetch. Your cache should implement the `DataSourceCache` trait, and after that you can pass it to Fetch's `run` methods.

There is no need for the cache to be mutable since fetch executions run in an interpreter that uses the state monad. Note that the `update` method in the `DataSourceCache` trait yields a new, updated cache.

```scala
trait DataSourceCache {
  def update[A](k: DataSourceIdentity, v: A): DataSourceCache
  def get(k: DataSourceIdentity): Option[Any]
}
```

Let's implement a cache that forgets everything we store in it.

```tut:silent
final case class ForgetfulCache() extends DataSourceCache {
  override def get(k: DataSourceIdentity): Option[Any] = None
  override def update[A](k: DataSourceIdentity, v: A): ForgetfulCache = this
}
```

We can now use our implementation of the cache when running a fetch.

```tut:book
val fetchSameTwice: Fetch[(User, User)] = for {
  one <- getUser(1)
  another <- getUser(1)
} yield (one, another)
  
await(fetchSameTwice.runA(ForgetfulCache()))
```

# Error handling

`Task` provides a number of combinators for dealing with and recovering from errors. One of the most interesting combinators is `attempt`, which given a `Task[A]` yields a `Task[Throwable Xor A]`. Knowing this, we can run fetches
and not worry about exceptions. Let's create a fetch that always fails when executed:

```tut:silent
val fetchError: Fetch[User] = (new Exception("Oh noes")).fetch
```

If we try to execute it the exception will be thrown.

```tut:fail
await(fetchError.runA)
```

We can use the `ApplicativeError[Task, Throwable]#attempt` to convert a fetch result into a disjuntion and avoid throwing exceptions. Fetch provides an implicit instance of ApplicativeError, let's import `fetch.implicits._` to have it available.

```tut:silent
import fetch.implicits._
import cats.ApplicativeError
import cats.data.Xor
```

Now we can convert `Task[User]` into `Task[Throwable Xor User]` and capture exceptions in the left of the disjunction.

```tut:book
val safeResult: Task[Throwable Xor User] = ApplicativeError[Task, Throwable].attempt(fetchError.runA)
val finalValue: Throwable Xor User = await(safeResult)
```

And more succintly with Cat's applicative error syntax.

```tut:book
import cats.syntax.applicativeError._

await(fetchError.runA.attempt)
```


## Missing identities

You've probably noticed that `DataSource.fetch` takes a list of identities and returns a map of identities to their result, taking
into account the possibility of some identities not being found. Whenever an identity cannot be found, the fetch execution will
fail.

Whenever a fetch fails, a `FetchFailure` exception is thrown. The `FetchFailure` will have the environment, which gives you information
about the execution of the fetch.

# Syntax

## Implicit syntax

Fetch provides implicit syntax to lift any value to the context of a `Fetch` in addition to the most common used
combinators active within `Fetch` instances.

### pure

Plain values can be lifted to the Fetch monad with `value.fetch`:

```tut:silent
val fetchPure: Fetch[Int] = 42.fetch
```

Executing a pure fetch doesn't query any data source and can be run synchronously, as expected.

```tut:book
fetchPure.runA.coeval.value
```

### error

Errors can also be lifted to the Fetch monad via `exception.fetch`. 

```tut:silent
val fetchFail: Fetch[Int] = Fetch.error(new Exception("Something went terribly wrong"))
```

Note that interpreting an errorful fetch to `Task` won't throw the exception until we execute it.

```tut:fail
await(fetchFail.runA)
```

### join

We can compose two independent fetches with `fetch1.join(fetch2)`.

```tut:silent
val fetchJoined: Fetch[(Post, User)] = getPost(1).join(getUser(2))
```

If the fetches are to the same data source they will be batched; if they aren't, they will be evaluated at the same time.

```tut:book
await(fetchJoined.runA)
```

### runA

Run directly any fetch to a `Task` with `fetch1.runA`.

```tut:book
await(getPost(1).runA)
```

### runE

Extract a fetch an get it's runtime environment `fetch1.runE`.

```tut:book
await(getPost(1).runE)
```

### runF

Run a fetch obtaining the environment and final value `fetch1.runF`.

```tut:book
await(getPost(1).runF)
```

## Companion object

We've been using `cats.syntax' and `fetch.syntax` throughout the examples since it's more concise and general than the
methods in the `Fetch` companion object. However, you can use the methods in the companion object
directly.

Note that using cats syntax gives you a plethora of combinators, much richer that what the companion object provides.

### pure

Plain values can be lifted to the Fetch monad with `Fetch#pure`:

```tut:silent
val fetchPure: Fetch[Int] = Fetch.pure(42)
```

Executing a pure fetch doesn't query any data source and can be run synchronously, as expected.

```tut:book
Fetch.run(fetchPure).coeval.value
```

### error

Errors can also be lifted to the Fetch monad via `Fetch#error`. 

```tut:silent
val fetchFail: Fetch[Int] = Fetch.error(new Exception("Something went terribly wrong"))
```

Note that interpreting an errorful fetch to `Task` won't throw the exception until we execute it.

```tut:fail
await(Fetch.run(fetchFail))
```

### join

We can compose two independent fetches with `Fetch#join`.

```tut:silent
val fetchJoined: Fetch[(Post, User)] = Fetch.join(getPost(1), getUser(2))
```

If the fetches are to the same data source they will be batched; if they aren't, they will be evaluated at the same time.

```tut:book
await(Fetch.run(fetchJoined))
```

### sequence

The `Fetch#sequence` combinator turns a `List[Fetch[A]]` into a `Fetch[List[A]]`, running all the fetches concurrently
and batching when possible.

```tut:silent
val fetchSequence: Fetch[List[User]] = Fetch.sequence(List(getUser(1), getUser(2), getUser(3)))
```

Note that `Fetch#sequence` is not as general as the `sequence` method from `Traverse`, but performs the same optimizations.

```tut:book
await(Fetch.run(fetchSequence))
```

### traverse

The `Fetch#traverse` combinator is a combination of `map` and `sequence`.

```tut:silent
val fetchTraverse: Fetch[List[User]] = Fetch.traverse(List(1, 2, 3))(getUser)
```

Note that `Fetch#traverse` is not as general as the `traverse` method from `Traverse`, but performs the same optimizations.

```tut:book
await(Fetch.run(fetchTraverse))
```

## cats

Fetch is built using cats' Free monad construction and thus works out of the box with
cats syntax. Using cats' syntax, we can make fetch declarations more concise, without
the need to use the combinators in the `Fetch` companion object.

Fetch provides its own instance of `Applicative[Fetch]`. Whenever we use applicative
operations on more than one `Fetch`, we know that the fetches are independent meaning
we can perform optimizations such as batching and concurrent requests.

If we were to use the default `Applicative[Fetch]` operations, which are implemented in terms of `flatMap`,
we wouldn't have information about the independency of multiple fetches.

### Applicative

The `|@|` operator allows us to combine multiple independent fetches, even when they
are from different types, and apply a pure function to their results. We can use it
as a more powerful alternative to the `product` method or `Fetch#join`:

```tut:silent
import cats.syntax.cartesian._

val fetchThree: Fetch[(Post, User, Post)] = (getPost(1) |@| getUser(2) |@| getPost(2)).tupled
```

Notice how the queries to posts are batched.

```tut:book
await(fetchThree.runA)
```

More interestingly, we can use it to apply a pure function to the results of various
fetches.

```tut:book
val fetchFriends: Fetch[String] = (getUser(1) |@| getUser(2)).map({ (one, other) =>
  s"${one.username} is friends with ${other.username}"
})

await(fetchFriends.runA)
```

The above example is equivalent to the following using the `Fetch#join` method:

```tut:book
val fetchFriends: Fetch[String] = Fetch.join(getUser(1), getUser(2)).map({ case (one, other) =>
  s"${one.username} is friends with ${other.username}"
})

await(fetchFriends.runA)
```

# Choosing a scheduler

The [Monix docs](http://monix.io/docs/2x/execution/scheduler.html) go in great detail about how scheduling works and you should refer there for the documentation.

## JVM (Scala)

When reading data in the JVM, you may want to create an unbounded thread pool with `monix.execution.Scheduler.io` for running your fetches.

```tut:book
import monix.execution.Scheduler

// unbounded thread pool for I/O bound tasks
implicit val ioScheduler: Scheduler = Scheduler.io(name="my-io-scheduler")

await(fetchFriends.runA)
```

## JS (Scala.js)

When needing to choose a scheduler in a JS environment with Scala.js refer to the [monix docs](http://monix.io/docs/2x/execution/scheduler.html#builders-for-javascript).

# Resources

- [Code](https://github.com/47deg/fetch) on GitHub.
- [Documentation site](http://47deg.github.io/fetch/)
- [Fetch: Simple & Efficient data access](https://www.youtube.com/watch?v=45fcKYFb0EU) talk at [Typelevel Summit in Oslo](http://typelevel.org/event/2016-05-summit-oslo/)

# Acknowledgements

Fetch stands on the shoulders of giants:

- [Haxl](https://github.com/facebook/haxl) is Facebook's implementation (Haskell) of the [original paper Fetch is based on](http://community.haskell.org/~simonmar/papers/haxl-icfp14.pdf).
- [Clump](http://getclump.io) has inspired the signature of the `DataSource#fetch` method.
- [Stitch](https://engineering.twitter.com/university/videos/introducing-stitch) is an in-house Twitter library that is not open source but has inspired Fetch's high-level API.
- [Cats](http://typelevel.org/cats/), a library for functional programming in Scala.
- [Monix](https://monix.io) high-performance and multiplatform (Scala / Scala.js) asynchronous programming library.
