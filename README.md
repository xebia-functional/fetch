# Fetch

Fetch is still a work in progress.

A library for efficient, concurrent, and concise data access in Scala.

## Installation

## Usage

First and foremost, import the `fetch` package.

```scala
import fetch._
```

The abstraction for remote data sources is the `DataSource` trait, every remote data
source must implement it. Data sources must have a unique identity, used for deduplication
and caching, and must define how to perform the fetch.

Let's implement our first data source, a call for getting a tweet based on its ID.

```scala
import scala.concurrent._

case class Tweet(id: Int, content: String)

case class GetTweet(id: Int) extends DataSource[Tweet] {
  def identity = id.toString
  def fetch = {
    println("Fetching a tweet with id " + id) 
    Future.successful(Tweet(id, "A tweet with id " + id))
  }
}
```

There's not a whole lot we can do with data sources, we can lift a `DataSource` to the `Fetch`
type.

```scala
val fetch: Fetch[Tweet] = Fetch(GetTweet(1))
```

Once we get the `Fetch` instance, we can run it. It returns a future:

```scala
Fetch.run(fetch).value
// Fetching a tweet with id 1
// => Some(Success(Tweet(1, A tweet with id 1)))
```

### Caching

Separating fetch declaration from execution allows us to make many optimizations, the first of which
is caching. Since we know the identity of the data sources and we want to guarantee the coherence of
data during the lifetime of a fetch, every fetched value is cached.

Note how the `"Fetching a tweet with id 1"` is only printed once, because the `fetch` method of the
tweet is only called once:

```scala
val fetch = for {
  tweet <- Fetch(GetTweet(1))
  tweet <- Fetch(GetTweet(1))
  tweet <- Fetch(GetTweet(1))
} yield tweet

Fetch.run(fetch).value
// Fetching a tweet with id 1
// => Some(Success(Tweet(1, A tweet with id 1)))
```

### Deduplication

Apart from caching, the data sources are deduplicated before calling their fetch methods. Let's see
it in action collecting a list of duplicate tweets:

```scala
val fetch = Fetch.collect(
  List(
    Fetch(GetTweet(1)),
    Fetch(GetTweet(2)),
    Fetch(GetTweet(1))
  )
)

val fut = Fetch.run(fetch)
// Fetching a tweet with id 1
// Fetching a tweet with id 2
fut.value
// => Some(Success(List(Tweet(1,A tweet with id 1), Tweet(2,A tweet with id 2), Tweet(1,A tweet with id 1))))
```

### Batching

In the previous example, when collecting a list of tweets, we fetch separate tweets concurrently. Oftentimes remote
data sources have batch APIs that we can use for fetching multiple results at once, considerably reducing latency.

If your data source can take advantage of batch APIs make sure to override the `fetchMulti` method of the `DataSource`
trait. Let's see how we take advantage of batch APIs to reduce the number of calls. First of all, we modify our `GetTweet`
data source to implement `fetchMulti`.

```scala
case class GetTweet(id: Int) extends DataSource[Tweet] {
  def identity = id.toString
  def fetch = {
    println("Fetching a tweet with id " + id) 
    Future.successful(Tweet(id, "A tweet with id " + id))
  }
  override def fetchMulti(sources: List[DataSource[Tweet]]): Future[List[Tweet]] = {
    val getTweets = sources.asInstanceOf[List[GetTweet]]
    val tweetIds = getTweets.map(_.id)
    println("Fetching tweets with ids " + tweetIds)
	Future.successful(tweetIds.map(id => Tweet(id, "A tweet with id " + id)))
  }
}
```

Now that tweets can be fetched in batch, note how `fetchMulti` is used instead of fetching them one by one.

```scala
val fetch = Fetch.collect(
  List(
    Fetch(GetTweet(1)),
    Fetch(GetTweet(2)),
    Fetch(GetTweet(1))
  )
)

val fut = Fetch.run(fetch)
// Fetching tweets with ids List(1, 2)
fut.value
// => Some(Success(List(Tweet(1,A tweet with id 1), Tweet(2,A tweet with id 2), Tweet(1,A tweet with id 1))))
```

## License

TODO



