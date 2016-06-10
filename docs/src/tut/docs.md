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
"com.fortysevendeg" %% "fetch" % "0.2.0"
```

Or, if using Scala.js:

```scala
"com.fortysevendeg" %%% "fetch" % "0.2.0"
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

We'll simulate unpredictable latency with this function.

```tut:silent
def latency[A](result: A, msg: String) = {
  val id = Thread.currentThread.getId
  println(s"~~> [$id] $msg")
  Thread.sleep(100)
  println(s"<~~ [$id] $msg")
  result
}
```

And now we're ready to write our user data source; we'll emulate a database with an in-memory map.

```tut:silent
import cats.data.NonEmptyList
import cats.std.list._

import fetch._

val userDatabase: Map[UserId, User] = Map(
  1 -> User(1, "@one"),
  2 -> User(2, "@two"),
  3 -> User(3, "@three"),
  4 -> User(4, "@four")
)

implicit object UserSource extends DataSource[UserId, User]{
  override def fetchOne(id: UserId): Query[Option[User]] = {
    Query.later({
	  latency(userDatabase.get(id), s"One User $id")
    })
  }
  override def fetchMany(ids: NonEmptyList[UserId]): Query[Map[UserId, User]] = {
    Query.later({
	  latency(userDatabase.filterKeys(ids.unwrap.contains), s"Many Users $ids")
    })
  }
}
```

Now that we have a data source we can write a function for fetching users
given an id, we just have to pass a `UserId` as an argument to `Fetch`.

```tut:silent
def getUser(id: UserId): Fetch[User] = Fetch(id) // or, more explicitly: Fetch(id)(UserSource)
```

### Data sources that don't support batching

If your data source doesn't support batching, you can use the `DataSource#batchingNotSupported` method as the implementation
of `fetchMany`. Note that it will use the `fetchOne` implementation for requesting identities one at a time.

```tut:silent
implicit object UnbatchedSource extends DataSource[Int, Int]{
  override def fetchOne(id: Int): Query[Option[Int]] = {
    Query.now(Option(id))
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

```tut:silent
val fetchUser: Fetch[User] = getUser(1)
```

A `Fetch` is just a value, and in order to be able to get its value we need to run it to a monad first. The
target monad `M[_]` must be able to lift a `Query[A]` to `M[A]`, evaluating the query in the monad's context.

We'll run `fetchUser` using `Id` as our target monad, so let's do some imports first. Note that interpreting
a fetch to a non-concurrency monad like `Id` or `Eval` is only recommended for trying things out in a Scala
console, that's why for using them you need to import `fetch.unsafe.implicits`.

```tut:silent
import cats.Id
import fetch.unsafe.implicits._
import fetch.syntax._
```

Note that running a fetch to non-concurrency monads like `Id` or `Eval` is not supported in Scala.js.
In real-life scenarios you'll want to run your fetches to `Future` or a `Task` type provided by a library like
[Monix](https://monix.io/) or [fs2](https://github.com/functional-streams-for-scala/fs2), both of which are supported
in Fetch.

We can now run the fetch and see its result:

```tut:book
fetchUser.runA[Id]
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
fetchTwoUsers.runA[Id]
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
fetchProduct.runA[Id]
```

### Deduplication

If two independent requests ask for the same identity, Fetch will detect it and deduplicate the id.

```tut:silent
val fetchDuped: Fetch[(User, User)] = getUser(1).product(getUser(1))
```

Note that when running the fetch, the identity 1 is only requested once even when it is needed by both fetches.

```tut:book
fetchDuped.runA[Id]
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
fetchCached.runA[Id]
```

As you can see, the `User` with id 1 was fetched only once in a single round-trip. The next
time it was needed we used the cached versions, thus avoiding another request to the user data
source.


## Queries

Queries are a way of separating the computation required to read a piece of data from the context in
which is run. Let's look at the various ways we have of constructing queries.

### Synchronous

A query can be synchronous, and we may want to evaluate it when `fetchOne` and `fetchMany`
are called. We can do so with `Query#now`:

```tut:book
Query.now(42)
```

You can also construct lazy queries that can evaluate synchronously with `Query#later`:

```tut:book
Query.later({ println("Computing 42"); 42 })
```

