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
"com.fortysevendeg" %%% "fetch" % "0.1.0"
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

The `fetch` method takes a non-empty list of identities and must return an [Eval](https://github.com/typelevel/cats/blob/master/core/src/main/scala/cats/Eval.scala) that will result
in a map from identities to results. Accepting a list of identities gives Fetch the ability to batch requests to
the same data source, and returning a mapping from identities to results, Fetch can detect whenever an identity
couldn't be fetched or no longer exists.

Returning `Eval` makes it possible to defer evaluation with a monad when running a fetch.

## Writing your first data source

Now that we know about the `DataSource` typeclass, let's write our first data source! We'll start by implementing a data
source for fetching users given their id. The first thing we'll do is define the types for user ids and users.

```tut:silent
type UserId = Int
case class User(id: UserId, username: String)
```

And now we're ready to write our user data source; we'll emulate a database with an in-memory map.

```tut:silent
import cats.Eval
import fetch._

val userDatabase: Map[UserId, User] = Map(
  1 -> User(1, "@one"),
  2 -> User(2, "@two"),
  3 -> User(3, "@three")
)

implicit object UserSource extends DataSource[UserId, User]{
  override def fetch(ids: List[UserId]): Eval[Map[UserId, User]] = {
    Eval.later({
      println(s"Fetching users $ids")
	  userDatabase.filterKeys(ids.contains)
    })
  }
}
```

Now that we have a data source we can write a function for fetching users
given an id, we just have to pass a `UserId` as an argument to `Fetch`.

```tut:silent
def getUser(id: UserId): Fetch[User] = Fetch(id) // or, more explicitly: Fetch(id)(UserSource)
```

## Creating and running a fetch

We are now ready to create and run fetches. Note the distinction between Fetch creation and execution.
When we are creating and combining `Fetch` values, we are just constructing a recipe of our data
dependencies.

```tut:silent
import cats.Id
import fetch.implicits._

val fetchUser: Fetch[User] = getUser(1)
```

A `Fetch` is just a value, and in order to get something out of it, we must execute it. We can execute a `Fetch` value as many times as we want, even to different target monads, since it is just
an immutable value.

We need to provide a target monad when we want to execute a fetch. We'll be using `Id` for now.
Make sure to import `fetch.implicits._` since Fetch needs an instance of `MonadError[Id, Throwable]` for running
a fetch in the `Id` monad.

Note that Fetch provides `MonadError` instances for a variety of different monads like `Eval` or
`Future` so it's likely that you won't have to write your own.

Let's run our first fetch!

```tut:book
val result: User = Fetch.run[Id](fetchUser)
```

In the previous examples, we:

- brought the implicit instance of `MonadError[Id, Throwable]` into scope importing `fetch.implicits._`
- created a fetch for a `User` using the `getUser` function
- interpreted the fetch to a `Id[User]` (which is just a `User`) using `Fetch.run`

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
val result: (User, User) = Fetch.run[Id](fetchTwoUsers)
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
val result: (User, User) = Fetch.run[Id](fetchProduct)
```

### Deduplication

If two independent requests ask for the same identity, Fetch will detect it and deduplicate the id.

```tut:silent
val fetchDuped: Fetch[(User, User)] = getUser(1).product(getUser(1))
```

Note that when running the fetch, the identity 1 is only requested once even when it is needed by both fetches.

```tut:book
val result: (User, User) = Fetch.run[Id](fetchDuped)
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
val result: (User, User) = Fetch.run[Id](fetchCached)
```

As you can see, the `User` with id 1 was fetched only once in a single round-trip. The next
time it was needed we used the cached versions, thus avoiding another request to the user data
source.

## Combining data from multiple sources

Now that we know about some of the optimizations that Fetch can perform to read data efficiently,
let's look at how we can combine more than one data source. Imagine that we are rendering a blog
and have the following types for posts and post information:

```tut:silent
type PostId = Int
case class Post(id: PostId, author: UserId, content: String)
case class PostInfo(topic: String)
```

As you can see, every `Post` has an author, but it refers to the author by its id. We'll implement two data sources:

- one for retrieving a post given a post id
- another for retrieving post metadata given a post id

```tut:silent
val postDatabase: Map[PostId, Post] = Map(
  1 -> Post(1, 2, "An article"),
  2 -> Post(2, 3, "Another article"),
  3 -> Post(3, 4, "Yet another article")
)

implicit object PostSource extends DataSource[PostId, Post]{
  override def fetch(ids: List[PostId]): Eval[Map[PostId, Post]] = {
    Eval.later({
      println(s"Fetching posts $ids")
      postDatabase.filterKeys(ids.contains)
    })
  }
}

def getPost(id: PostId): Fetch[Post] = Fetch(id)

val postInfoDatabase: Map[PostId, PostInfo] = Map(
  1 -> PostInfo("monad"),
  2 -> PostInfo("applicative"),
  3 -> PostInfo("monad")
)

implicit object PostInfoSource extends DataSource[PostId, PostInfo]{
  override def fetch(ids: List[PostId]): Eval[Map[PostId, PostInfo]] = {
    Eval.later({
      println(s"Fetching post info $ids")
      postInfoDatabase.filterKeys(ids.contains)
    })
  }
}

def getPostInfo(id: PostId): Fetch[PostInfo] = Fetch(id)
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
val result: (Post, User) = Fetch.run[Id](fetchMulti)
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
val result: (Post, User) = Fetch.run[Id](fetchConcurrent)
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
val result: List[User] = Fetch.run[Id](fetchSequence)
```

As you can see, requests to the user data source were batched, thus fetching all the data in one round.

### Traverse

Another interesing combinator is `traverse`, which is the composition of `map` and `sequence`.

```tut:silent
val fetchTraverse: Fetch[List[User]] = List(1, 2, 3).traverse(getUser)
```

As you may have guessed, all the optimizations made by `sequence` still apply when using `traverse`.

```tut:book
val result: List[User] = Fetch.run[Id](fetchTraverse)
```

# Interpreting a fetch to an async capable monad

Although the examples use `Id` as the target Monad, `Fetch` is not limited to just `Id`, any monad `M` that
implements `MonadError[M, Throwable]` will do. Fetch provides `MonadError` instances for some existing monads like
`Future`, `cats.Id` and `cats.Eval` and it's easy to write your own.

For practice, you'll be interpreting a fetch to an async capable monad like `Future` or `scalaz.concurrent.Task` to exploit
parallelism whenever we can make requests to multiple independent data sources at the same time.

## Future

For interpreting a fetch into a `Future` we must first import the `MonadError[Future, Throwable]` available in cats.

```tut:silent
import cats.std.future._

import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global


val fetchParallel: Fetch[(User, Post)] = (getUser(1) |@| getPost(1)).tupled
```

We can now interpret a fetch into a future:

```tut:book
val fut: Future[(User, Post)] = Fetch.run[Future](fetchParallel)
Await.result(fut, 1 seconds) // this call blocks the current thread, don't do this at home!
```

Since futures run in a thread pool, we need to explicitly set println output to the standard output. Note how both requests
to the data sources run in parallel, each in its own logical thread.

# Caching

As we have learned, Fetch caches intermediate results implicitly using a cache. You can
provide a prepopulated cache for running a fetch, replay a fetch with the cache of a previous
one, and even implement a custom cache.

## Prepopulating a cache

We'll be using the default in-memory cache, prepopulated with some data. The cache key of an identity
is calculated with the `DataSource`'s `identity` method.

```tut:silent
val cache = InMemoryCache(UserSource.identity(1) -> User(1, "@dialelo"))
```

We can pass a cache as the second argument when running a fetch with `Fetch.run`.

```tut:book
val result: User = Fetch.run[Id](fetchUser, cache)
```

As you can see, when all the data is cached, no query to the data sources is executed since the results are available
in the cache.

```tut:silent
val fetchManyUsers: Fetch[List[User]] = List(1, 2, 3).traverse(getUser)
```

If only part of the data is cached, the cached data won't be asked for:

```tut:book
val result: List[User] = Fetch.run[Id](fetchManyUsers, cache)
```

## Replaying a fetch without querying any data source

When running a fetch, we are generally interested in its final result. However, we also have access to the cache
and information about the executed rounds once we run a fetch. Fetch's interpreter keeps its state in an environment
(implementing the `Env` trait), and we can get both the environment and result after running a fetch using `Fetch.runFetch`
instead of `Fetch.run`.

Knowing this, we can replay a fetch reusing the cache of a previous one. The replayed fetch won't have to call any of the
data sources.

```tut:book
val populatedCache = Fetch.runEnv[Id](fetchManyUsers).cache

val result: List[User] = Fetch.run[Id](fetchManyUsers, populatedCache)
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

Let's reimplement the in-memory cache found in Fetch; we'll write a case class that'll store the cache contents in an in-memory immutable map and implement `DataSourceCache`.

```tut:silent
case class MyInMemoryCache(state: Map[DataSourceIdentity, Any]) extends DataSourceCache {
  override def get(k: DataSourceIdentity): Option[Any] =
    state.get(k)

  override def update[A](k: DataSourceIdentity, v: A): MyInMemoryCache =
    copy(state = state.updated(k, v))
}
```

Now that we have our cache implementation, we can populate it. Note how keys for the cache are tuples and are derived using the data source's `identity` method on identities.

```tut:silent
val myCache = MyInMemoryCache(Map(UserSource.identity(1) -> User(1, "dialelo")))
```

We can now use our implementation of the cache when running a fetch.

```tut:book
val result: User = Fetch.run[Id](fetchUser, myCache)
```

# Error handling

As we mentioned before, when interpreting a fetch to a target monad `M`, an implicit instance of `MonadError[M, Throwable]` has to be
available. [MonadError](https://github.com/typelevel/cats/blob/master/core/src/main/scala/cats/MonadError.scala) gives us a few combinators
for working with errors, like `MonadError#raiseError` and `MonadError#attempt`.

One of the most interesting combinators is `attempt`, which given a `M[A]` yields a `M[Throwable Xor A]`. Knowing this, we can run fetches
in the `Eval` monad to an `Xor` and not worry about exceptions. Let's create a fetch that always fails when executed:

```tut:silent
import cats.data.Xor
import fetch.implicits.evalMonadError

val fetchError: Fetch[User] = Fetch.error(new Exception("Oh noes"))
```

We can now use the Eval MonadError's `attempt` to convert a fetch result into a disjuntion and avoid throwing exceptions.

```tut:book
val result: Eval[User] = Fetch.run[Eval](fetchError)
val safeResult: Eval[Throwable Xor User] = evalMonadError.attempt(result)
val finalValue: Throwable Xor User = safeResult.value
```

In the above example, we didn't use `Id` since interpreting a fetch to `Id` throws the exception, and we can't capture it with the
combinators in `MonadError`.

## Missing identities

You've probably noticed that `DataSource.fetch` takes a list of identities and returns a map of identities to their result, taking
into account the possibility of some identities not being found. Whenever an identity cannot be found, the fetch execution will
fail.

Whenever a fetch fails, a `FetchFailure` exception is thrown. The `FetchFailure` will have the environment, which gives you information
about the execution of the fetch.

# Syntax

## Companion object

We've been using cats' syntax throughout the examples since it's more concise and general than the
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
val result: Int = Fetch.run[Id](fetchPure)
```

### error

Errors can also be lifted to the Fetch monad, in this case with `Fetch#error`. Note that interpreting
an errorful fetch to `Id` will throw the exception so we won't do that:

```tut:silent
val fetchFail: Fetch[Int] = Fetch.error(new Exception("Something went terribly wrong"))
```

### join

We can compose two independent fetches with `Fetch#join`.

```tut:silent
val fetchJoined: Fetch[(Post, User)] = Fetch.join(getPost(1), getUser(2))
```

If the fetches are to the same data source they will be batched; if they aren't, they will be evaluated at the same time.

```tut:book
val result: (Post, User) = Fetch.run[Id](fetchJoined)
```

### sequence

The `Fetch#sequence` combinator turns a `List[Fetch[A]]` into a `Fetch[List[A]]`, running all the fetches concurrently
and batching when possible.

```tut:silent
val fetchSequence: Fetch[List[User]] = Fetch.sequence(List(getUser(1), getUser(2), getUser(3)))
```

Note that `Fetch#sequence` is not as general as the `sequence` method from `Traverse`, but performs the same optimizations.

```tut:book
val result: List[User] = Fetch.run[Id](fetchSequence)
```

### traverse

The `Fetch#traverse` combinator is a combination of `map` and `sequence`.

```tut:silent
val fetchTraverse: Fetch[List[User]] = Fetch.traverse(List(1, 2, 3))(getUser)
```

Note that `Fetch#traverse` is not as general as the `traverse` method from `Traverse`, but performs the same optimizations.

```tut:book
val result: List[User] = Fetch.run[Id](fetchTraverse)
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
val result: (Post, User, Post) = Fetch.run[Id](fetchThree)
```

More interestingly, we can use it to apply a pure function to the results of various
fetches.

```tut:book
val fetchFriends: Fetch[String] = (getUser(1) |@| getUser(2)).map({ (one, other) =>
  s"${one.username} is friends with ${other.username}"
})

val result: String = Fetch.run[Id](fetchFriends)
```

The above example is equivalent to the following using the `Fetch#join` method:

```tut:book
val fetchFriends: Fetch[String] = Fetch.join(getUser(1), getUser(2)).map({ case (one, other) =>
  s"${one.username} is friends with ${other.username}"
})

val result: String = Fetch.run[Id](fetchFriends)
```

# Resources

- [Code](https://github.com/47deg/fetch) on GitHub.
- [Documentation site](http://47deg.github.io/fetch/)
- [Fetch: Simple & Efficient data access](https://www.youtube.com/watch?v=45fcKYFb0EU) talk at [Typelevel Summit in Oslo](http://typelevel.org/event/2016-05-summit-oslo/)
