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
"com.47deg" %% "fetch" % "@VERSION@"
```

Or, if using Scala.js:

```scala
"com.47deg" %%% "fetch" % "@VERSION@"
```

Now you'll have Fetch available in both Scala and Scala.js.

```scala mdoc:invisible
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

If something is missing in Fetch that stops you from using it we'd appreciate if you [open an issue in our repository](https://github.com/47degrees/fetch/issues).

# Usage

In order to tell Fetch how to retrieve data, we must implement the `DataSource` typeclass. The `Data` typeclass is to tell Fetch which kind of data the source is fetching, and is used to optimize requests for the same data.

```scala
import cats.effect.Concurrent
import cats.data.NonEmptyList

trait DataSource[F[_], Identity, Result]{
  def data: Data[Identity, Result]

  def CF: Concurrent[F]

  def fetch(id: Identity): F[Option[Result]]
  
  /* `batch` is implemented in terms of `fetch` by default */
  def batch(ids: NonEmptyList[Identity]): F[Map[Identity, Result]]
}
```

It takes two type parameters:

 - `Identity`: the identity we want to fetch (a `UserId` if we were fetching users)
 - `Result`: the type of the data we retrieve (a `User` if we were fetching users)

There are two methods: `fetch` and `batch`. `fetch` receives one identity and must return
a `Concurrent` containing
an optional result. Returning an `Option` Fetch can detect whether an identity couldn't be fetched or no longer exists.

The `batch` method takes a non-empty list of identities and must return a `Concurrent` containing
a map from identities to results. Accepting a list of identities gives Fetch the ability to batch requests to
the same data source, and returning a mapping from identities to results, Fetch can detect whenever an identity
couldn't be fetched or no longer exists.

The `data` method returns a `Data[Identity, Result]` instance that Fetch uses to optimize requests to the same data source, and is expected to return a singleton `object` that extends `Data[Identity, Result]`.

## Writing your first data source

Now that we know about the `DataSource` typeclass, let's write our first data source! We'll start by implementing a data
source for fetching users given their id. The first thing we'll do is define the types for user ids and users.

```scala mdoc:silent
type UserId = Int
case class User(id: UserId, username: String)
```

We'll simulate unpredictable latency with this function.

```scala mdoc:silent
import fetch._
import cats.implicits._
import cats.effect._
import cats.effect.std.Console
import scala.concurrent.duration._

def latency[F[_]: Console: Temporal](msg: String): F[Unit] = for {
  _ <- Console[F].println(s"--> [${Thread.currentThread.getId}] $msg")
  _ <- Temporal[F].sleep(100.milliseconds)
  _ <- Console[F].println(s"<-- [${Thread.currentThread.getId}] $msg")
} yield ()
```

And now we're ready to write our user data source; we'll emulate a database with an in-memory map.

```scala mdoc:silent
import cats.data.NonEmptyList

val userDatabase: Map[UserId, User] = Map(
  1 -> User(1, "@one"),
  2 -> User(2, "@two"),
  3 -> User(3, "@three"),
  4 -> User(4, "@four")
)

object Users extends Data[UserId, User] {
  def name = "Users"

  def source[F[_]: Console: Temporal]: DataSource[F, UserId, User] = new DataSource[F, UserId, User] {
    override def data = Users

    override def CF = Concurrent[F]

    override def fetch(id: UserId): F[Option[User]] =
      latency[F](s"One User $id") >> CF.pure(userDatabase.get(id))

    override def batch(ids: NonEmptyList[UserId]): F[Map[UserId, User]] =
      latency[F](s"Batch Users $ids") >> CF.pure(userDatabase.view.filterKeys(ids.toList.toSet).toMap)
  }
}
```

Now that we have a data source we can write a function for fetching users
given a `DataSource`, an id and the data source as arguments to `Fetch`.

```scala mdoc:silent
def getUser[F[_]: Console: Temporal](id: UserId): Fetch[F, User] =
  Fetch(id, Users.source)
```

### Optional identities

If you want to create a Fetch that doesn't fail if the identity is not found, you can use `Fetch#optional` instead of `Fetch#apply`. Note that instead of a `Fetch[F, A]` you will get a `Fetch[F, Option[A]]`.

```scala mdoc:silent
def maybeGetUser[F[_]: Console: Temporal](id: UserId): Fetch[F, Option[User]] =
  Fetch.optional(id, Users.source)
```

