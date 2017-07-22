import scala.concurrent._
import scala.concurrent.duration._

import org.scalatest._

import cats.data.NonEmptyList
import cats.instances.list._
import fetch._
import fetch.implicits._


class FutureTimeoutTests extends AsyncFlatSpec with Matchers {

  implicit override def executionContext : ExecutionContext = ExecutionContext.Implicits.global

  // A datasource with configurable delay

  case class ArticleId(id: Int)
  case class Article(id: Int, content: String) {
    def author: Int = id + 1
  }

  case class ConfigurableTimeoutDatasource(timeout: FiniteDuration) extends DataSource[ArticleId, Article] {
    override def name = "ArticleFuture"
    override def fetchOne(id: ArticleId): Query[Option[Article]] =
      Query.async((ok, fail) => {
        ok(Option(Article(id.id, "An article with id " + id.id)))
      }, timeout)
    override def fetchMany(ids: NonEmptyList[ArticleId]): Query[Map[ArticleId, Article]] =
      batchingNotSupported(ids)
  }

  implicit val timeout500msDataSource = ConfigurableTimeoutDatasource(500 milliseconds)

  def article(id: Int): Fetch[Article] = Fetch(ArticleId(id))

  "fetchfuture" should "fail with timeout when a datasource does not complete in time" in {

    val fetch: Fetch[Article] = article(1)
    val fut: Future[Article]  = Fetch.run[Future](fetch)
    fut.map(_ shouldEqual Article(1, "An article with id 1"))

    succeed

  }


}
