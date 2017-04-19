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
"com.47deg" %% "fetch" % "0.6.1"
```

Or, if using Scala.js:

```scala
"com.47deg" %%% "fetch" % "0.6.1"
```

[comment]: # (End Replace)

Now you'll have Fetch available in both Scala and Scala.js.




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
  def name: String
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

```scala
type UserId = Int
case class User(id: UserId, username: String)
```

We'll simulate unpredictable latency with this function.

```scala
def latency[A](result: A, msg: String) = {
  val id = Thread.currentThread.getId
  println(s"~~> [$id] $msg")
  Thread.sleep(100)
  println(s"<~~ [$id] $msg")
  result
}
```

And now we're ready to write our user data source; we'll emulate a database with an in-memory map.

```scala
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

  override def fetchOne(id: UserId): Query[Option[User]] = {
    Query.sync({
	  latency(userDatabase.get(id), s"One User $id")
    })
  }
  override def fetchMany(ids: NonEmptyList[UserId]): Query[Map[UserId, User]] = {
    Query.sync({
	  latency(userDatabase.filterKeys(ids.toList.contains), s"Many Users $ids")
    })
  }
}
```

Now that we have a data source we can write a function for fetching users
given an id, we just have to pass a `UserId` as an argument to `Fetch`.

```scala
def getUser(id: UserId): Fetch[User] = Fetch(id) // or, more explicitly: Fetch(id)(UserSource)
```

### Data sources that don't support batching

If your data source doesn't support batching, you can use the `DataSource#batchingNotSupported` method as the implementation
of `fetchMany`. Note that it will use the `fetchOne` implementation for requesting identities one at a time.

```scala
implicit object UnbatchedSource extends DataSource[Int, Int]{
  override def name = "Unbatched"

  override def fetchOne(id: Int): Query[Option[Int]] = {
    Query.sync(Option(id))
  }
  override def fetchMany(ids: NonEmptyList[Int]): Query[Map[Int, Int]] = {
    batchingNotSupported(ids)
  }
}
```

### Data sources that only support batching

If your data source only supports querying it in batches, you can implement `fetchOne` in terms of `fetchMany` using `DataSource#batchingOnly`.

```scala
implicit object OnlyBatchedSource extends DataSource[Int, Int]{
  override def name = "OnlyBatched"

  override def fetchOne(id: Int): Query[Option[Int]] =
    batchingOnly(id)

  override def fetchMany(ids: NonEmptyList[Int]): Query[Map[Int, Int]] =
    Query.sync(ids.toList.map((x) => (x, x)).toMap)
}
```

## Creating and running a fetch

We are now ready to create and run fetches. Note the distinction between Fetch creation and execution.
When we are creating and combining `Fetch` values, we are just constructing a recipe of our data
dependencies.

```scala
val fetchUser: Fetch[User] = getUser(1)
```

A `Fetch` is just a value, and in order to be able to get its value we need to run it to a monad first. The
target monad `M[_]` must be able to lift a `Query[A]` to `M[A]`, evaluating the query in the monad's context.

We'll run `fetchUser` using `Id` as our target monad, so let's do some imports first. Note that interpreting
a fetch to a non-concurrency monad like `Id` or `Eval` is only recommended for trying things out in a Scala
console, that's why for using them you need to import `fetch.unsafe.implicits`.

```scala
import cats.Id
import fetch.unsafe.implicits._
import fetch.syntax._
```

