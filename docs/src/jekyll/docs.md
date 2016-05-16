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

First of all, add the following dependency to your SBT build file:

```scala
"org.fortysevendeg" %%% "fetch" % "0.1.0"
```

Now you'll have Fetch available both in Scala and Scala.js.

# Usage

In order to tell Fetch how to retrieve data, we must implement the `DataSource` typeclass.

```scala
trait DataSource[Identity, Result]{
  def fetch(ids: List[Identity]): Eval[Map[Identity, Result]]
}
```

It takes two type parameters:

 - `Identity`: the identity we want to fetch (a `UserId` if we were fetching users)
 - `Result`: the type of the data we retrieve (a `User` if we were fetching users)

The `fetch` method takes a non empty list of identities and must return an `Eval` that will result
in a map from identities to results. Accepting a list of identities gives Fetch the ability to batch requests to
the same data source, and returning a mapping from identities to results Fetch can detect whenever an identity
couldn't be fetched or no longer exists.

Returning `Eval` makes it possible to defer evaluation with a concurrency monad when running a fetch. For example
when using `Future` as your target monad when running a fetch the data source fetches will possibly run in a thread pool,
depending on the implicit execution context you use.

## Writing your first data source

Now that we know about the `DataSource` typeclass, let's write our first data source! We'll be using Scala's
`Future` as our concurrency monad for the examples. We'll write a little function for simulating latency and
another for blocking on futures to get their results. Note that the `Future` API differs in Scala and Scala.js,
since there are no threads in JavaScript and you cannot block for a `Future`'s result.

```scala
import scala.util.Random
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import cats.Eval

def latency[A](result: A, msg: String, wait: Int = Random.nextInt(100)): Eval[A] =
  Eval.later({
    val threadId = Thread.currentThread().getId()
    println(s"~~~>[thread: $threadId] $msg")
    Thread.sleep(wait)
    println(s"<~~~[thread: $threadId] $msg")
    result
  })

def await[A](f: Future[A]): A = Await.result(f, 5 seconds)
```

We'll start implementing a data source for fetching users given their id. The identity type will be a user's
id and the result type a user.

```scala
import fetch._

type UserId = Int
case class User(id: UserId, username: String)

implicit object UserSource extends DataSource[UserId, User]{
  override def fetch(ids: List[UserId]): Eval[Map[UserId, User]] = {
    val result = ids.map(i => (i, User(i, s"@egg_$i"))).toMap
    latency(result, s"Fetching users $ids")
  }
}
```

Now that we have a data source we can write a function for fetching users
given an id, we just have to pass a `UserId` as an argument to `Fetch`.

```scala
def getUser(id: UserId): Fetch[User] = Fetch(id) // or, more explicitly: Fetch(id)(UserSource)
```

## Declaring and running a fetch

We are now ready to declare and run fetches, the only thing missing is an instance
of `MonadError[M, Throwable]` for our target monad (`scala.concurrent.Future`), which
the `cats` library already provides.

```scala
import cats.std.future._

val fch: Fetch[User] = getUser(1)
val fut: Future[User] = Fetch.run(fch)

await(fut)
// ~~~>[thread: 43] Fetching users List(1)
// <~~~[thread: 43] Fetching users List(1)

//=> User(1,@egg_1)
```

In the previous example, we:

- brought the implicit instance of `MonadError[Future, Throwable]` into scope importing `cats.std.future._`
- created a fetch for a `User` using the `getUser` function
- interpreted the fetch to a `Future` using `Fetch.run`

As you can see, the fetch was executed only in one round to fetch the user and was finished after that.

### Sequencing

When we have two fetches that depend on each other, we can use `flatMap` to combine them. When composing fetches with `flatMap` we are telling Fetch that the second one depends on the previous one, so it isn't able to make any optimizations.

```scala
val fch: Fetch[(User, User)] = for {
  aUser <- getUser(1)
  anotherUser <- getUser(aUser.id + 1)
} yield (aUser, anotherUser)
  
val fut: Future[(User, User)] = Fetch.run(fch)
  
await(fut)
// ~~~>[thread: 44] Fetching users List(1)
// <~~~[thread: 44] Fetching users List(1)
// ~~~>[thread: 42] Fetching users List(2)
// <~~~[thread: 42] Fetching users List(2)

//=> (User(1,@egg_1),User(2,@egg_2))
```

### Batching

If we combine two independent requests to the same data source, Fetch will
automatically batch them together into a single request. Fetch's `join` operator
help us tell the library that two fetches are independent, and thus can be batched
if they use the same data source:

```scala
val fch: Fetch[(User, User)] = Fetch.join(getUser(1), getUser(2))
val fut: Future[(User, User)] = Fetch.run(fch)

await(fut)
// ~~~>[thread: 47] Fetching users List(1, 2)
// <~~~[thread: 47] Fetching users List(1, 2)

//=> (User(1,@egg_1),User(2,@egg_2))
```

### Deduplication

If two independent requests ask for the same identity, Fetch will detect that and
deduplicate such id.

```scala
val fch: Fetch[(User, User)] = Fetch.join(getUser(1), getUser(1))
val fut: Future[(User, User)] = Fetch.run(fch)

await(fut)
// ~~~>[thread: 47] Fetching users List(1)
// <~~~[thread: 47] Fetching users List(1)

//=> (User(1,@egg_1),User(1,@egg_1))
```

### Caching

During a fetch, previously requested results are implicitly cached. This allows us to write
fetches in a very modular way, asking for all the data they need as if it
was in memory; furthermore, it also avoids refetching an identity that may have changed
during the course of a fetch execution, which can lead to inconsistencies in the data.

```scala
val fch: Fetch[(User, User)] = for {
  aUser <- getUser(1)
  anotherUser <- getUser(1)
} yield (aUser, anotherUser)

val fut: Future[(User, User)] = Fetch.run(fch)

await(fut)
// ~~~>[thread: 53] Fetching users List(1)
// <~~~[thread: 53] Fetching users List(1)

//=> (User(1,@egg_1),User(1,@egg_1))
```

As you can see, the `User` with id 1 was fetched only once in a single round-trip. The next
time it was needed we used the cached versions, thus avoiding another request to the user data
source.

## Combining data from multiple sources

Now that we know about some of the optimizations that Fetch can make to read data efficiently,
let's look at how we can combine more than one data source. Imagine that we are rendering a blog
and have the following types for posts and post information:

```scala
type PostId = Int
case class Post(id: PostId, author: UserId, content: String)
case class PostInfo(topic: String)
```

As you can see, every `Post` has an author, but it refers to it by its id. We'll implement two data sources:

- one for retrieving a post given a post id
- another for retrieveng post metadata given a post id

```scala
implicit object PostSource extends DataSource[PostId, Post]{
  override def fetch(ids: List[PostId]): Eval[Map[PostId, Post]] = {
    val result = ids.map(i => (i, Post(i, i % 3 + 1, s"An article with id $i"))).toMap
    latency(result, s"Fetching posts $ids")
  }
}

def getPost(id: PostId): Fetch[Post] = Fetch(id)

implicit object PostInfoSource extends DataSource[PostId, PostInfo]{
  override def fetch(ids: List[PostId]): Eval[Map[PostId, PostInfo]] = {
    val result = ids.map(i => (i, PostInfo(if (i % 2 == 0) "applicative" else "monad"))).toMap
    latency(result, s"Fetching post info $ids")
  }
}
  
def getPostInfo(id: PostId): Fetch[PostInfo] = Fetch(id)
```

We can also implement a function for fetching a post's author given a post:

```scala
def getAuthor(p: Post): Fetch[User] = Fetch(p.author)
```

Now that we have multiple sources, let's mix them in the same fetch.

```scala
val fch: Fetch[(Post, User)] = for {
  post <- getPost(1)
  user <- getAuthor(post)
} yield (post, user)

val fut: Future[(Post, User)] = Fetch.run(fch)

await(fut)
// ~~~>[thread: 81] Fetching posts List(1)
// <~~~[thread: 81] Fetching posts List(1)
// ~~~>[thread: 82] Fetching users List(2)
// <~~~[thread: 82] Fetching users List(2)

//=> (Post(1,2,An article with id 1),User(2,@egg_2))
```

In the previous example we fetched a post given its id, and then fetched its author. These
data could come from entirely different places but Fetch makes working with heterogeneous sources
of data very easy.

### Parallelism

Combining multiple independent requests to the same data source results in batching but what happens
when combining independent requests to *different* data sources? We have the oportunity to query the
different data sources at the same time.

Since we are using `Future` as our concurrency monad, the requests will effectively run in parallel,
each in its own logical thread.

