package fetch.twitterFuture

import cats._, data._
import cats.implicits._
import fetch._
import io.catbird.util._
import org.scalatest._
import com.twitter.util.{Await, Future}
import com.twitter.conversions.time._

class FetchRerunnableSpec extends FlatSpec with Matchers {

  import ShardedClientDataSource._

  case class ArticleId(id: Int)
  case class Article(id: Int, content: String) {
    def author: Int = id + 1
  }

  implicit object ArticleFuture extends DataSource[ArticleId, Article] {
    override def name = "ArticleFuture"
    override def fetchOne(id: ArticleId): Query[Option[Article]] =
      Query.async((ok, fail) ⇒ {
        ok(Option(Article(id.id, "An article with id " + id.id)))
      })
    override def fetchMany(ids: NonEmptyList[ArticleId]): Query[Map[ArticleId, Article]] =
      batchingNotSupported(ids)
  }

  def article(id: Int): Fetch[Article] = Fetch(ArticleId(id))

  case class AuthorId(id: Int)
  case class Author(id: Int, name: String)

  implicit object AuthorFuture extends DataSource[AuthorId, Author] {
    override def name = "AuthorFuture"
    override def fetchOne(id: AuthorId): Query[Option[Author]] =
      Query.async((ok, fail) ⇒ {
        ok(Option(Author(id.id, "@egg" + id.id)))
      })
    override def fetchMany(ids: NonEmptyList[AuthorId]): Query[Map[AuthorId, Author]] =
      batchingNotSupported(ids)
  }

  def author(a: Article): Fetch[Author] = Fetch(AuthorId(a.author))

  "TwFutureMonadError" should "execute an async fetch on a Future" in {
    val fetch: Fetch[Article] = Fetch(ArticleId(1))
    val article: Future[Article] = Fetch.run[Future](fetch)
    Await.result(article, 100.milliseconds) shouldEqual (Article(1, "An article with id 1"))
  }
  it should "allow for several async datasources to be combined" in {
    val fetch: Fetch[(Article, Author)] = for {
      art ← article(1)
      author ← author(art)
    } yield (art, author)

    Await.result(Fetch.run[Future](fetch), 100.milliseconds) shouldEqual (Article(1, "An article with id 1"), Author(2, "@egg2"))
  }

  "RerunnableMonadError" should "lift and execute an async fetch into a Rerunnable" in {
    val fetch: Fetch[Article] = Fetch(ArticleId(1))
    val article: Rerunnable[Article] = Fetch.run[Rerunnable](fetch)
    Await.result(article.run, 100.milliseconds) shouldEqual (Article(1, "An article with id 1"))
  }
  it should "allow for several async datasources to be combined" in {
    val fetch: Fetch[(Article, Author)] = for {
      art ← article(1)
      author ← author(art)
    } yield (art, author)

    val rr: Rerunnable[(Article, Author)] = Fetch.run[Rerunnable](fetch)
    Await.result(rr.run, 100.milliseconds) shouldEqual (Article(1, "An article with id 1"), Author(2, "@egg2"))
    Await.result(rr.run, 100.milliseconds) shouldEqual (Article(1, "An article with id 1"), Author(2, "@egg2"))
  }

}