Synchronous queries simply wrap a Cats' `Eval` instance, which captures the notion of a lazy synchronous
computation. You can lift an `Eval[A]` into a `Query[A]` too:

```tut:book
import cats.Eval

Query.sync(Eval.always({ println("Computing 42"); 42 }))
```

### Asynchronous

Asynchronous queries are constructed passing a function that accepts a callback (`A => Unit`) and an errback
(`Throwable => Unit`) and performs the asynchronous computation. Note that you must ensure that either the
callback or the errback are called.

```tut:book
Query.async((ok: (Int => Unit), fail) => {
  Thread.sleep(100)
  ok(42)
})
```

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
  override def fetchOne(id: PostId): Query[Option[Post]] = {
    Query.later({
	  latency(postDatabase.get(id), s"One Post $id")
    })
  }
  override def fetchMany(ids: NonEmptyList[PostId]): Query[Map[PostId, Post]] = {
    Query.later({
	  latency(postDatabase.filterKeys(ids.unwrap.contains), s"Many Posts $ids")
    })
  }
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
  override def fetchOne(id: Post): Query[Option[PostTopic]] = {
    Query.later({
      val topic = if (id.id % 2 == 0) "monad" else "applicative"
      latency(Option(topic), s"One Post Topic $id")
    })
  }
  override def fetchMany(ids: NonEmptyList[Post]): Query[Map[Post, PostTopic]] = {
    Query.later({
	  val result = ids.unwrap.map(id => (id, if (id.id % 2 == 0) "monad" else "applicative")).toMap
      latency(result, s"Many Post Topics $ids")
    })
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
fetchMulti.runA[Id]
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
fetchConcurrent.runA[Id]
```

Since we are running the fetch to `Id`, we couldn't exploit parallelism for reading from both sources
at the same time. Let's do some imports in order to be able to run fetches to a `Future`.

```tut:silent
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.concurrent.duration._
```

Let's see what happens when running the same fetch to a `Future`, note that you cannot block for a
future's result in Scala.js.

```tut:book
import fetch.implicits._

Await.result(fetchConcurrent.runA[Future], Duration.Inf)
```

As you can see, each independent request ran in its own logical thread.

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
fetchSequence.runA[Id]
```

As you can see, requests to the user data source were batched, thus fetching all the data in one round.

### Traverse

Another interesting combinator is `traverse`, which is the composition of `map` and `sequence`.

```tut:silent
val fetchTraverse: Fetch[List[User]] = List(1, 2, 3).traverse(getUser)
```

As you may have guessed, all the optimizations made by `sequence` still apply when using `traverse`.

```tut:book
fetchTraverse.runA[Id]
```


# Caching

As we have learned, Fetch caches intermediate results implicitly. You can
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
Fetch.run[Id](fetchUser, cache)
```

And as the first when using fetch syntax:

```tut:book
fetchUser.runA[Id](cache)
```

As you can see, when all the data is cached, no query to the data sources is executed since the results are available
in the cache.

```tut:silent
val fetchManyUsers: Fetch[List[User]] = List(1, 2, 3).traverse(getUser)
```

If only part of the data is cached, the cached data won't be asked for:

```tut:book
fetchManyUsers.runA[Id](cache)
```

## Replaying a fetch without querying any data source

When running a fetch, we are generally interested in its final result. However, we also have access to the cache
and information about the executed rounds once we run a fetch. Fetch's interpreter keeps its state in an environment
(implementing the `Env` trait), and we can get both the environment and result after running a fetch using `Fetch.runFetch`
instead of `Fetch.run` or `value.runF` via it's implicit syntax.

Knowing this, we can replay a fetch reusing the cache of a previous one. The replayed fetch won't have to call any of the
data sources.

```tut:book
val env = fetchManyUsers.runE[Id]

fetchManyUsers.runA[Id](env.cache)
```

## Implementing a custom cache

The default cache is implemented as an immutable in-memory map, but users are free to use their own caches when running a fetch. Your cache should implement the `DataSourceCache` trait, and after that you can pass it to Fetch's `run` methods.

There is no need for the cache to be mutable since fetch executions run in an interpreter that uses the state monad. Note that the `update` method in the `DataSourceCache` trait yields a new, updated cache.

```scala
trait DataSourceCache {
  def update[A](k: DataSourceIdentity, v: A): DataSourceCache
  def get[A](k: DataSourceIdentity): Option[A]
}
```

Let's implement a cache that forgets everything we store in it.

```tut:silent
final case class ForgetfulCache() extends DataSourceCache {
  override def get[A](k: DataSourceIdentity): Option[A] = None
  override def update[A](k: DataSourceIdentity, v: A): ForgetfulCache = this
}
```

We can now use our implementation of the cache when running a fetch.

```tut:book
val fetchSameTwice: Fetch[(User, User)] = for {
  one <- getUser(1)
  another <- getUser(1)
} yield (one, another)

fetchSameTwice.runA[Id](ForgetfulCache())
```

# Error handling

Fetch is used for reading data from remote sources and the queries we perform can and will fail at some point. What happens if we run a fetch and fails? We'll create a fetch that always fails to learn about it.

```tut:silent
val fetchError: Fetch[User] = (new Exception("Oh noes")).fetch
```

If we try to execute to `Id` the exception will be thrown.

```tut:fail
fetchError.runA[Id]
```

Since `Id` runs the fetch eagerly, the only way to recover from errors when running it is surrounding it with a `try-catch` block. We'll use Cats' `Eval` type as the target
monad which, instead of evaluating the fetch eagerly, gives us an `Eval[A]` that we can run anytime with its `.value` method.

We can use the `FetchMonadError[Eval]#attempt` to convert a fetch result into a disjuntion and avoid throwing exceptions. Fetch provides an implicit instance of `FetchMonadError[Eval]` that we can import from `fetch.unsafe.implicits._` to have it available.

```tut:silent
import fetch.unsafe.implicits._
```

Now we can convert `Eval[User]` into `Eval[Throwable Xor User]` and capture exceptions as values in the left of the disjunction.

```tut:book
import cats.Eval
import cats.data.Xor

val safeResult: Eval[Throwable Xor User] = FetchMonadError[Eval].attempt(fetchError.runA[Eval])

safeResult.value
```

And more succintly with Cats' applicative error syntax.

```tut:book
import cats.syntax.applicativeError._

fetchError.runA[Eval].attempt.value
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

Executing a pure fetch doesn't query any data source, as expected.

```tut:book
fetchPure.runA[Id]
```

### error

Errors can also be lifted to the Fetch monad via `exception.fetch`.

```tut:silent
val fetchFail: Fetch[Int] = Fetch.error(new Exception("Something went terribly wrong"))
```

Note that interpreting an errorful fetch to `Future` and blocking for its result will throw the exception.

```tut:fail
fetchFail.runA[Id]
```

### join

We can compose two independent fetches with `fetch1.join(fetch2)`.

```tut:silent
val fetchJoined: Fetch[(Post, User)] = getPost(1).join(getUser(2))
```

If the fetches are to the same data source they will be batched; if they aren't, they will be evaluated at the same time.

```tut:book
fetchJoined.runA[Id]
```

### runA

Run directly any fetch to a `Future` with `fetch1.runA`.

```tut:book
getPost(1).runA[Id]
```

### runE

Extract a fetch an get it's runtime environment `fetch1.runE`.

```tut:book
getPost(1).runE[Id]
```

### runF

Run a fetch obtaining the environment and final value `fetch1.runF`.

```tut:book
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

```tut:book
Fetch.run[Id](fetchPure)
```

### error

Errors can also be lifted to the Fetch monad via `Fetch#error`.

```tut:silent
val fetchFail: Fetch[Int] = Fetch.error(new Exception("Something went terribly wrong"))
```

Note that interpreting an errorful fetch to `Future` won't throw the exception until we block for its result it.

```tut:fail
Fetch.run[Id](fetchFail)
```

### join

We can compose two independent fetches with `Fetch#join`.

```tut:silent
val fetchJoined: Fetch[(Post, User)] = Fetch.join(getPost(1), getUser(2))
```

If the fetches are to the same data source they will be batched; if they aren't, they will be evaluated at the same time.

```tut:book
Fetch.run[Id](fetchJoined)
```

### sequence

The `Fetch#sequence` combinator turns a `List[Fetch[A]]` into a `Fetch[List[A]]`, running all the fetches concurrently
and batching when possible.

```tut:silent
val fetchSequence: Fetch[List[User]] = Fetch.sequence(List(getUser(1), getUser(2), getUser(3)))
```

Note that `Fetch#sequence` is not as general as the `sequence` method from `Traverse`, but performs the same optimizations.

```tut:book
Fetch.run[Id](fetchSequence)
```

### traverse

The `Fetch#traverse` combinator is a combination of `map` and `sequence`.

```tut:silent
val fetchTraverse: Fetch[List[User]] = Fetch.traverse(List(1, 2, 3))(getUser)
```

Note that `Fetch#traverse` is not as general as the `traverse` method from `Traverse`, but performs the same optimizations.

```tut:book
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

The `|@|` operator allows us to combine multiple independent fetches, even when they
are from different types, and apply a pure function to their results. We can use it
as a more powerful alternative to the `product` method or `Fetch#join`:

```tut:silent
import cats.syntax.cartesian._

val fetchThree: Fetch[(Post, User, Post)] = (getPost(1) |@| getUser(2) |@| getPost(2)).tupled
```

Notice how the queries to posts are batched.

```tut:book
fetchThree.runA[Id]
```

More interestingly, we can use it to apply a pure function to the results of various
fetches.

```tut:book
val fetchFriends: Fetch[String] = (getUser(1) |@| getUser(2)).map({ (one, other) =>
  s"${one.username} is friends with ${other.username}"
})

fetchFriends.runA[Id]
```

The above example is equivalent to the following using the `Fetch#join` method:

```tut:book
val fetchFriends: Fetch[String] = Fetch.join(getUser(1), getUser(2)).map({ case (one, other) =>
  s"${one.username} is friends with ${other.username}"
})

fetchFriends.runA[Id]
```

# Concurrency monads

Fetch lets you choose the concurrency monad you want for running fetches, supporting the Scala and Scala.js
standard library concurrency primitives. However not everyone is using `Future` and Fetch acknowledges it,
providing support for the most widespread concurrency monads and making it easy for users to run a fetch to a
custom type.

For supporting running a fetch to a monad `M[_]` an instance of `FetchMonadError[M]` must be available.

We'll use the following fetches for the examples. They show how we can combine independent fetches both for
batching and exploiting the concurrency of independent data.

```tut:silent
val postsByAuthor: Fetch[List[Post]] = for {
  posts <- List(1, 2).traverse(getPost)
  authors <- posts.traverse(getAuthor)
  ordered = (posts zip authors).sortBy({ case (_, author) => author.username }).map(_._1)
} yield ordered

val postTopics: Fetch[Map[PostTopic, Int]] = for {
  posts <- List(2, 3).traverse(getPost)
  topics <- posts.traverse(getPostTopic)
  countByTopic = (posts zip topics).groupBy(_._2).mapValues(_.size)
} yield countByTopic

val homePage = (postsByAuthor |@| postTopics).tupled
```

## Future

You can run a fetch into a `Future` simply by importing `fetch.implicits`. It
contains an instance of `FetchMonadError[Future]` given that you provide an implicit `ExecutionContext`.

For the sake of the examples we'll use the global `ExecutionContext`.

```tut:book
Await.result(Fetch.run[Future](homePage),  Duration.Inf)
```

## Monix Task

The [Monix](https://monix.io/) library provides an abstraction for lazy, asynchronous computations with its [Task](https://monix.io/docs/2x/eval/task.html) type.

For using `Task` as the target concurrency monad of a fetch, add the following dependency to your build file:

```scala
"com.fortysevendeg" %% "fetch-monix" % "0.2.0"
```

And do some standard imports, we'll need an Scheduler for running our tasks as well as the instance of `FetchMonadError[Task]` that `fetch-monix` provids:

```tut:silent
import monix.eval.Task
import monix.execution.Scheduler

import fetch.monixTask.implicits._
```

Note that running a fetch to a `Task` doesn't trigger execution. We can interpret a task to a `Future` with the `Task#runAsync` method. We'll use the global scheduler for now.

```tut:book
val scheduler = Scheduler.Implicits.global
val task = Fetch.run[Task](homePage)

Await.result(task.runAsync(scheduler), Duration.Inf)
```

### JVM

In the JVM, you may want to choose a [scheduler tuned for IO workloads](https://monix.io/docs/2x/execution/scheduler.html#builders-on-the-jvm) to interpret fetches.

```tut:book
val ioSched = Scheduler.io(name="io-scheduler")

Await.result(task.runAsync(ioSched), Duration.Inf)
```

## Custom types

If you want to run a fetch to a custom type `M[_]`, you need to implement the `FetchMonadError[M]` typeclass. `FetchMonadError[M]` is simply a `MonadError[M, Throwable]` from cats augmented
with a method for running a `Query[A]` in the context of the monad `M[A]`.

For ilustrating integration with an asynchronous concurrency monad we'll use the implementation of Monix Task.

### Running queries

First of all, we need to run queries in our target type. As we have learned, queries can be synchronous (simply wrapping an `Eval` from Cats) or asynchronous. Since we'll need to lift
`Eval[A]` values to `Task[A]`, let's write a function for doing so first. Note that Monix's `Task` supports the same evaluation strategies of `Eval` in Cats, so the conversion is very
direct:

```tut:silent
import cats.{Eval, Now, Later, Always}
import monix.eval.Task

def evalToTask[A](e: Eval[A]): Task[A] = e match {
  case Now(x) => Task.now(x)
  case l: Later[A]  => Task.evalOnce({ l.value })
  case a: Always[A] => Task.evalAlways({ a.value })
  case other => Task.evalOnce({ other.value })
}
```

Now that we can run synchronous queries to `Task`, we'll use `Task#create` for running asynchronous computations. Queries also have a third option: `Ap`, which delegates the applicative combination of independent queries to the target monad.

```tut:silent
import monix.execution.Cancelable
import scala.concurrent.duration._

def queryToTask[A](q: Query[A]): Task[A] = q match {
  case Sync(e) => evalToTask(e)
  case Async(action, timeout) => {
    val task: Task[A] = Task.create((scheduler, callback) => {
	  scheduler.execute(new Runnable {
        def run() = action(callback.onSuccess, callback.onError)
      })

      Cancelable.empty
    })

    timeout match {
      case finite: FiniteDuration => task.timeout(finite)
      case _                      => task
    }
  }
  case Ap(qf, qx) => Task.zip2(queryToTask(qf), queryToTask(qx)).map({ case (f, x) => f(x) })	
}
```

The asynchronous action was built using `Task#create`; it receives the used scheduler and a callback, runs
the async action in the scheduler passing the success and error versions of the callback and returns an empty
cancelable (it can not be canceled); if we encounter a finite duration timeout, we set it on the task.

The applicative action used `Task#zip2` to combine two tasks and apply the function contained in one of them
to the other. We used `Task#zip2` for expressing the independence between the two tasks, which can potentially
be evaluated in parallel.

### Writing the FetchMonadError instance

Now we're ready for implementing the FetchMonadError instance for `Task`, we need to define it as an implicit. 
Note that Cats' typeclass hierarchy is expressed with inheritance and methods from weaker typeclasses like `Functor` or `Applicative` in more powerful typeclasses like `Monad` are implemented in terms of the operations of the latter. In practice, this means that if you just implement `pure` and `flatMap` the rest of the combinators like `map` are going to be implemented in terms of them. Because of this we'll override `map` for not using `flatMap` and `product` for expressing the independence of two computations.


```tut:silent
implicit val taskFetchMonadError: FetchMonadError[Task] = new FetchMonadError[Task] {
  override def map[A, B](fa: Task[A])(f: A => B): Task[B] =
    fa.map(f)

  override def product[A, B](fa: Task[A], fb: Task[B]): Task[(A, B)] =
    Task.zip2(Task.fork(fa), Task.fork(fb)) // introduce parallelism with Task#fork

  override def pureEval[A](e: Eval[A]): Task[A] = evalToTask(e)

  def pure[A](x: A): Task[A] =
    Task.now(x)

  def handleErrorWith[A](fa: Task[A])(f: Throwable => Task[A]): Task[A] =
    fa.onErrorHandleWith(f)

  def raiseError[A](e: Throwable): Task[A] =
    Task.raiseError(e)

  def flatMap[A, B](fa: Task[A])(f: A => Task[B]): Task[B] =
    fa.flatMap(f)

  override def runQuery[A](q: Query[A]): Task[A] = queryToTask(q)
}
```

We can now import the above implicit and run a fetch to our custom type, let's give it a go:

```tut:book
val task = Fetch.run(homePage)(taskFetchMonadError)

Await.result(task.runAsync(scheduler), Duration.Inf)
```


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