```scala
val fch: Fetch[(Post, User)] = Fetch.join(getPost(1), getUser(2))
val fut: Future[(Post, User)] = Fetch.run(fch)

await(fut)
// ~~~>[thread: 83] Fetching posts List(1)
// ~~~>[thread: 84] Fetching users List(2)
// <~~~[thread: 84] Fetching users List(2)
// <~~~[thread: 83] Fetching posts List(1)

//=> (Post(1,2,An article with id 1),User(2,@egg_2))
```

As you can notice, both requests are started at the same time in different threads (with ids 83 and 84) and
the fetch finishes as soon as both results are available.

## Combinators

Besides `flatMap` for sequencing fetches and `join` for running them concurrently, Fetch provides a number of
other combinators.

### Collect

Whenever we have a list of fetches of the same type and want to run them concurrently we can use the `collect`
combinator. It takes a `List[Fetch[A]]` and gives you back a `Fetch[List[A]]`, batching the fetches to the same
data source and running fetches to different sources in parallel.

```scala
val fch: Fetch[List[User]] = Fetch.collect(List(getUser(1), getUser(2), getUser(3)))

val fut: Future[List[User]] = Fetch.run(fch)

await(fut)
// ~~~>[thread: 86] Fetching users List(1, 2, 3)
// <~~~[thread: 86] Fetching users List(1, 2, 3)

//=> List(User(1,@egg_1), User(2,@egg_2), User(3,@egg_3))
```

As you can see, requests to the user data source were batched, thus fetching all the data in one round.

### Traverse

Another interesing combinator is `traverse`, which is the composition of `map` and `collect`.a

```scala
val fch: Fetch[List[User]] = Fetch.traverse(List(1, 2, 3))(getUser)

val fut: Future[List[User]] = Fetch.run(fch)

await(fut)
// ~~~>[thread: 86] Fetching users List(1, 2, 3)
// <~~~[thread: 86] Fetching users List(1, 2, 3)

//=> List(User(1,@egg_1), User(2,@egg_2), User(3,@egg_3))
```

# Caching

As we have learned, Fetch caches intermediate results implicitly using a cache. You can
provide a prepopulated cache for running a fetch, replay a fetch with the cache of a previous
one and even implement a custom cache.

## Prepopulating a cache

We'll be using the default in-memory cache, prepopulated with some data. The cache key of an identity
is calculated with the `DataSource`'s `identity` method; we can pass a cache as the second argument when
running a fetch with `Fetch.run`.

```scala
val fch: Fetch[User] = getUser(1)
val cache = InMemoryCache(UserSource.identity(1) -> User(1, "@dialelo"))
val fut: Future[User] = Fetch.run(fch, cache)

await(fut)
//=> User(1,@dialelo)
```

As you can see, when all the data is cached no fetch is executed at all since the results are available
in the cache.

If only part of the data is cached, the cached data won't be asked for:

```scala
val fch: Fetch[List[User]] = Fetch.traverse(List(1, 2, 3))(getUser)
val cache = InMemoryCache(UserSource.identity(2) -> User(2, "@two"))
val fut: Future[List[User]] = Fetch.run(fch, cache)

await(fut)
// ~~~>[thread: 180] Fetching users List(1, 3)
// <~~~[thread: 180] Fetching users List(1, 3)

//=> List(User(1,@egg_1), User(2,@two), User(3,@egg_3))
```

## Replaying a fetch

When running a fetch we are generally interested in its final result. However, we also have access to the cache
and information about the executed rounds once we run a fetch. Fetch's interpreter keeps its state in an environment
(implementing the `Env` trait) and we can get both the environment and result after running a fetch using `Fetch.runFetch`
instead of `Fetch.run`.

Knowing this, we can replay a fetch reusing the cache of a previous one. The replayed fetch won't have to call any of the
data sources.

```scala
val fch: Fetch[List[User]] = Fetch.traverse(List(1, 2, 3))(getUser)
val env = await(Fetch.runEnv[Future](fch))
// ~~~>[thread: 261] Fetching users List(1, 2, 3)
// <~~~[thread: 261] Fetching users List(1, 2, 3)

//=> List(User(1,@egg_1), User(2,@egg_2), User(3,@egg_3))

val fut: Future[List[User]] = Fetch.run(fch, env.cache)

await(fut)
//=> List(User(1,@egg_1), User(2,@egg_2), User(3,@egg_3))
```

## Implementing a custom cache

The default cache is implemented as an in-memory map, but users are free to use their own caches when running a fetch. Your cache should implement the `DataSourceCache` trait, and after that you can pass it to Fetch's `run` methods.