### Data sources that don't support batching

If your data source doesn't support batching, you can simply leave the `batch` method unimplemented. Note that it will use the `fetch` implementation for requesting identities in parallel.

```scala mdoc:silent
object Unbatched extends Data[Int, Int] {
  def name = "Unbatched"

  def source[F[_]: Console: Temporal]: DataSource[F, Int, Int] = new DataSource[F, Int, Int]{
    override def data = Unbatched

    override def CF = Concurrent[F]

    override def fetch(id: Int): F[Option[Int]] =
      CF.pure(Option(id))
  }
}
```

#### Batching individuals requests sequentially

The default `batch` implementation run requests to the data source in parallel, but you can easily override it. We can make `batch` sequential using `NonEmptyList.traverse` for fetching individual identities.

```scala
object UnbatchedSeq extends Data[Int, Int]{
  def name = "UnbatchedSeq"

  //Normally you only need F[_]: Concurrent; other examples use Console and Temporal due to the `latency` function.
  def source[F[_]: Concurrent]: DataSource[F, Int, Int] = new DataSource[F, Int, Int]{
    override def data = UnbatchedSeq

    override def CF = Concurrent[F]

    override def fetch(id: Int): F[Option[Int]] =
      CF.pure(Option(id))

    override def batch(ids: NonEmptyList[Int]): F[Map[Int, Int]] =
      ids.traverse(
        (id) => fetch(id).map(v => (id, v))
      ).map(_.collect { case (i, Some(x)) => (i, x) }.toMap)
  }
}
```


### Data sources that only support batching

If your data source only supports querying it in batches, you can implement `fetch` in terms of `batch`.

```scala mdoc:silent
object OnlyBatched extends Data[Int, Int]{
  def name = "OnlyBatched"

  def source[F[_]: Concurrent]: DataSource[F, Int, Int] = new DataSource[F, Int, Int]{
    override def data = OnlyBatched

    override def CF = Concurrent[F]

    override def fetch(id: Int): F[Option[Int]] =
      batch(NonEmptyList(id, List())).map(_.get(id))

    override def batch(ids: NonEmptyList[Int]): F[Map[Int, Int]] =
      CF.pure(ids.map(x => (x, x)).toList.toMap)
  }
}
```

## Creating a runtime

Since we'll use `IO` from the `cats-effect` library to execute our fetches, we'll need an `IORuntime` for executing our `IO` instances.

```scala mdoc:silent
import cats.effect.unsafe.implicits.global //Give
```