Note that running a fetch to non-concurrency monads like `Id` or `Eval` is not supported in Scala.js.
In real-life scenarios you'll want to run your fetches to `Future` or a `Task` type provided by a library like
[Monix](https://monix.io/) or [fs2](https://github.com/functional-streams-for-scala/fs2). Monix's Task is already
supported in the `fetch-monix` project, but fs2's Task is not at the moment.

We can now run the fetch and see its result:

```scala
fetchUser.runA[Id]
// ~~> [157] One User 1
// <~~ [157] One User 1
// res3: cats.Id[User] = User(1,@one)
```

### Sequencing

When we have two fetches that depend on each other, we can use `flatMap` to combine them. The most straightforward way is to use a for comprehension:

```scala
val fetchTwoUsers: Fetch[(User, User)] = for {
  aUser <- getUser(1)
  anotherUser <- getUser(aUser.id + 1)
} yield (aUser, anotherUser)
```

When composing fetches with `flatMap` we are telling Fetch that the second one depends on the previous one, so it isn't able to make any optimizations. When running the above fetch, we will query the user data source in two rounds: one for the user with id 1 and another for the user with id 2.

```scala
fetchTwoUsers.runA[Id]
// ~~> [157] One User 1
// <~~ [157] One User 1
// ~~> [157] One User 2
// <~~ [157] One User 2
// res4: cats.Id[(User, User)] = (User(1,@one),User(2,@two))
```

### Batching

If we combine two independent requests to the same data source, Fetch will
automatically batch them together into a single request. Applicative operations like the product of two fetches
help us tell the library that those fetches are independent, and thus can be batched if they use the same data source:

```scala
import cats.syntax.cartesian._

val fetchProduct: Fetch[(User, User)] = getUser(1).product(getUser(2))
```

Note how both ids (1 and 2) are requested in a single query to the data source when executing the fetch.

```scala
fetchProduct.runA[Id]
// ~~> [157] Many Users NonEmptyList(2, 1)
// <~~ [157] Many Users NonEmptyList(2, 1)
// res6: cats.Id[(User, User)] = (User(1,@one),User(2,@two))
```

### Deduplication

If two independent requests ask for the same identity, Fetch will detect it and deduplicate the id.

```scala
val fetchDuped: Fetch[(User, User)] = getUser(1).product(getUser(1))
```

Note that when running the fetch, the identity 1 is only requested once even when it is needed by both fetches.

```scala
fetchDuped.runA[Id]
// ~~> [157] One User 1
// <~~ [157] One User 1
// res7: cats.Id[(User, User)] = (User(1,@one),User(1,@one))
```

### Caching

During the execution of a fetch, previously requested results are implicitly cached. This allows us to write
fetches in a very modular way, asking for all the data they need as if it
was in memory; furthermore, it also avoids re-fetching an identity that may have changed
during the course of a fetch execution, which can lead to inconsistencies in the data.

```scala
val fetchCached: Fetch[(User, User)] = for {
  aUser <- getUser(1)
  anotherUser <- getUser(1)
} yield (aUser, anotherUser)
```

The above fetch asks for the same identity multiple times. Let's see what happens when executing it.

```scala
fetchCached.runA[Id]
// ~~> [157] One User 1
// <~~ [157] One User 1
// res8: cats.Id[(User, User)] = (User(1,@one),User(1,@one))
```

As you can see, the `User` with id 1 was fetched only once in a single round-trip. The next
time it was needed we used the cached versions, thus avoiding another request to the user data
source.


## Queries

Queries are a way of separating the computation required to read a piece of data from the context in
which is run. Let's look at the various ways we have of constructing queries.

### Synchronous

A query can be synchronous, and we may want to evaluate it when `fetchOne` and `fetchMany`
are called. We can do so with `Query#sync`:

```scala
Query.sync(42)
// res9: fetch.Query[Int] = Sync(cats.Later@3b970e91)
```

You can also construct lazy queries that can evaluate synchronously passing a thunk to `Query#sync`:

```scala
Query.sync({ println("Computing 42"); 42 })
// res10: fetch.Query[Int] = Sync(cats.Later@6a223a84)
```

Synchronous queries simply wrap a Cats' `Eval` instance, which captures the notion of a lazy synchronous
computation. You can lift an `Eval[A]` into a `Query[A]` too:

```scala
import cats.Eval
// import cats.Eval

Query.eval(Eval.always({ println("Computing 42"); 42 }))
// res11: fetch.Query[Int] = Sync(cats.Always@777a9736)
```

### Asynchronous

Asynchronous queries are constructed passing a function that accepts a callback (`A => Unit`) and an errback
(`Throwable => Unit`) and performs the asynchronous computation. Note that you must ensure that either the
callback or the errback are called.

```scala
Query.async((ok: (Int => Unit), fail) => {
  Thread.sleep(100)
  ok(42)
})
// res12: fetch.Query[Int] = Async($$Lambda$2127/425366030@43c04e6d,Duration.Inf)
```

## Combining data from multiple sources

Now that we know about some of the optimizations that Fetch can perform to read data efficiently,
let's look at how we can combine more than one data source.


Imagine that we are rendering a blog and have the following types for posts:

```scala
type PostId = Int
case class Post(id: PostId, author: UserId, content: String)
```

As you can see, every `Post` has an author, but it refers to the author by its id. We'll implement a data source for retrieving a post given a post id.

```scala
val postDatabase: Map[PostId, Post] = Map(
  1 -> Post(1, 2, "An article"),
  2 -> Post(2, 3, "Another article"),
  3 -> Post(3, 4, "Yet another article")
)

implicit object PostSource extends DataSource[PostId, Post]{
  override def name = "Post"

  override def fetchOne(id: PostId): Query[Option[Post]] = {
    Query.sync({
	  latency(postDatabase.get(id), s"One Post $id")
    })
  }
  override def fetchMany(ids: NonEmptyList[PostId]): Query[Map[PostId, Post]] = {
    Query.sync({
	  latency(postDatabase.filterKeys(ids.toList.contains), s"Many Posts $ids")
    })
  }
}

def getPost(id: PostId): Fetch[Post] = Fetch(id)
```

We can also implement a function for fetching a post's author given a post:

```scala
def getAuthor(p: Post): Fetch[User] = Fetch(p.author)
```

Apart from posts, we are going to add another data source: one for post topics.

```scala
type PostTopic = String
```

We'll implement a data source for retrieving a post topic given a post id.

```scala
implicit object PostTopicSource extends DataSource[Post, PostTopic]{
  override def name = "Post topic"

  override def fetchOne(id: Post): Query[Option[PostTopic]] = {
    Query.sync({
      val topic = if (id.id % 2 == 0) "monad" else "applicative"
      latency(Option(topic), s"One Post Topic $id")
    })
  }
  override def fetchMany(ids: NonEmptyList[Post]): Query[Map[Post, PostTopic]] = {
    Query.sync({
	  val result = ids.toList.map(id => (id, if (id.id % 2 == 0) "monad" else "applicative")).toMap
      latency(result, s"Many Post Topics $ids")
    })
  }
}

def getPostTopic(post: Post): Fetch[PostTopic] = Fetch(post)
```

Now that we have multiple sources let's mix them in the same fetch.

```scala
val fetchMulti: Fetch[(Post, PostTopic)] = for {
  post <- getPost(1)
  topic <- getPostTopic(post)
} yield (post, topic)
```

We can now run the previous fetch, querying the posts data source first and the user data source afterwards.

```scala
fetchMulti.runA[Id]
// ~~> [157] One Post 1
// <~~ [157] One Post 1
// ~~> [157] One Post Topic Post(1,2,An article)
// <~~ [157] One Post Topic Post(1,2,An article)
// res16: cats.Id[(Post, PostTopic)] = (Post(1,2,An article),applicative)
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

```scala
val fetchConcurrent: Fetch[(Post, User)] = getPost(1).product(getUser(2))
```

The above example combines data from two different sources, and the library knows they are independent.

```scala
fetchConcurrent.runA[Id]
// ~~> [157] One User 2
// <~~ [157] One User 2
// ~~> [157] One Post 1
// <~~ [157] One Post 1
// res17: cats.Id[(Post, User)] = (Post(1,2,An article),User(2,@two))
```

Since we are running the fetch to `Id`, we couldn't exploit parallelism for reading from both sources
at the same time. Let's do some imports in order to be able to run fetches to a `Future`.

```scala
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.concurrent.duration._
```

Let's see what happens when running the same fetch to a `Future`, note that you cannot block for a
future's result in Scala.js.

```scala
import fetch.implicits._
// import fetch.implicits._

Await.result(fetchConcurrent.runA[Future], Duration.Inf)
// ~~> [162] One User 2
// ~~> [158] One Post 1
// <~~ [162] One User 2
// <~~ [158] One Post 1
// res18: (Post, User) = (Post(1,2,An article),User(2,@two))
```

As you can see, each independent request ran in its own logical thread.

## Combinators

Besides `flatMap` for sequencing fetches and `product` for running them concurrently, Fetch provides a number of
other combinators.

### Sequence

Whenever we have a list of fetches of the same type and want to run them concurrently, we can use the `sequence`
combinator. It takes a `List[Fetch[A]]` and gives you back a `Fetch[List[A]]`, batching the fetches to the same
data source and running fetches to different sources in parallel. Note that the `sequence` combinator is more general and works not only on lists but on any type that has a [Traverse](http://typelevel.org/cats/tut/traverse.html) instance.

```scala
import cats.instances.list._
import cats.syntax.traverse._

val fetchSequence: Fetch[List[User]] = List(getUser(1), getUser(2), getUser(3)).sequence
```

Since `sequence` uses applicative operations internally, the library is able to perform optimizations across all the sequenced fetches.

```scala
fetchSequence.runA[Id]
// ~~> [157] Many Users NonEmptyList(1, 2, 3)
// <~~ [157] Many Users NonEmptyList(1, 2, 3)
// res20: cats.Id[List[User]] = List(User(1,@one), User(2,@two), User(3,@three))
```

As you can see, requests to the user data source were batched, thus fetching all the data in one round.

### Traverse

Another interesting combinator is `traverse`, which is the composition of `map` and `sequence`.

```scala
val fetchTraverse: Fetch[List[User]] = List(1, 2, 3).traverse(getUser)
```

As you may have guessed, all the optimizations made by `sequence` still apply when using `traverse`.

```scala
fetchTraverse.runA[Id]
// ~~> [157] Many Users NonEmptyList(1, 2, 3)
// <~~ [157] Many Users NonEmptyList(1, 2, 3)
// res21: cats.Id[List[User]] = List(User(1,@one), User(2,@two), User(3,@three))
```


# Caching

As we have learned, Fetch caches intermediate results implicitly. You can
provide a prepopulated cache for running a fetch, replay a fetch with the cache of a previous
one, and even implement a custom cache.

## Prepopulating a cache

We'll be using the default in-memory cache, prepopulated with some data. The cache key of an identity
is calculated with the `DataSource`'s `identity` method.

```scala
val cache = InMemoryCache(UserSource.identity(1) -> User(1, "@dialelo"))
// cache: fetch.InMemoryCache = InMemoryCache(Map((User,1) -> User(1,@dialelo)))
```

We can pass a cache as the second argument when running a fetch with `Fetch.run`.

```scala
Fetch.run[Id](fetchUser, cache)
// res22: cats.Id[User] = User(1,@dialelo)
```

And as the first when using fetch syntax:

```scala
fetchUser.runA[Id](cache)
// res23: cats.Id[User] = User(1,@dialelo)
```

As you can see, when all the data is cached, no query to the data sources is executed since the results are available
in the cache.

```scala
val fetchManyUsers: Fetch[List[User]] = List(1, 2, 3).traverse(getUser)
```

If only part of the data is cached, the cached data won't be asked for:

```scala
fetchManyUsers.runA[Id](cache)
// ~~> [157] Many Users NonEmptyList(2, 3)
// <~~ [157] Many Users NonEmptyList(2, 3)
// res24: cats.Id[List[User]] = List(User(1,@dialelo), User(2,@two), User(3,@three))
```

## Replaying a fetch without querying any data source

When running a fetch, we are generally interested in its final result. However, we also have access to the cache
and information about the executed rounds once we run a fetch. Fetch's interpreter keeps its state in an environment
(implementing the `Env` trait), and we can get both the environment and result after running a fetch using `Fetch.runFetch`
instead of `Fetch.run` or `value.runF` via it's implicit syntax.

Knowing this, we can replay a fetch reusing the cache of a previous one. The replayed fetch won't have to call any of the
data sources.

```scala
val env = fetchManyUsers.runE[Id]
// ~~> [157] Many Users NonEmptyList(1, 2, 3)
// <~~ [157] Many Users NonEmptyList(1, 2, 3)
// env: cats.Id[fetch.FetchEnv] = FetchEnv(InMemoryCache(Map((User,1) -> User(1,@one), (User,2) -> User(2,@two), (User,3) -> User(3,@three))),Queue(Round(InMemoryCache(Map()),Concurrent(NonEmptyList(FetchMany(NonEmptyList(1, 2, 3),User))),NonEmptyList(Map(1 -> User(1,@one), 2 -> User(2,@two), 3 -> User(3,@three))),87432969231826,87433072134120)))

fetchManyUsers.runA[Id](env.cache)
// res25: cats.Id[List[User]] = List(User(1,@one), User(2,@two), User(3,@three))
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

```scala
final case class ForgetfulCache() extends DataSourceCache {
  override def get[A](k: DataSourceIdentity): Option[A] = None
  override def update[A](k: DataSourceIdentity, v: A): ForgetfulCache = this
}
```

We can now use our implementation of the cache when running a fetch.

```scala
val fetchSameTwice: Fetch[(User, User)] = for {
  one <- getUser(1)
  another <- getUser(1)
} yield (one, another)
// fetchSameTwice: fetch.Fetch[(User, User)] = Free(...)

fetchSameTwice.runA[Id](ForgetfulCache())
// ~~> [157] One User 1
// <~~ [157] One User 1
// ~~> [157] One User 1
// <~~ [157] One User 1
// res26: cats.Id[(User, User)] = (User(1,@one),User(1,@one))
```

# Batching

As we have learned, Fetch performs batched requests whenever it can. It also exposes a couple knobs
for tweaking the maximum batch size and whether multiple batches are run in parallel or sequentially.

## Maximum batch size

When implementing a `DataSource`, there is a method we can override called `maxBatchSize`. When implementing it
we can specify the maximum size of the batched requests to this data source, let's try it out:

```scala
implicit object BatchedUserSource extends DataSource[UserId, User]{
  override def name = "BatchedUser"

  override def maxBatchSize: Option[Int] = Some(2)

  override def fetchOne(id: UserId): Query[Option[User]] = {
    Query.sync({
	  latency(userDatabase.get(id), s"One User $id")
    })
  }
  override def fetchMany(ids: NonEmptyList[UserId]): Query[Map[UserId, User]] = {
    Query.sync({
	  latency(userDatabase.filterKeys(ids.toList.contains), s"Many Users $ids")
    })
  }
}

def getBatchedUser(id: Int): Fetch[User] = Fetch(id)(BatchedUserSource)
```

We have defined the maximum batch size to be 2, let's see what happens when running a fetch that needs more
than two users:


```scala
val fetchManyBatchedUsers: Fetch[List[User]] = List(1, 2, 3, 4).traverse(getBatchedUser)
// fetchManyBatchedUsers: fetch.Fetch[List[User]] = Free(...)

fetchManyBatchedUsers.runA[Id]
// ~~> [157] Many Users NonEmptyList(1, 2)
// <~~ [157] Many Users NonEmptyList(1, 2)
// ~~> [157] Many Users NonEmptyList(3, 4)
// <~~ [157] Many Users NonEmptyList(3, 4)
// res28: cats.Id[List[User]] = List(User(1,@one), User(2,@two), User(3,@three), User(4,@four))
```

## Batch execution strategy

In the presence of multiple concurrent batches, we can choose between a sequential or parallel execution strategy. By default they will be run in parallel, but you can tweak it by overriding `DataSource#batchExection`.

```scala
implicit object SequentialUserSource extends DataSource[UserId, User]{
  override def name = "SequentialUser"

  override def maxBatchSize: Option[Int] = Some(2)

  override def batchExecution: ExecutionType = Sequential

  override def fetchOne(id: UserId): Query[Option[User]] = {
    Query.sync({
	  latency(userDatabase.get(id), s"One User $id")
    })
  }
  override def fetchMany(ids: NonEmptyList[UserId]): Query[Map[UserId, User]] = {
    Query.sync({
	  latency(userDatabase.filterKeys(ids.toList.contains), s"Many Users $ids")
    })
  }
}

def getSequentialUser(id: Int): Fetch[User] = Fetch(id)(SequentialUserSource)
```

We have defined the maximum batch size to be 2 and the batch execution to be sequential, let's see what happens when running a fetch that needs more than one batch:


```scala
val fetchManySeqBatchedUsers: Fetch[List[User]] = List(1, 2, 3, 4).traverse(getSequentialUser)
// fetchManySeqBatchedUsers: fetch.Fetch[List[User]] = Free(...)

fetchManySeqBatchedUsers.runA[Id]
// ~~> [157] Many Users NonEmptyList(1, 2)
// <~~ [157] Many Users NonEmptyList(1, 2)
// ~~> [157] Many Users NonEmptyList(3, 4)
// <~~ [157] Many Users NonEmptyList(3, 4)
// res30: cats.Id[List[User]] = List(User(1,@one), User(2,@two), User(3,@three), User(4,@four))
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

```scala
val fetchException: Fetch[User] = (new Exception("Oh noes")).fetch
```

If we try to execute to `Id` the exception will be thrown wrapped in a `FetchException`.

```scala
scala> fetchException.runA[Id]
fetch.UnhandledException: java.lang.Exception: Oh noes
  at fetch.FetchInterpreters$$anon$1.$anonfun$apply$1(interpreters.scala:51)
  at fetch.FetchInterpreters$$anon$1$$Lambda$2002/1832220525.apply(Unknown Source)
  at scala.Function1.$anonfun$andThen$1(Function1.scala:52)
  at scala.Function1$$Lambda$1999/1592720715.apply(Unknown Source)
  at cats.data.StateT.$anonfun$run$1(StateT.scala:58)
  at cats.data.StateT$$Lambda$1990/1456370760.apply(Unknown Source)
  at fetch.unsafe.implicits$$anon$2.flatMap(unsafeImplicits.scala:122)
  at cats.data.StateT.run(StateT.scala:58)
  at cats.data.StateTMonad.$anonfun$tailRecM$2(StateT.scala:282)
  at cats.data.StateTMonad$$Lambda$1991/1379697920.apply(Unknown Source)
  at cats.package$$anon$1.tailRecM(package.scala:36)
  at fetch.unsafe.implicits$$anon$2.tailRecM(unsafeImplicits.scala:106)
  at cats.data.StateTMonad.$anonfun$tailRecM$1(StateT.scala:281)
  at cats.data.StateTMonad$$Lambda$1989/1200822908.apply(Unknown Source)
  at scala.Function1.$anonfun$andThen$1(Function1.scala:52)
  at scala.Function1$$Lambda$1999/1592720715.apply(Unknown Source)
  at cats.data.StateT.$anonfun$run$1(StateT.scala:58)
  at cats.data.StateT$$Lambda$1990/1456370760.apply(Unknown Source)
  at fetch.unsafe.implicits$$anon$2.flatMap(unsafeImplicits.scala:122)
  at cats.data.StateT.run(StateT.scala:58)
  at cats.data.StateTMonad.$anonfun$tailRecM$2(StateT.scala:282)
  at cats.data.StateTMonad$$Lambda$1991/1379697920.apply(Unknown Source)
  at cats.package$$anon$1.tailRecM(package.scala:36)
  at fetch.unsafe.implicits$$anon$2.tailRecM(unsafeImplicits.scala:106)
  at cats.data.StateTMonad.$anonfun$tailRecM$1(StateT.scala:281)
  at cats.data.StateTMonad$$Lambda$1989/1200822908.apply(Unknown Source)
  at scala.Function1.$anonfun$andThen$1(Function1.scala:52)
  at scala.Function1$$Lambda$1999/1592720715.apply(Unknown Source)
  at cats.data.StateT.$anonfun$run$1(StateT.scala:58)
  at cats.data.StateT$$Lambda$1990/1456370760.apply(Unknown Source)
  at fetch.unsafe.implicits$$anon$2.flatMap(unsafeImplicits.scala:122)
  at cats.data.StateT.run(StateT.scala:58)
  at cats.data.StateTMonad.$anonfun$tailRecM$2(StateT.scala:282)
  at cats.data.StateTMonad$$Lambda$1991/1379697920.apply(Unknown Source)
  at cats.package$$anon$1.tailRecM(package.scala:36)
  at fetch.unsafe.implicits$$anon$2.tailRecM(unsafeImplicits.scala:106)
  at cats.data.StateTMonad.$anonfun$tailRecM$1(StateT.scala:281)
  at cats.data.StateTMonad$$Lambda$1989/1200822908.apply(Unknown Source)
  at cats.data.StateT.$anonfun$run$1(StateT.scala:58)
  at cats.data.StateT$$Lambda$1990/1456370760.apply(Unknown Source)
  at fetch.unsafe.implicits$$anon$2.flatMap(unsafeImplicits.scala:122)
  at cats.data.StateT.run(StateT.scala:58)
  at cats.data.StateT.runA(StateT.scala:70)
  at fetch.package$Fetch$FetchRunnerA.apply(fetch.scala:242)
  at fetch.syntax$FetchSyntax$.runA$extension0(syntax.scala:48)
  ... 979 elided
Caused by: java.lang.Exception: Oh noes
```

Since `Id` runs the fetch eagerly, the only way to recover from errors when running it is surrounding it with a `try-catch` block. We'll use Cats' `Eval` type as the target
monad which, instead of evaluating the fetch eagerly, gives us an `Eval[A]` that we can run anytime with its `.value` method.

We can use the `FetchMonadError[Eval]#attempt` to convert a fetch result into a disjuntion and avoid throwing exceptions. Fetch provides an implicit instance of `FetchMonadError[Eval]` that we can import from `fetch.unsafe.implicits._` to have it available.

```scala
import fetch.unsafe.implicits._
```

Now we can convert `Eval[User]` into `Eval[Either[FetchException, User]` and capture exceptions as values in the left of the disjunction.

```scala
import cats.Eval
// import cats.Eval

val safeResult: Eval[Either[FetchException, User]] = FetchMonadError[Eval].attempt(fetchException.runA[Eval])
// safeResult: cats.Eval[Either[fetch.FetchException,User]] = cats.Later@709ae4a7

safeResult.value
// res32: Either[fetch.FetchException,User] = Left(fetch.UnhandledException)
```

And more succintly with Cats' applicative error syntax.

```scala
import cats.syntax.applicativeError._
// import cats.syntax.applicativeError._

import fetch.unsafe.implicits._
// import fetch.unsafe.implicits._

fetchException.runA[Eval].attempt.value
// res33: Either[fetch.FetchException,User] = Left(fetch.UnhandledException)
```

### Debugging exceptions

Using fetch's debugging facilities, we can visualize a failed fetch's execution up until the point where it failed. Let's create
a fetch that fails after a couple rounds to see it in action:

```scala
val failingFetch: Fetch[String] = for {
  a <- getUser(1)
  b <- getUser(2)
  c <- fetchException
} yield s"${a.username} loves ${b.username}"

val result: Eval[Either[FetchException, String]] = FetchMonadError[Eval].attempt(failingFetch.runA[Eval])
```

Now let's use the `fetch.debug.describe` function for describing the error if we find one:

```scala
import fetch.debug.describe
// import fetch.debug.describe

val value: Either[FetchException, String] = result.value
// ~~> [157] One User 1
// <~~ [157] One User 1
// ~~> [157] One User 2
// <~~ [157] One User 2
// value: Either[fetch.FetchException,String] = Left(fetch.UnhandledException)

println(value.fold(describe, identity _))
// [Error] Unhandled `java.lang.Exception`: 'Oh noes', fetch interrupted after 2 rounds
// Fetch execution took 0.205078 seconds
//   
//     [Fetch one] From `User` with id 1 took 0.000102 seconds
//     [Fetch one] From `User` with id 2 took 0.000102 seconds
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

```scala
import cats.syntax.either._
import fetch.debug.describe

val missingUser = getUser(5)

val result: Eval[Either[FetchException, User]] = missingUser.runA[Eval].attempt
```

And now we can execute the fetch and describe its execution:

```scala
val value: Either[FetchException, User] = result.value
// ~~> [157] One User 5
// <~~ [157] One User 5
// value: Either[fetch.FetchException,User] = Left(fetch.NotFound)

println(value.fold(describe, _.toString))
// [Error] Identity not found: 5 in `User`, fetch interrupted after 0 rounds
// 
```

As you can see in the output, the identity `5` for the user source was not found, thus the fetch failed without executing any rounds.
`NotFound` also allows you to access the fetch request that was in progress when the error happened and the environment of the fetch.

```scala
value match {
  case Left(nf @ NotFound(_, _)) => {
    println("Request " + nf.request)
    println("Environment " + nf.env)
  }
  case _ =>
}
// Request FetchOne(5,User)
// Environment FetchEnv(InMemoryCache(Map()),Queue())
```

### Multiple requests

When multiple requests to the same data source are batched and/or multiple requests are performed at the same time, is possible that more than one identity was missing. There is another error case for such situations: `MissingIdentities`, which contains a mapping from data source names to the list of missing identities.

```scala
import fetch.debug.describe
// import fetch.debug.describe

val missingUsers = List(3, 4, 5, 6).traverse(getUser)
// missingUsers: fetch.Fetch[List[User]] = Free(...)

val result: Eval[Either[FetchException, List[User]]] = missingUsers.runA[Eval].attempt
// result: cats.Eval[Either[fetch.FetchException,List[User]]] = cats.Later@1305566a
```

And now we can execute the fetch and describe its execution:

```scala
val value: Either[FetchException, List[User]] = result.value
// ~~> [157] Many Users NonEmptyList(3, 4, 5, 6)
// <~~ [157] Many Users NonEmptyList(3, 4, 5, 6)
// value: Either[fetch.FetchException,List[User]] = Left(fetch.MissingIdentities)

println(value.fold(describe, _.toString))
// [Error] Missing identities, fetch interrupted after 0 rounds
// 
//   `User` missing identities List(5, 6)
// 
```

The `.missing` attribute will give us the mapping from data source name to missing identities, and `.env` will give us the environment so we can track the execution of the fetch.

```scala
value match {
  case Left(mi @ MissingIdentities(_, _)) => {
    println("Missing identities " + mi.missing)
    println("Environment " + mi.env)
  }
  case _ =>
}
// Missing identities Map(User -> List(5, 6))
// Environment FetchEnv(InMemoryCache(Map()),Queue())
```

## Your own errors

# Syntax

## Implicit syntax

Fetch provides implicit syntax to lift any value to the context of a `Fetch` in addition to the most common used
combinators active within `Fetch` instances.

### pure

Plain values can be lifted to the Fetch monad with `value.fetch`:

```scala
val fetchPure: Fetch[Int] = 42.fetch
```

Executing a pure fetch doesn't query any data source, as expected.

```scala
fetchPure.runA[Id]
// res42: cats.Id[Int] = 42
```

### error

Errors can also be lifted to the Fetch monad via `exception.fetch`.

```scala
val fetchFail: Fetch[Int] = new Exception("Something went terribly wrong").fetch
```

Note that interpreting an errorful fetch to `Id` will throw the exception.

```scala
scala> fetchFail.runA[Id]
fetch.UnhandledException: java.lang.Exception: Something went terribly wrong
  at fetch.FetchInterpreters$$anon$1.$anonfun$apply$1(interpreters.scala:51)
  at fetch.FetchInterpreters$$anon$1$$Lambda$2002/1832220525.apply(Unknown Source)
  at scala.Function1.$anonfun$andThen$1(Function1.scala:52)
  at scala.Function1$$Lambda$1999/1592720715.apply(Unknown Source)
  at cats.data.StateT.$anonfun$run$1(StateT.scala:58)
  at cats.data.StateT$$Lambda$1990/1456370760.apply(Unknown Source)
  at fetch.unsafe.implicits$$anon$2.flatMap(unsafeImplicits.scala:122)
  at cats.data.StateT.run(StateT.scala:58)
  at cats.data.StateTMonad.$anonfun$tailRecM$2(StateT.scala:282)
  at cats.data.StateTMonad$$Lambda$1991/1379697920.apply(Unknown Source)
  at cats.package$$anon$1.tailRecM(package.scala:36)
  at fetch.unsafe.implicits$$anon$2.tailRecM(unsafeImplicits.scala:106)
  at cats.data.StateTMonad.$anonfun$tailRecM$1(StateT.scala:281)
  at cats.data.StateTMonad$$Lambda$1989/1200822908.apply(Unknown Source)
  at scala.Function1.$anonfun$andThen$1(Function1.scala:52)
  at scala.Function1$$Lambda$1999/1592720715.apply(Unknown Source)
  at cats.data.StateT.$anonfun$run$1(StateT.scala:58)
  at cats.data.StateT$$Lambda$1990/1456370760.apply(Unknown Source)
  at fetch.unsafe.implicits$$anon$2.flatMap(unsafeImplicits.scala:122)
  at cats.data.StateT.run(StateT.scala:58)
  at cats.data.StateTMonad.$anonfun$tailRecM$2(StateT.scala:282)
  at cats.data.StateTMonad$$Lambda$1991/1379697920.apply(Unknown Source)
  at cats.package$$anon$1.tailRecM(package.scala:36)
  at fetch.unsafe.implicits$$anon$2.tailRecM(unsafeImplicits.scala:106)
  at cats.data.StateTMonad.$anonfun$tailRecM$1(StateT.scala:281)
  at cats.data.StateTMonad$$Lambda$1989/1200822908.apply(Unknown Source)
  at scala.Function1.$anonfun$andThen$1(Function1.scala:52)
  at scala.Function1$$Lambda$1999/1592720715.apply(Unknown Source)
  at cats.data.StateT.$anonfun$run$1(StateT.scala:58)
  at cats.data.StateT$$Lambda$1990/1456370760.apply(Unknown Source)
  at fetch.unsafe.implicits$$anon$2.flatMap(unsafeImplicits.scala:122)
  at cats.data.StateT.run(StateT.scala:58)
  at cats.data.StateTMonad.$anonfun$tailRecM$2(StateT.scala:282)
  at cats.data.StateTMonad$$Lambda$1991/1379697920.apply(Unknown Source)
  at cats.package$$anon$1.tailRecM(package.scala:36)
  at fetch.unsafe.implicits$$anon$2.tailRecM(unsafeImplicits.scala:106)
  at cats.data.StateTMonad.$anonfun$tailRecM$1(StateT.scala:281)
  at cats.data.StateTMonad$$Lambda$1989/1200822908.apply(Unknown Source)
  at cats.data.StateT.$anonfun$run$1(StateT.scala:58)
  at cats.data.StateT$$Lambda$1990/1456370760.apply(Unknown Source)
  at fetch.unsafe.implicits$$anon$2.flatMap(unsafeImplicits.scala:122)
  at cats.data.StateT.run(StateT.scala:58)
  at cats.data.StateT.runA(StateT.scala:70)
  at fetch.package$Fetch$FetchRunnerA.apply(fetch.scala:242)
  at fetch.syntax$FetchSyntax$.runA$extension0(syntax.scala:48)
  ... 979 elided
Caused by: java.lang.Exception: Something went terribly wrong
```

### join

We can compose two independent fetches with `fetch1.join(fetch2)`.

```scala
val fetchJoined: Fetch[(Post, User)] = getPost(1).join(getUser(2))
```

If the fetches are to the same data source they will be batched; if they aren't, they will be evaluated at the same time.

```scala
fetchJoined.runA[Id]
// ~~> [157] One User 2
// <~~ [157] One User 2
// ~~> [157] One Post 1
// <~~ [157] One Post 1
// res44: cats.Id[(Post, User)] = (Post(1,2,An article),User(2,@two))
```

### runA

Run directly any fetch with `fetch1.runA`.

```scala
getPost(1).runA[Id]
// ~~> [157] One Post 1
// <~~ [157] One Post 1
// res45: cats.Id[Post] = Post(1,2,An article)
```

### runE

Run a fetch an get it's runtime environment `fetch1.runE`.

```scala
getPost(1).runE[Id]
// ~~> [157] One Post 1
// <~~ [157] One Post 1
// res46: cats.Id[fetch.FetchEnv] = FetchEnv(InMemoryCache(Map((Post,1) -> Post(1,2,An article))),Queue(Round(InMemoryCache(Map()),FetchOne(1,Post),Post(1,2,An article),87445564637919,87445666588145)))
```

### runF

Run a fetch obtaining the environment and final value `fetch1.runF`.

```scala
getPost(1).runF[Id]
// ~~> [157] One Post 1
// <~~ [157] One Post 1
// res47: cats.Id[(fetch.FetchEnv, Post)] = (FetchEnv(InMemoryCache(Map((Post,1) -> Post(1,2,An article))),Queue(Round(InMemoryCache(Map()),FetchOne(1,Post),Post(1,2,An article),87445953273841,87446055148090))),Post(1,2,An article))
```

## Companion object

We've been using Cats' syntax and `fetch.syntax` throughout the examples since it's more concise and general than the
methods in the `Fetch` companion object. However, you can use the methods in the companion object
directly.

Note that using cats syntax gives you a plethora of combinators, much richer that what the companion object provides.

### pure

Plain values can be lifted to the Fetch monad with `Fetch#pure`:

```scala
val fetchPure: Fetch[Int] = Fetch.pure(42)
```

Executing a pure fetch doesn't query any data source, as expected.

```scala
Fetch.run[Id](fetchPure)
// res48: cats.Id[Int] = 42
```

### error

Errors can also be lifted to the Fetch monad via `Fetch#error`.

```scala
val fetchFail: Fetch[Int] = Fetch.error(new Exception("Something went terribly wrong"))
```

Note that interpreting an errorful fetch to `Id` will throw the exception.

```scala
scala> Fetch.run[Id](fetchFail)
fetch.UnhandledException: java.lang.Exception: Something went terribly wrong
  at fetch.FetchInterpreters$$anon$1.$anonfun$apply$1(interpreters.scala:51)
  at fetch.FetchInterpreters$$anon$1$$Lambda$2002/1832220525.apply(Unknown Source)
  at scala.Function1.$anonfun$andThen$1(Function1.scala:52)
  at scala.Function1$$Lambda$1999/1592720715.apply(Unknown Source)
  at cats.data.StateT.$anonfun$run$1(StateT.scala:58)
  at cats.data.StateT$$Lambda$1990/1456370760.apply(Unknown Source)
  at fetch.unsafe.implicits$$anon$2.flatMap(unsafeImplicits.scala:122)
  at cats.data.StateT.run(StateT.scala:58)
  at cats.data.StateTMonad.$anonfun$tailRecM$2(StateT.scala:282)
  at cats.data.StateTMonad$$Lambda$1991/1379697920.apply(Unknown Source)
  at cats.package$$anon$1.tailRecM(package.scala:36)
  at fetch.unsafe.implicits$$anon$2.tailRecM(unsafeImplicits.scala:106)
  at cats.data.StateTMonad.$anonfun$tailRecM$1(StateT.scala:281)
  at cats.data.StateTMonad$$Lambda$1989/1200822908.apply(Unknown Source)
  at scala.Function1.$anonfun$andThen$1(Function1.scala:52)
  at scala.Function1$$Lambda$1999/1592720715.apply(Unknown Source)
  at cats.data.StateT.$anonfun$run$1(StateT.scala:58)
  at cats.data.StateT$$Lambda$1990/1456370760.apply(Unknown Source)
  at fetch.unsafe.implicits$$anon$2.flatMap(unsafeImplicits.scala:122)
  at cats.data.StateT.run(StateT.scala:58)
  at cats.data.StateTMonad.$anonfun$tailRecM$2(StateT.scala:282)
  at cats.data.StateTMonad$$Lambda$1991/1379697920.apply(Unknown Source)
  at cats.package$$anon$1.tailRecM(package.scala:36)
  at fetch.unsafe.implicits$$anon$2.tailRecM(unsafeImplicits.scala:106)
  at cats.data.StateTMonad.$anonfun$tailRecM$1(StateT.scala:281)
  at cats.data.StateTMonad$$Lambda$1989/1200822908.apply(Unknown Source)
  at scala.Function1.$anonfun$andThen$1(Function1.scala:52)
  at scala.Function1$$Lambda$1999/1592720715.apply(Unknown Source)
  at cats.data.StateT.$anonfun$run$1(StateT.scala:58)
  at cats.data.StateT$$Lambda$1990/1456370760.apply(Unknown Source)
  at fetch.unsafe.implicits$$anon$2.flatMap(unsafeImplicits.scala:122)
  at cats.data.StateT.run(StateT.scala:58)
  at cats.data.StateTMonad.$anonfun$tailRecM$2(StateT.scala:282)
  at cats.data.StateTMonad$$Lambda$1991/1379697920.apply(Unknown Source)
  at cats.package$$anon$1.tailRecM(package.scala:36)
  at fetch.unsafe.implicits$$anon$2.tailRecM(unsafeImplicits.scala:106)
  at cats.data.StateTMonad.$anonfun$tailRecM$1(StateT.scala:281)
  at cats.data.StateTMonad$$Lambda$1989/1200822908.apply(Unknown Source)
  at cats.data.StateT.$anonfun$run$1(StateT.scala:58)
  at cats.data.StateT$$Lambda$1990/1456370760.apply(Unknown Source)
  at fetch.unsafe.implicits$$anon$2.flatMap(unsafeImplicits.scala:122)
  at cats.data.StateT.run(StateT.scala:58)
  at cats.data.StateT.runA(StateT.scala:70)
  at fetch.package$Fetch$FetchRunnerA.apply(fetch.scala:242)
  ... 980 elided
Caused by: java.lang.Exception: Something went terribly wrong
  ... 929 more
```

### join

We can compose two independent fetches with `Fetch#join`.

```scala
val fetchJoined: Fetch[(Post, User)] = Fetch.join(getPost(1), getUser(2))
```

If the fetches are to the same data source they will be batched; if they aren't, they will be evaluated at the same time.

```scala
Fetch.run[Id](fetchJoined)
// ~~> [157] One User 2
// <~~ [157] One User 2
// ~~> [157] One Post 1
// <~~ [157] One Post 1
// res50: cats.Id[(Post, User)] = (Post(1,2,An article),User(2,@two))
```

### sequence

The `Fetch#sequence` combinator turns a `List[Fetch[A]]` into a `Fetch[List[A]]`, running all the fetches concurrently
and batching when possible.

```scala
val fetchSequence: Fetch[List[User]] = Fetch.sequence(List(getUser(1), getUser(2), getUser(3)))
```

Note that `Fetch#sequence` is not as general as the `sequence` method from `Traverse`, but performs the same optimizations.

```scala
Fetch.run[Id](fetchSequence)
// ~~> [157] Many Users NonEmptyList(1, 2, 3)
// <~~ [157] Many Users NonEmptyList(1, 2, 3)
// res51: cats.Id[List[User]] = List(User(1,@one), User(2,@two), User(3,@three))
```

### traverse

The `Fetch#traverse` combinator is a combination of `map` and `sequence`.

```scala
val fetchTraverse: Fetch[List[User]] = Fetch.traverse(List(1, 2, 3))(getUser)
```

Note that `Fetch#traverse` is not as general as the `traverse` method from `Traverse`, but performs the same optimizations.

```scala
Fetch.run[Id](fetchTraverse)
// ~~> [157] Many Users NonEmptyList(1, 2, 3)
// <~~ [157] Many Users NonEmptyList(1, 2, 3)
// res52: cats.Id[List[User]] = List(User(1,@one), User(2,@two), User(3,@three))
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

```scala
import cats.syntax.cartesian._

val fetchThree: Fetch[(Post, User, Post)] = (getPost(1) |@| getUser(2) |@| getPost(2)).tupled
```

Notice how the queries to posts are batched.

```scala
fetchThree.runA[Id]
// ~~> [157] Many Posts NonEmptyList(2, 1)
// <~~ [157] Many Posts NonEmptyList(2, 1)
// ~~> [157] One User 2
// <~~ [157] One User 2
// res54: cats.Id[(Post, User, Post)] = (Post(1,2,An article),User(2,@two),Post(2,3,Another article))
```

More interestingly, we can use it to apply a pure function to the results of various
fetches.

```scala
val fetchFriends: Fetch[String] = (getUser(1) |@| getUser(2)).map({ (one, other) =>
  s"${one.username} is friends with ${other.username}"
})
// fetchFriends: fetch.Fetch[String] = Free(...)

fetchFriends.runA[Id]
// ~~> [157] Many Users NonEmptyList(2, 1)
// <~~ [157] Many Users NonEmptyList(2, 1)
// res55: cats.Id[String] = @one is friends with @two
```

The above example is equivalent to the following using the `Fetch#join` method:

```scala
val fetchFriends: Fetch[String] = Fetch.join(getUser(1), getUser(2)).map({ case (one, other) =>
  s"${one.username} is friends with ${other.username}"
})
// fetchFriends: fetch.Fetch[String] = Free(...)

fetchFriends.runA[Id]
// ~~> [157] Many Users NonEmptyList(2, 1)
// <~~ [157] Many Users NonEmptyList(2, 1)
// res56: cats.Id[String] = @one is friends with @two
```

# Concurrency monads

Fetch lets you choose the concurrency monad you want for running fetches, supporting the Scala and Scala.js
standard library concurrency primitives. However not everyone is using `Future` and Fetch acknowledges it,
providing support for the most widespread concurrency monads and making it easy for users to run a fetch to a
custom type.

For supporting running a fetch to a monad `M[_]` an instance of `FetchMonadError[M]` must be available.

We'll use the following fetches for the examples. They show how we can combine independent fetches both for
batching and exploiting the concurrency of independent data.

```scala
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

```scala
Await.result(Fetch.run[Future](homePage),  Duration.Inf)
// ~~> [161] Many Posts NonEmptyList(1, 2, 3)
// <~~ [161] Many Posts NonEmptyList(1, 2, 3)
// ~~> [158] Many Users NonEmptyList(2, 3)
// ~~> [161] Many Post Topics NonEmptyList(Post(2,3,Another article), Post(3,4,Yet another article))
// <~~ [158] Many Users NonEmptyList(2, 3)
// <~~ [161] Many Post Topics NonEmptyList(Post(2,3,Another article), Post(3,4,Yet another article))
// res59: (List[Post], Map[PostTopic,Int]) = (List(Post(2,3,Another article), Post(1,2,An article)),Map(monad -> 1, applicative -> 1))
```

## Monix Task

The [Monix](https://monix.io/) library provides an abstraction for lazy, asynchronous computations with its [Task](https://monix.io/docs/2x/eval/task.html) type.

For using `Task` as the target concurrency monad of a fetch, add the following dependency to your build file:

```scala
"com.47deg" %% "fetch-monix" % "0.6.0"
```

And do some standard imports, we'll need an Scheduler for running our tasks as well as the instance of `FetchMonadError[Task]` that `fetch-monix` provides:

```scala
import monix.eval.Task
import monix.execution.Scheduler

import fetch.monixTask.implicits._
```

Note that running a fetch to a `Task` doesn't trigger execution. We can interpret a task to a `Future` with the `Task#runAsync` method. We'll use the global scheduler for now.

```scala
val scheduler = Scheduler.Implicits.global
// scheduler: monix.execution.Scheduler = monix.execution.schedulers.AsyncScheduler@288cba26

val task = Fetch.run[Task](homePage)
// task: monix.eval.Task[(List[Post], Map[PostTopic,Int])] = Task.FlatMap(Task.FlatMap(Task.Now(cats.data.StateTMonad$$Lambda$1989/1200822908@641e7bda), cats.data.StateT$$Lambda$1990/1456370760@5156fac), monix.eval.Task$$Lambda$2262/1075148235@3bdbc8f)

Await.result(task.runAsync(scheduler), Duration.Inf)
// ~~> [161] Many Posts NonEmptyList(1, 2, 3)
// <~~ [161] Many Posts NonEmptyList(1, 2, 3)
// ~~> [161] Many Post Topics NonEmptyList(Post(2,3,Another article), Post(3,4,Yet another article))
// ~~> [162] Many Users NonEmptyList(2, 3)
// <~~ [161] Many Post Topics NonEmptyList(Post(2,3,Another article), Post(3,4,Yet another article))
// <~~ [162] Many Users NonEmptyList(2, 3)
// res61: (List[Post], Map[PostTopic,Int]) = (List(Post(2,3,Another article), Post(1,2,An article)),Map(monad -> 1, applicative -> 1))
```

### JVM

In the JVM, you may want to choose a [scheduler tuned for IO workloads](https://monix.io/docs/2x/execution/scheduler.html#builders-on-the-jvm) to interpret fetches.

```scala
val ioSched = Scheduler.io(name="io-scheduler")
// ioSched: monix.execution.schedulers.SchedulerService = monix.execution.schedulers.ExecutorScheduler$FromSimpleExecutor@4d605efb

Await.result(task.runAsync(ioSched), Duration.Inf)
// ~~> [164] Many Posts NonEmptyList(1, 2, 3)
// <~~ [164] Many Posts NonEmptyList(1, 2, 3)
// ~~> [165] Many Post Topics NonEmptyList(Post(2,3,Another article), Post(3,4,Yet another article))
// ~~> [167] Many Users NonEmptyList(2, 3)
// <~~ [165] Many Post Topics NonEmptyList(Post(2,3,Another article), Post(3,4,Yet another article))
// <~~ [167] Many Users NonEmptyList(2, 3)
// res62: (List[Post], Map[PostTopic,Int]) = (List(Post(2,3,Another article), Post(1,2,An article)),Map(monad -> 1, applicative -> 1))
```

## Custom types

If you want to run a fetch to a custom type `M[_]`, you need to implement the `FetchMonadError[M]` typeclass. `FetchMonadError[M]` is simply a `MonadError[M, FetchException]` from cats augmented
with a method for running a `Query[A]` in the context of the monad `M[A]`.

For ilustrating integration with an asynchronous concurrency monad we'll use the implementation of Monix Task.

### Running queries

First of all, we need to run queries in our target type. As we have learned, queries can be synchronous (simply wrapping an `Eval` from Cats) or asynchronous. Since we'll need to lift
`Eval[A]` values to `Task[A]`, let's write a function for doing so first. Note that Monix's `Task` supports the same evaluation strategies of `Eval` in Cats, so the conversion is very
direct:

```scala
import cats.{Eval, Now, Later, Always}
import monix.eval.Task

def evalToTask[A](e: Eval[A]): Task[A] = e match {
  case Now(x) => Task.now(x)
  case l: Later[A]  => Task.evalOnce(l.value)
  case a: Always[A] => Task.eval(a.value)
  case other => Task.evalOnce(other.value)
}
```

Now that we can run synchronous queries to `Task`, we'll use `Task#create` for running asynchronous computations. Queries also have a third option: `Ap`, which delegates the applicative combination of independent queries to the target monad.

```scala
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

We make use of the `FromMonadError` class below, making it easer to implement `FetchMonadError[Task]` given a `MonadError[Task, Throwable]` which we can get from the _monix-cats_ projects.

```scala
import monix.cats._

implicit val taskFetchMonadError: FetchMonadError[Task] =
  new FetchMonadError.FromMonadError[Task] {
    override def runQuery[A](q: Query[A]): Task[A] = queryToTask[A](q)

    override def map[A, B](fa: Task[A])(f: A => B): Task[B] =
      fa.map(f)

    override def product[A, B](fa: Task[A], fb: Task[B]): Task[(A, B)] =
      Task.zip2(Task.fork(fa), Task.fork(fb))
  }
```

We can now import the above implicit and run a fetch to our custom type, let's give it a go:

```scala
val task = Fetch.run(homePage)(taskFetchMonadError)
// task: monix.eval.Task[(List[Post], Map[PostTopic,Int])] = Task.FlatMap(Task.FlatMap(Task.Now(cats.data.StateTMonad$$Lambda$1989/1200822908@5400439a), cats.data.StateT$$Lambda$1990/1456370760@7d47d498), monix.eval.Task$$Lambda$2262/1075148235@45c39352)

Await.result(task.runAsync(scheduler), Duration.Inf)
// ~~> [162] Many Posts NonEmptyList(1, 2, 3)
// <~~ [162] Many Posts NonEmptyList(1, 2, 3)
// ~~> [162] Many Post Topics NonEmptyList(Post(2,3,Another article), Post(3,4,Yet another article))
// ~~> [161] Many Users NonEmptyList(2, 3)
// <~~ [162] Many Post Topics NonEmptyList(Post(2,3,Another article), Post(3,4,Yet another article))
// <~~ [161] Many Users NonEmptyList(2, 3)
// res66: (List[Post], Map[PostTopic,Int]) = (List(Post(2,3,Another article), Post(1,2,An article)),Map(monad -> 1, applicative -> 1))
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
val batched: Fetch[List[User]] = Fetch.multiple(1, 2)(UserSource)
val cached: Fetch[User] = getUser(2)
val concurrent: Fetch[(List[User], List[Post])] = (List(1, 2, 3).traverse(getUser) |@| List(1, 2, 3).traverse(getPost)).tupled

val interestingFetch = for {
  users <- batched
  anotherUser <- cached
  _ <- concurrent
} yield "done"
```

Now that we have the fetch let's run it, get the environment and visualize its execution using the `describe` function:

```scala
import fetch.debug.describe
// import fetch.debug.describe

val env = interestingFetch.runE[Id]
// ~~> [157] Many Users NonEmptyList(1, 2)
// <~~ [157] Many Users NonEmptyList(1, 2)
// ~~> [157] One User 3
// <~~ [157] One User 3
// ~~> [157] Many Posts NonEmptyList(1, 2, 3)
// <~~ [157] Many Posts NonEmptyList(1, 2, 3)
// env: cats.Id[fetch.FetchEnv] = FetchEnv(InMemoryCache(Map((Post,2) -> Post(2,3,Another article), (User,2) -> User(2,@two), (User,3) -> User(3,@three), (User,1) -> User(1,@one), (Post,3) -> Post(3,4,Yet another article), (Post,1) -> Post(1,2,An article))),Queue(Round(InMemoryCache(Map()),FetchMany(NonEmptyList(1, 2),User),List(User(1,@one), User(2,@two)),87459793865292,87459904502030), Round(InMemoryCache(Map((User,1) -> User(1,@one), (User,2) -> User(2,@two))),Concurrent(NonEmptyList(FetchOne(3,User), FetchMany(NonEmptyList(1, 2, 3),Post))),NonEmptyList(Map(3 -> User(3,@three)), Map(1 -> Post(1,2,An article), 2 -> Post(2,3,Another article), 3 -> Post(3,4,Yet another article))),87459907333203,87460114300397)))

println(describe(env))
// Fetch execution took 0.320435 seconds
// 
//   [Fetch many] From `User` with ids List(1, 2) took 0.000111 seconds
//   [Concurrent] took 0.000207 seconds
//     [Fetch one] From `User` with id 3
//     [Fetch many] From `Post` with ids List(1, 2, 3)
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
- [Clump](http://getclump.io) has inspired the signature of the `DataSource#fetch*` methods.
- [Stitch](https://engineering.twitter.com/university/videos/introducing-stitch) is an in-house Twitter library that is not open source but has inspired Fetch's high-level API.
- [Cats](http://typelevel.org/cats/), a library for functional programming in Scala.
- [Monix](https://monix.io) high-performance and multiplatform (Scala / Scala.js) asynchronous programming library.