```scala
trait DataSourceCache {
  def update[I, A](k: DataSourceIdentity, v: A): DataSourceCache
  def get[I](k: DataSourceIdentity): Option[Any]
}
```

Let's reimplement the in memory cache found in Fetch, we'll write a case class that'll store the cache contents in an in-memory immutable map and implement `DataSourceCache`.

```scala
case class MyInMemoryCache(state: Map[DataSourceIdentity, Any]) extends DataSourceCache {
  override def get[I](k: DataSourceIdentity): Option[Any] =
    state.get(k)

  override def update[I, A](k: DataSourceIdentity, v: A): MyInMemoryCache =
    copy(state = state.updated(k, v))
}
```

Now that we have a data type, we can use it when running a fetch:

```scala
val cache = MyInMemoryCache(Map(UserSource.identity(1) -> User(1, "dialelo")))
val fch: Fetch[User] = getUser(1)
val fut: Future[User] = Fetch.run(fch, cache)

await(fut)
//=> User(1,dialelo)
```

# Error handling

You probably have noticed that `DataSource.fetch` takes a list of identities and returns a map of identities to their result, taking
into account the possibility of some identities not being found. Whenever an identity isn't found, the fetch execution will
fail.

## Diagnosing fetch failures

Whenever a fetch fails, a `FetchFailure` exception is thrown. The `FetchFailure` will have the environment, which gives you information about the execution of a fetch.

We plan to make error handling more flexible in the future, as well as providing combinators for retrying fetches, timeouts and so on.

# Syntax

## cats

Fetch is built using cats' Free monad construction and thus works out of the box with
cats syntax. Using cats' syntax we can make fetch declarations more concise, without
the need to use the combinators in the `Fetch` companion object.

### Apply

The `|@|` operator allow us to combine multiple independent fetches, even when they
are from different types, and apply a pure function to their results. We can use it
as a more powerful alternative to `Fetch.join`:

```scala
import cats.syntax.cartesian._

val fch: Fetch[(Post, User)] = (getPost(1) |@| getUser(2)).tupled
val fut: Future[(Post, User)] = Fetch.run(fch)

await(fut)
// ~~~>[thread: 92] Fetching posts List(1)
// ~~~>[thread: 93] Fetching users List(2)
// <~~~[thread: 92] Fetching posts List(1)
// <~~~[thread: 93] Fetching users List(2)

//=> (Post(1,2,An article with id 1),User(2,@egg_2))
```

More interestingly, we can use it to apply a pure function to the results of various
fetches.

```scala
import cats.syntax.cartesian._

val fch: Fetch[String] = (getUser(1) |@| getUser(2)).map({ (one, other) =>
  s"${one.username} is friends with ${other.username}"
})
val fut: Future[String] = Fetch.run(fch)

await(fut)
// ~~~>[thread: 56] Fetching users List(1, 2)
// <~~~[thread: 56] Fetching users List(1, 2)

//=> @egg_1 is friends with @egg_2
```

### Traverse

Apart from using Fetch with cartesian syntax and performing all fetches in a product concurrently, we can treat Fetch as an applicative and get implicit concurrency and batching as well.

For the next example, we'll import cats' extensions to the `List` type and traverse syntax. This will allow us to use both `sequence` and `traverse` methods on lists, which are equivalent to `Fetch#collect` and `Fetch#traverse`.

```scala
import cats.std.list._
import cats.syntax.traverse._
```

We'll start by using `sequence`, which can transform `List[Fetch[A]]` to a `Fetch[List[A]]`.

```scala
val fch: Fetch[List[User]] = List(getUser(1), getUser(2), getUser(3)).sequence
val fut: Future[List[User]] = Fetch.run(fch)

await(fut)
// ~~~>[thread: 65] Fetching users List(1, 2, 3)
// <~~~[thread: 65] Fetching users List(1, 2, 3)

//=> List(User(1,@egg_1), User(2,@egg_2), User(3,@egg_3))
```

Next is `traverse`, which traverses a collection with a fetch-returning function and collapses the result into a fetch of a collection.

```scala
val fch: Fetch[List[User]] = List(1, 2, 3).traverse(getUser)
val fut: Future[List[User]] = Fetch.run(fch)

await(fut)
// ~~~>[thread: 62] Fetching users List(1, 2, 3)
// <~~~[thread: 62] Fetching users List(1, 2, 3)

//=>  List(User(1,@egg_1), User(2,@egg_2), User(3,@egg_3))
```

## scalaz

There is still no integration with Scalaz, but soon will be.