Normally, in your applications, this is provided by `IOApp`, and you should not need to import this except in limited scenarios such as test environments that do not have Cats Effect integration.
For more information, and particularly on why you would usually not want to make one of these yourself, [see this post by Daniel Spiewak](https://github.com/typelevel/cats-effect/discussions/1562#discussioncomment-254838)

## Creating and running a fetch

We are now ready to create and run fetches. Note the distinction between Fetch creation and execution.
When we are creating and combining `Fetch` values, we are just constructing a recipe of our data
dependencies.

```scala mdoc:silent
def fetchUser[F[_]: Console: Temporal]: Fetch[F, User] =
  getUser(1)
```

A `Fetch` is just a value, and in order to be able to get its value we need to run it to an `IO` first. 

```scala mdoc:silent
import cats.effect.IO

Fetch.run[IO](fetchUser)
```

We can now run the `IO` and see its result:

```scala mdoc
Fetch.run[IO](fetchUser).unsafeRunTimed(5.seconds)
```

### Sequencing

When we have two fetches that depend on each other, we can use `flatMap` to combine them. The most straightforward way is to use a for comprehension:

```scala mdoc:silent
def fetchTwoUsers[F[_]: Console: Temporal]: Fetch[F, (User, User)] = for {
  aUser <- getUser(1)
  anotherUser <- getUser(aUser.id + 1)
} yield (aUser, anotherUser)
```

When composing fetches with `flatMap` we are telling Fetch that the second one depends on the previous one, so it isn't able to make any optimizations. When running the above fetch, we will query the user data source in two rounds: one for the user with id 1 and another for the user with id 2.

```scala mdoc
Fetch.run[IO](fetchTwoUsers).unsafeRunTimed(5.seconds)
```

### Batching

If we combine two independent requests to the same data source, Fetch will
automatically batch them together into a single request. Applicative operations like the product of two fetches
help us tell the library that those fetches are independent, and thus can be batched if they use the same data source:

```scala mdoc:silent
def fetchProduct[F[_]: Console: Temporal]: Fetch[F, (User, User)] =
  (getUser(1), getUser(2)).tupled
```

Note how both ids (1 and 2) are requested in a single query to the data source when executing the fetch.

```scala mdoc
Fetch.run[IO](fetchProduct).unsafeRunTimed(5.seconds)
```

### Deduplication

If two independent requests ask for the same identity, Fetch will detect it and deduplicate the id.

```scala mdoc:silent
def fetchDuped[F[_]: Console: Temporal]: Fetch[F, (User, User)] =
  (getUser(1), getUser(1)).tupled
```

Note that when running the fetch, the identity 1 is only requested once even when it is needed by both fetches.

```scala mdoc
Fetch.run[IO](fetchDuped).unsafeRunTimed(5.seconds)
```

### Caching

During the execution of a fetch, previously requested results are implicitly cached. This allows us to write
fetches in a very modular way, asking for all the data they need as if it
was in memory; furthermore, it also avoids re-fetching an identity that may have changed
during the course of a fetch execution, which can lead to inconsistencies in the data.

```scala mdoc:silent
def fetchCached[F[_]: Console: Temporal]: Fetch[F, (User, User)] = for {
  aUser <- getUser(1)
  anotherUser <- getUser(1)
} yield (aUser, anotherUser)
```

The above fetch asks for the same identity multiple times. Let's see what happens when executing it.

```scala mdoc
Fetch.run[IO](fetchCached).unsafeRunTimed(5.seconds)
```

As you can see, the `User` with id 1 was fetched only once in a single round-trip. The next
time it was needed we used the cached versions, thus avoiding another request to the user data
source.

## Combining data from multiple sources

Now that we know about some of the optimizations that Fetch can perform to read data efficiently,
let's look at how we can combine more than one data source.


Imagine that we are rendering a blog and have the following types for posts:

```scala mdoc:silent
type PostId = Int
case class Post(id: PostId, author: UserId, content: String)
```

As you can see, every `Post` has an author, but it refers to the author by its id. We'll implement a data source for retrieving a post given a post id.

```scala mdoc:silent
val postDatabase: Map[PostId, Post] = Map(
  1 -> Post(1, 2, "An article"),
  2 -> Post(2, 3, "Another article"),
  3 -> Post(3, 4, "Yet another article")
)

object Posts extends Data[PostId, Post] {
  def name = "Posts"

  //Calls to `latency` need to sleep and use the console, so we need Console and Temporal (which extends Concurrent) instances
  def source[F[_]: Console: Temporal]: DataSource[F, PostId, Post] = new DataSource[F, PostId, Post] {
    override def data = Posts 

    override def CF = Concurrent[F]

    override def fetch(id: PostId): F[Option[Post]] =
      latency[F](s"One Post $id") >> CF.pure(postDatabase.get(id))

    override def batch(ids: NonEmptyList[PostId]): F[Map[PostId, Post]] =
      latency[F](s"Batch Posts $ids") >> CF.pure(postDatabase.view.filterKeys(ids.toList.toSet).toMap)
  }
}

def getPost[F[_]: Console: Temporal](id: PostId): Fetch[F, Post] =
  Fetch(id, Posts.source)
```

Apart from posts, we are going to add another data source: one for post topics.

```scala mdoc:silent
type PostTopic = String
```

We'll implement a data source for retrieving a post topic given a post id.

```scala mdoc:silent
object PostTopics extends Data[Post, PostTopic] {
  def name = "Post Topics"

  def source[F[_]: Console: Temporal]: DataSource[F, Post, PostTopic] = new DataSource[F, Post, PostTopic] {
    override def data = PostTopics 

    override def CF = Concurrent[F]

    override def fetch(id: Post): F[Option[PostTopic]] = {
      val topic = if (id.id % 2 == 0) "monad" else "applicative"
      latency[F](s"One Post Topic $id") >> CF.pure(Option(topic))
    }

    override def batch(ids: NonEmptyList[Post]): F[Map[Post, PostTopic]] = {
      val result = ids.toList.map(id => (id, if (id.id % 2 == 0) "monad" else "applicative")).toMap
      latency[F](s"Batch Post Topics $ids") >> CF.pure(result)
    }
  }
}

def getPostTopic[F[_]: Console: Temporal](post: Post): Fetch[F, PostTopic] =
  Fetch(post, PostTopics.source)
```

Now that we have multiple sources let's mix them in the same fetch.

```scala mdoc:silent
def fetchMulti[F[_]: Console: Temporal]: Fetch[F, (Post, PostTopic)] = for {
  post <- getPost(1)
  topic <- getPostTopic(post)
} yield (post, topic)
```

We can now run the previous fetch, querying the posts data source first and the user data source afterwards.

```scala mdoc
Fetch.run[IO](fetchMulti).unsafeRunTimed(5.seconds)
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

```scala mdoc:silent
def fetchConcurrent[F[_]: Console: Temporal]: Fetch[F, (Post, User)] =
  (getPost(1), getUser(2)).tupled
```

The above example combines data from two different sources, and the library knows they are independent.

```scala mdoc
Fetch.run[IO](fetchConcurrent).unsafeRunTimed(5.seconds)
```

## Auto-batching

Fetch supports automatically batching multiple fetch requests in sequence using various combinators.
This means that if you make multiple requests at once using combinators from Cats such as `.sequence` or `.traverse` you will get your requests as fast as possible, every time.

In Fetch 2.x and 3.1.x, calls to `sequence` or `traverse` on sequences of fetches will automatically try to batch or run fetches concurrently where possible.
However, in Fetch 3.0.0, we briefly went in the direction of not guaranteeing batches on sequences and introducing explicit batching support to work around this.
In hindsight, we felt that those ideas would be best explored in other projects and have decided to revert behavior to the way it was in 2.x, but keeping the new syntax added so as to not break projects.

Here is an example showing how to batch fetches using `Fetch.batchAll`

```scala mdoc:silent
val listOfFetches = List(1, 2, 3).map(getPost[IO])
val batchedList: Fetch[IO, List[Post]] = Fetch.batchAll(listOfFetches: _*)
```

You can also use helpful syntax by importing `fetch.syntax._` for batching sequences, like so:

```scala mdoc:silent
import fetch.syntax._

//Takes a sequence of fetches and batches them
val batchedListWithSyntax = listOfFetches.batchAll

//Allows you to supply your own function to batch a sequence as fetches
val listToBatchWithSyntax = List(1, 2, 3).batchAllWith(id => getPost[IO](id))
```

Underneath, `.batchAll` and its siblings are synonymous with the methods from Cats named `.sequence` or `.traverse`, but converting the final result to a `List` explicitly afterward.
If you currently use `.sequence` or `.traverse`, you will automatically batch a sequence of fetches as always, and `.batchAll` is there for less cats-heavy usage.

# Caching

As we have learned, Fetch caches intermediate results implicitly. You can
provide a prepopulated cache for running a fetch, replay a fetch with the cache of a previous
one, and even implement a custom cache.

## Prepopulating a cache

We'll be using the default in-memory cache, prepopulated with some data. The cache key of an identity
is calculated with the `DataSource`'s `name` method and the request identity.

```scala mdoc:silent
def cache[F[_]: Concurrent] = InMemoryCache.from[F, UserId, User](
 (Users, 1) -> User(1, "purrgrammer")
)
```

We can pass a cache as the second argument when running a fetch with `Fetch.run`.

```scala mdoc
Fetch.run[IO](fetchUser, cache).unsafeRunTimed(5.seconds)
```

As you can see, when all the data is cached, no query to the data sources is executed since the results are available
in the cache.

```scala mdoc:silent
def fetchManyUsers[F[_]: Console: Temporal]: Fetch[F, List[User]] =
  List(1, 2, 3).traverse(getUser[F])
```

If only part of the data is cached, the cached data won't be asked for:

```scala mdoc
Fetch.run[IO](fetchManyUsers, cache).unsafeRunTimed(5.seconds)
```

## Replaying a fetch without querying any data source

When running a fetch, we are generally interested in its final result. However, we also have access to the cache once we run a fetch. We can get both the cache and the result using `Fetch.runCache` instead of `Fetch.run`.

Knowing this, we can replay a fetch reusing the cache of a previous one. The replayed fetch won't have to call any of the
data sources.

```scala mdoc
val (populatedCache, result) = Fetch.runCache[IO](fetchManyUsers).unsafeRunSync()

Fetch.run[IO](fetchManyUsers, populatedCache).unsafeRunTimed(5.seconds)
```

## Implementing a custom cache

The default cache is implemented as an immutable in-memory map, but users are free to use their own caches when running a fetch. Your cache should implement the `DataCache` trait, and after that you can pass it to Fetch's `run` methods.

There is no need for the cache to be mutable since fetch executions run in an interpreter that uses the state monad. Note that the `update` method in the `DataCache` trait yields a new, updated cache.

```scala
trait DataCache[F[_]] {
  def insert[I, A](i: I, v: A, d: Data[I, A]): F[DataCache[F]]
  def lookup[I, A](i: I, d: Data[I, A]): F[Option[A]]
}
```

Let's implement a cache that forgets everything we store in it.

```scala mdoc:silent
import cats.{Applicative, Monad}

case class ForgetfulCache[F[_] : Monad]() extends DataCache[F] {
  def insert[I, A](i: I, v: A, d: Data[I, A]): F[DataCache[F]] =
    Applicative[F].pure(this)

  def lookup[I, A](i: I, ds: Data[I, A]): F[Option[A]] =
    Applicative[F].pure(None)
}

def forgetfulCache[F[_]: Concurrent] = ForgetfulCache[F]()
```

We can now use our implementation of the cache when running a fetch.

```scala mdoc
def fetchSameTwice[F[_]: Console: Temporal]: Fetch[F, (User, User)] = for {
  one <- getUser(1)
  another <- getUser(1)
} yield (one, another)

Fetch.run[IO](fetchSameTwice, forgetfulCache).unsafeRunTimed(5.seconds)
```

# Batching

As we have learned, Fetch performs batched requests whenever it can. It also exposes a couple knobs
for tweaking the maximum batch size and whether multiple batches are run in parallel or sequentially.

## Maximum batch size

When implementing a `DataSource`, there is a method we can override called `maxBatchSize`. When implementing it
we can specify the maximum size of the batched requests to this data source, let's try it out:

```scala mdoc:silent
object BatchedUsers extends Data[UserId, User]{
  def name = "Batched Users"

  def source[F[_]: Console: Temporal]: DataSource[F, UserId, User] = new DataSource[F, UserId, User] {
    override def data = BatchedUsers

    override def CF = Concurrent[F]

    override def maxBatchSize: Option[Int] = Some(2)

    override def fetch(id: UserId): F[Option[User]] =
      latency[F](s"One User $id") >> CF.pure(userDatabase.get(id))

    override def batch(ids: NonEmptyList[UserId]): F[Map[UserId, User]] =
      latency[F](s"Batch Users $ids") >> CF.pure(userDatabase.view.filterKeys(ids.toList.toSet).toMap)
  }
}

def getBatchedUser[F[_]: Console: Temporal](id: Int): Fetch[F, User] =
  Fetch(id, BatchedUsers.source)
```

We have defined the maximum batch size to be 2, let's see what happens when running a fetch that needs more
than two users:

```scala mdoc
def fetchManyBatchedUsers[F[_]: Console: Temporal]: Fetch[F, List[User]] =
  List(1, 2, 3, 4).traverse(getBatchedUser[F])

Fetch.run[IO](fetchManyBatchedUsers).unsafeRunTimed(5.seconds)
```

## Batch execution strategy

In the presence of multiple concurrent batches, we can choose between a sequential or parallel execution strategy. By default batches will be run in parallel, but you can tweak this behaviour by overriding `DataSource#batchExection`.

```scala mdoc:silent
object SequentialUsers extends Data[UserId, User]{
  def name = "Sequential Users"

  def source[F[_]: Console: Temporal]: DataSource[F, UserId, User] = new DataSource[F, UserId, User] {
    override def data = SequentialUsers

    override def CF = Concurrent[F]

    override def maxBatchSize: Option[Int] = Some(2)
    override def batchExecution: BatchExecution = Sequentially // defaults to `InParallel`

    override def fetch(id: UserId): F[Option[User]] =
      latency[F](s"One User $id") >> CF.pure(userDatabase.get(id))

    override def batch(ids: NonEmptyList[UserId]): F[Map[UserId, User]] =
      latency[F](s"Batch Users $ids") >> CF.pure(userDatabase.view.filterKeys(ids.toList.toSet).toMap)
  }
}

def getSequentialUser[F[_]: Console: Temporal](id: Int): Fetch[F, User] =
  Fetch(id, SequentialUsers.source)
```

We have defined the maximum batch size to be 2 and the batch execution to be sequential, let's see what happens when running a fetch that needs more than one batch:


```scala mdoc
def fetchManySeqBatchedUsers[F[_]: Console: Temporal]: Fetch[F, List[User]] =
  List(1, 2, 3, 4).traverse(getSequentialUser[F])

Fetch.run[IO](fetchManySeqBatchedUsers).unsafeRunTimed(5.seconds)
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

```scala mdoc:silent
def fetchException[F[_]: Applicative]: Fetch[F, User] =
  Fetch.error(new Exception("Oh noes"))
```

If we try to execute to `IO` the exception will be thrown wrapped in a `fetch.UnhandledException`.

```scala mdoc:crash
Fetch.run[IO](fetchException).unsafeRunTimed(5.seconds)
```

A safer version would use Cats' `.attempt` method:

```scala mdoc
Fetch.run[IO](fetchException).attempt.unsafeRunTimed(5.seconds)
```

### Debugging exceptions

Using fetch's debugging facilities, we can visualize a failed fetch's execution up until the point where it failed. Let's create
a fetch that fails after a couple rounds to see it in action:

```scala mdoc:silent
def failingFetch[F[_]: Console: Temporal]: Fetch[F, String] = for {
  a <- getUser(1)
  b <- getUser(2)
  c <- fetchException[F]
} yield s"${a.username} loves ${b.username}"

val result2: IO[Either[Throwable, (Log, String)]] = Fetch.runLog[IO](failingFetch).attempt
```

Now let's use the `fetch.debug.describe` function for describing the error if we find one:

```scala mdoc
import fetch.debug.describe

val value: Either[Throwable, (Log, String)] = result2.unsafeRunSync()

println(value.fold(describe, _.toString))
```

As you can see in the output from `describe`, the fetch stopped due to a `java.lang.Exception` after succesfully executing two
rounds for getting users 1 and 2.

## Missing identities

You've probably noticed that `DataSource.fetch` and `DataSource.batch` return types help Fetch know if any requested
identity was not found. Whenever an identity cannot be found, the fetch execution will fail with an instance of `MissingIdentity`.

```scala mdoc:silent
def missingUser[F[_]: Console: Temporal] =
  getUser(5)

val result3: IO[Either[Throwable, (Log, User)]] = Fetch.runLog[IO](missingUser).attempt
```

And now we can execute the fetch and describe its execution:

```scala mdoc
val value2: Either[Throwable, (Log, User)] = result3.unsafeRunSync()

println(value2.fold(describe, _.toString))
```

As you can see in the output, the identity `5` for the user source was not found, thus the fetch failed without executing any rounds.
`MissingIdentity` also allows you to access the fetch request that was in progress when the error happened.

```scala mdoc
value2 match {
  case Left(mi @ MissingIdentity(id, q, log)) => {
    println("Data: " + q.data.name)
    println("Identity: " + id)

    println(describe(log))
  }
  case _ =>
}
```

# Syntax

## Companion object

We've been using Cats' syntax and `fetch.syntax` throughout the examples since it's more concise and general than the
methods in the `Fetch` companion object. However, you can use the methods in the companion object
directly.

Note that using cats syntax gives you a plethora of combinators, much richer that what the companion object provides.

### pure

Plain values can be lifted to the Fetch monad with `Fetch#pure`:

```scala mdoc:silent
def fetchPure[F[_]: Applicative]: Fetch[F, Int] =
  Fetch.pure(42)
```

Executing a pure fetch doesn't query any data source, as expected.

```scala mdoc
Fetch.run[IO](fetchPure).unsafeRunTimed(5.seconds)
```

### error

Errors can also be lifted to the Fetch monad via `Fetch#error`.

```scala mdoc:silent
def fetchFail[F[_]: Applicative]: Fetch[F, Int] =
  Fetch.error(new Exception("Something went terribly wrong"))
```

Note that interpreting an errorful fetch can throw an exception.

```scala mdoc:crash
Fetch.run[IO](fetchFail).unsafeRunTimed(5.seconds)
```

### batchAll

The `Fetch.batchAll` function can be ran on any `Seq[Fetch[F, A]]` to turn it into a `Fetch[F, List[A]]`.
It works similarly to calling `.sequence` on a sequence of fetches, only it tries to batch them where possible.
You can also use `.batchAllWith` which works similarly to `.traverse` in that it works just like `.map` followed by `.batchAll`.

```scala mdoc:silent
//Longer, manual syntax for batching lists of fetches
val batchAllManual = Fetch.batchAll(List(1, 2, 3).map(getPost[IO]): _*)

//Handy, smaller syntax directly on your list that works like .sequence
val batchAllWithSyntax = List(1, 2, 3).map(getPost[IO]).batchAll

//Similar syntax that works like .traverse, allowing you to pass a function
val batchAllDifferentSyntax = List(1, 2, 3).batchAllWith(getPost[IO])
```

## cats

Fetch is built using Cats' data types and typeclasses and thus works out of the box with
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
as a more powerful alternative to the `product` method:

```scala mdoc:silent
def fetchThree[F[_]: Console: Temporal]: Fetch[F, (Post, User, Post)] =
  (getPost(1), getUser(2), getPost(2)).tupled
```

Notice how the queries to posts are batched.

```scala mdoc
Fetch.run[IO](fetchThree).unsafeRunTimed(5.seconds)
```

More interestingly, we can use it to apply a pure function to the results of various
fetches.

```scala mdoc
def fetchFriends[F[_]: Console: Temporal]: Fetch[F, String] = (getUser(1), getUser(2)).mapN { (one, other) =>
  s"${one.username} is friends with ${other.username}"
}

Fetch.run[IO](fetchFriends).unsafeRunTimed(5.seconds)
```

# Debugging

We have introduced the handy `fetch.debug.describe` function for debugging errors, but it can do more than that. It can also give you a detailed description of
a fetch execution given an execution log.

Add the following line to your dependencies for including Fetch's debugging facilities:

```scala
"com.47deg" %% "fetch-debug" % "@VERSION@"
```

## Fetch execution

We are going to create an interesting fetch that applies all the optimizations available (caching, batching and concurrent request) for ilustrating how we can
visualize fetch executions using the execution log.

```scala mdoc:silent
def batched[F[_]: Console: Temporal]: Fetch[F, List[User]] =
  List(1, 2).traverse(getUser[F])

def cached[F[_]: Console: Temporal]: Fetch[F, User] =
  getUser(2)

def notCached[F[_]: Console: Temporal]: Fetch[F, User] =
  getUser(4)

def concurrent[F[_]: Console: Temporal]: Fetch[F, (List[User], List[Post])] =
  (List(1, 2, 3).traverse(getUser[F]), List(1, 2, 3).traverse(getPost[F])).tupled

def interestingFetch[F[_]: Console: Temporal]: Fetch[F, String] =
  batched >> cached >> notCached >> concurrent >> Fetch.pure("done")
```

Now that we have the fetch let's run it, get the log and visualize its execution using the `describe` function:

```scala mdoc
val io = Fetch.runLog[IO](interestingFetch)

val (log, result4) = io.unsafeRunSync()

io.unsafeRunTimed(5.seconds) match {
 case Some((log, _)) => println(describe(log))
 case None => println("Unable to run fetch")
}
```

Let's break down the output from `describe`:

 - The first line shows the total time that took to run the fetch
 - The nested lines represent the different rounds of execution
  + "Fetch one" rounds are executed for getting an identity from one data source
  + "Batch" rounds are executed for getting a batch of identities from one data source

# Resources

- [Code](https://github.com/47degrees/fetch) on GitHub.
- [Documentation site](https://47degrees.github.io/fetch/)
- [Fetch: Simple & Efficient data access](https://www.youtube.com/watch?v=45fcKYFb0EU) talk at [Typelevel Summit in Oslo](http://typelevel.org/event/2016-05-summit-oslo/)

# Acknowledgements

Fetch stands on the shoulders of giants:

- [Haxl](https://github.com/facebook/haxl) is Facebook's implementation (Haskell) of the [original paper Fetch is based on](http://community.haskell.org/~simonmar/papers/haxl-icfp14.pdf).
- [Clump](https://github.com/getclump/clump) has inspired the signature of the `DataSource#fetch*` methods.
- [Stitch](https://engineering.twitter.com/university/videos/introducing-stitch) is an in-house Twitter library that is not open source but has inspired Fetch's high-level API.
- [Cats](http://typelevel.org/cats/), a library for functional programming in Scala.
- [Monix](https://monix.io) high-performance and multiplatform (Scala / Scala.js) asynchronous programming library.