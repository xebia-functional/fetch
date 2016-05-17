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
"com.fortysevendeg" %%% "fetch" % "0.1.0"
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

The `fetch` method takes a non empty list of identities and must return an [`Eval`](https://github.com/typelevel/cats/blob/master/core/src/main/scala/cats/Eval.scala) that will result
in a map from identities to results. Accepting a list of identities gives Fetch the ability to batch requests to
the same data source, and returning a mapping from identities to results Fetch can detect whenever an identity
couldn't be fetched or no longer exists.

Returning `Eval` makes it possible to defer evaluation with a monad when running a fetch.

## Writing your first data source

Now that we know about the `DataSource` typeclass, let's write our first data source! We'll start implementing a data
source for fetching users given their id. The identity type will be a user's id and the result type a user.

```scala
import cats.Eval

import fetch._

type UserId = Int
case class User(id: UserId, username: String)

implicit object UserSource extends DataSource[UserId, User]{
  override def fetch(ids: List[UserId]): Eval[Map[UserId, User]] = {
    Eval.later({
      println(s"Fetching users $ids")
	  ids.map(i => (i, User(i, s"@egg_$i"))).toMap
    })
  }
}
```

Now that we have a data source we can write a function for fetching users
given an id, we just have to pass a `UserId` as an argument to `Fetch`.

```scala
def getUser(id: UserId): Fetch[User] = Fetch(id) // or, more explicitly: Fetch(id)(UserSource)
```

## Declaring and running a fetch

We are now ready to declare and run fetches. We need to provide Fetch with a target
monad when we want to execute a fetch. We'll be using `Id` for now, make sure to import
`fetch.implicits._` since Fetch needs an instance of `MonadError[Id, Throwable]` for running
a fetch in the `Id` monad.

Note that Fetch provides `MonadError` instances for a variety of different monads like `Eval` or
`Future` so is likely that you don't have to write your own.

Let's run our first fetch!

```scala
import cats.Id
import fetch.implicits._

val fch: Fetch[User] = getUser(1)

val result: User = Fetch.run[Id](fch)
// Fetching users List(1)

//=> result: User = User(1,@egg_1)
```

In the previous example, we:

- brought the implicit instance of `MonadError[Id, Throwable]` into scope importing `fetch.implicits._`
- created a fetch for a `User` using the `getUser` function
- interpreted the fetch to a `Id[User]` (which is just a `User`) using `Fetch.run`

As you can see, the fetch was executed only in one round to fetch the user and was finished after that.

### Sequencing

When we have two fetches that depend on each other, we can use `flatMap` to combine them. When composing fetches with `flatMap` we are telling Fetch that the second one depends on the previous one, so it isn't able to make any optimizations.

```scala
val fch: Fetch[(User, User)] = for {
  aUser <- getUser(1)
  anotherUser <- getUser(aUser.id + 1)
} yield (aUser, anotherUser)

val result: (User, User) = Fetch.run[Id](fch)
// Fetching users List(1)
// Fetching users List(2)

//=> result: (User, User) = (User(1,@egg_1),User(2,@egg_2))
```

### Batching

If we combine two independent requests to the same data source, Fetch will
automatically batch them together into a single request. Fetch's `join` operator
help us tell the library that two fetches are independent, and thus can be batched
if they use the same data source:

```scala
import cats.syntax.cartesian._

val fch: Fetch[(User, User)] = getUser(1).product(getUser(2))

val result: (User, User) = Fetch.run[Id](fch)
// Fetching users List(1, 2)

//=> result: (User, User) = (User(1,@egg_1),User(2,@egg_2))
```

### Deduplication

If two independent requests ask for the same identity, Fetch will detect that and
deduplicate such id.

```scala
val fch: Fetch[(User, User)] = getUser(1).product(getUser(1))

val result: (User, User) = Fetch.run[Id](fch)
// Fetching users List(1)

//=> result: (User, User) = (User(1,@egg_1),User(1,@egg_1))
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

val result: (User, User) = Fetch.run[Id](fch)
// Fetching users List(1)

//=> result: (User, User) = (User(1,@egg_1),User(1,@egg_1))
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
    Eval.later({
      println(s"Fetching posts $ids")
      ids.map(i => (i, Post(i, i % 3 + 1, s"An article with id $i"))).toMap
    })
  }
}

def getPost(id: PostId): Fetch[Post] = Fetch(id)

implicit object PostInfoSource extends DataSource[PostId, PostInfo]{
  override def fetch(ids: List[PostId]): Eval[Map[PostId, PostInfo]] = {
    Eval.later({
      println(s"Fetching post info $ids")
      ids.map(i => (i, PostInfo(if (i % 2 == 0) "applicative" else "monad"))).toMap
    })
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

val result: (Post, User) = Fetch.run[Id](fch)
// Fetching posts List(1)
// Fetching users List(2)

//=> result: (Post, User) = (Post(1,2,An article with id 1),User(2,@egg_2))
```

In the previous example we fetched a post given its id, and then fetched its author. These
data could come from entirely different places but Fetch makes working with heterogeneous sources
of data very easy.

### Concurrency

Combining multiple independent requests to the same data source can have two outcomes:

 - if the data sources are the same, the request is batched
 - otherwise, both data sources are queried at the same time

In the following example we are fetching from different data sources so both requests will be
evaluated together.

```scala
val fch: Fetch[(Post, User)] = getPost(1).product(getUser(2))

val result: (Post, User) = Fetch.run[Id](fch)
// Fetching posts List(1)
// Fetching users List(2)

//=> result: (Post, User) = (Post(1,2,An article with id 1),User(2,@egg_2))
```

Since we are interpreting the fetch to the `Id` monad, that doesn't give us any parallelism, the fetches
will be run sequentially. However, if we interpret it to a `Future` each request will run in its own logical
thread.

## Combinators

Besides `flatMap` for sequencing fetches and `product` for running them concurrently, Fetch provides a number of
other combinators.

### Sequence

Whenever we have a list of fetches of the same type and want to run them concurrently we can use the `sequence`
combinator. It takes a `List[Fetch[A]]` and gives you back a `Fetch[List[A]]`, batching the fetches to the same
data source and running fetches to different sources in parallel.

Note that the `sequence` combinator is more general and not only works on lists but any type that has a [`Traverse`](http://typelevel.org/cats/tut/traverse.html) instance.

```scala
import cats.std.list._
import cats.syntax.traverse._

val fch: Fetch[List[User]] = List(getUser(1), getUser(2), getUser(3)).sequence

val result: List[User] = Fetch.run[Id](fch)
// Fetching users List(1, 2, 3)

//=> result: List[User] = List(User(1,@egg_1), User(2,@egg_2), User(3,@egg_3))
```

As you can see, requests to the user data source were batched, thus fetching all the data in one round.

### Traverse

Another interesing combinator is `traverse`, which is the composition of `map` and `sequence`.

```scala
val fch: Fetch[List[User]] = List(1, 2, 3).traverse(getUser)

val result: List[User] = Fetch.run[Id](fch)
// Fetching users List(1, 2, 3)

//=> result: List[User] = List(User(1,@egg_1), User(2,@egg_2), User(3,@egg_3))
```

# Interpreting a fetch to an async capable monad

Albeit the examples use `Id` as the target Monad, `Fetch` is not limited to just `Id`, any monad `M` that
implements `MonadError[M, Throwable]` will do. Fetch provides `MonadError` instances for some existing monads like
`Future`, `cats.Id` and `cats.Eval` and is easy to write your own.

In practice you'll be interpreting a fetch to an async capable monad like `Future` or `scalaz.concurrent.Task` to exploit
parallelism whenever we can make requests to multiple independent data sources at the same time.

## Future

For interpreting a fetch into a `Future` we must first import the `MonadError[Future, Throwable]` available in cats.

```scala
import cats.std.future._
```

We can now interpret a fetch into a future:

```scala
import cats.std.future._

import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

val fch: Fetch[(User, Post)] = (getUser(1) |@| getPost(1)).tupled

val fut: Future[(User, Post)] = Fetch.run[Future](fch)
// Fetching users List(1)
// Fetching posts List(1)

Await.result(fut, 1 seconds)
//=> (User, Post) = (User(1,@egg_1),Post(1,2,An article with id 1))
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

val result: User = Fetch.run[Id](fch, cache)
//=> result: User = User(1,@dialelo)
```

As you can see, when all the data is cached no fetch is executed at all since the results are available
in the cache.

If only part of the data is cached, the cached data won't be asked for:

```scala
val fch: Fetch[List[User]] = List(1, 2, 3).traverse(getUser)
val cache = InMemoryCache(UserSource.identity(2) -> User(2, "@two"))

val result: List[User] = Fetch.run[Id](fch, cache)
// Fetching users List(1, 3)

//=> result: List[User] = List(User(1,@egg_1), User(2,@two), User(3,@egg_3))
```

## Replaying a fetch

When running a fetch we are generally interested in its final result. However, we also have access to the cache
and information about the executed rounds once we run a fetch. Fetch's interpreter keeps its state in an environment
(implementing the `Env` trait) and we can get both the environment and result after running a fetch using `Fetch.runFetch`
instead of `Fetch.run`.

Knowing this, we can replay a fetch reusing the cache of a previous one. The replayed fetch won't have to call any of the
data sources.

```scala
val fch: Fetch[List[User]] = List(1, 2, 3).traverse(getUser)

val env: Env = Fetch.runEnv[Id](fch)
// Fetching users List(1, 2, 3)

val result: List[User] = Fetch.run[Id](fch, env.cache)
//=> result: List[User] = List(User(1,@egg_1), User(2,@egg_2), User(3,@egg_3))
```

## Implementing a custom cache

The default cache is implemented as an in-memory map, but users are free to use their own caches when running a fetch. Your cache should implement the `DataSourceCache` trait, and after that you can pass it to Fetch's `run` methods.

```scala
trait DataSourceCache {
  def update[A](k: DataSourceIdentity, v: A): DataSourceCache
  def get(k: DataSourceIdentity): Option[Any]
}
```

Let's reimplement the in memory cache found in Fetch, we'll write a case class that'll store the cache contents in an in-memory immutable map and implement `DataSourceCache`.

```scala
case class MyInMemoryCache(state: Map[DataSourceIdentity, Any]) extends DataSourceCache {
  override def get(k: DataSourceIdentity): Option[Any] =
    state.get(k)

  override def update[A](k: DataSourceIdentity, v: A): MyInMemoryCache =
    copy(state = state.updated(k, v))
}
```

Now that we have a data type, we can use it when running a fetch:

```scala
val cache = MyInMemoryCache(Map(UserSource.identity(1) -> User(1, "dialelo")))
val fch: Fetch[User] = getUser(1)

val result: User = Fetch.run[Id](fch, cache)
//=> result: User = User(1,dialelo)
```

# Error handling

As we mentioned before, when interpreting a fetch to a target monad `M`, an implicit instance of `MonadError[M, Throwable]` has to be
available. [`MonadError`](https://github.com/typelevel/cats/blob/master/core/src/main/scala/cats/MonadError.scala) gives us a few combinators
for working with errors, like `MonadError#raiseError` and `MonadError#attempt`.

One of the most interesting combinators is `attempt`, which given a `M[A]` yields a `M[Xor[Throwable, A]]`. Knowing this, we can run fetches
in the `Eval` monad to an `Xor` and not worry about exceptions:

```scala
import cats.data.Xor
import fetch.implicits.evalMonadError

val fch: Fetch[User] = Fetch.error(new Exception("Oh noes"))

val result: Eval[User] = Fetch.run[Eval](fch)

val safeResult: Eval[Throwable Xor User] = evalMonadError.attempt(result)

val finalValue: Throwable Xor User = safeResult.value
//=> finalValue: cats.data.Xor[Throwable,User] = Left(java.lang.Exception: Oh noes)
```

In the above example we didn't use `Id` since interpreting a fetch to `Id` throws the exceptions and we can't capture it with the
combinators in `MonadError`.

## Missing identities

You probably have noticed that `DataSource.fetch` takes a list of identities and returns a map of identities to their result, taking
into account the possibility of some identities not being found. Whenever an identity isn't found, the fetch execution will
fail.

Whenever a fetch fails, a `FetchFailure` exception is thrown. The `FetchFailure` will have the environment, which gives you information
about the execution of the fetch.

# Syntax

## Companion object

We've been using cats' syntax throughout the examples since its more concise and general than the
methods in the `Fetch` companion object. However, you can use the methods in the companion object
directly.

Note that using cats syntax gives you a plethora of combinators, much richer that what the companion object provides.

### pure

Plain values can be lifted to the Fetch monad with `Fetch#pure`:

```scala
val fch: Fetch[Int] = Fetch.pure(42)

val result: Int = Fetch.run[Id](fch)
//=> result: Int = 42
```

### error

Errors can also be lifted to the Fetch monad, in this case with `Fetch#error`. Note that interpreting
a errorful fetch to `Id` will throw the exception so we won't do that:

```scala
val fch: Fetch[Int] = Fetch.error(new Exception("Something went terribly wrong"))
```

### join

We can compose two independent fetches with `Fetch#join`. If the fetches are to the same data source they will
be batched; if they aren't, they will be evaluated at the same time.

```scala
val fch: Fetch[(Post, User)] = Fetch.join(getPost(1), getUser(2))

val result: (Post, User) = Fetch.run[Id](fch)
// Fetching posts List(1)
// Fetching users List(2)

//=> result: (Post, User) = (Post(1,2,An article with id 1),User(2,@egg_2))
```

### sequence

The `Fetch#sequence` combinator turns a `List[Fetch[A]]` into a `Fetch[List[A]]`, running all the fetches concurrently
and batching when possible.

```scala
val fch: Fetch[List[User]] = Fetch.sequence(List(getUser(1), getUser(2), getUser(3)))

val result: List[User] = Fetch.run[Id](fch)
// Fetching users List(1, 2, 3)

//=> result: List[User] = List(User(1,@egg_1), User(2,@egg_2), User(3,@egg_3))
```

### traverse

The `Fetch#traverse` combinator is a combination of `map` and `sequence`.

```scala
val fch: Fetch[List[User]] = Fetch.traverse(List(1, 2, 3))(getUser)

val result: List[User] = Fetch.run[Id](fch)
// Fetching users List(1, 2, 3)

//=> result: List[User] = List(User(1,@egg_1), User(2,@egg_2), User(3,@egg_3))
```

## cats

Fetch is built using cats' Free monad construction and thus works out of the box with
cats syntax. Using cats' syntax we can make fetch declarations more concise, without
the need to use the combinators in the `Fetch` companion object.

### Apply

The `|@|` operator allow us to combine multiple independent fetches, even when they
are from different types, and apply a pure function to their results. We can use it
as a more powerful alternative to the `product` method or `Fetch#join`:

```scala
import cats.syntax.cartesian._

val fch: Fetch[(Post, User, Post)] = (getPost(1) |@| getUser(2) |@| getPost(2)).tupled

val result: (Post, User, Post) = Fetch.run[Id](fch)
// Fetching posts List(1, 2)
// Fetching users List(2)

//=> result: (Post, User, Post) = (Post(1,2,An article with id 1),User(2,@egg_2),Post(2,3,An article with id 2))
```

More interestingly, we can use it to apply a pure function to the results of various
fetches.

```scala
import cats.syntax.cartesian._

val fch: Fetch[String] = (getUser(1) |@| getUser(2)).map({ (one, other) =>
  s"${one.username} is friends with ${other.username}"
})

val result: String = Fetch.run[Id](fch)
// Fetching users List(1, 2)

//=> result: String = @egg_1 is friends with @egg_2
```

The avove example is equivalent to the following using the `Fetch#join` method:

```scala
import cats.syntax.cartesian._

val fch: Fetch[String] = Fetch.join(getUser(1), getUser(2)).map({ case (one, other) =>
  s"${one.username} is friends with ${other.username}"
})

val result: String = Fetch.run[Id](fch)
// Fetching users List(1, 2)

//=> result: String = @egg_1 is friends with @egg_2
```
