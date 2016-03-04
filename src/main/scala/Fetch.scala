package fetch

import cats.{ Applicative, Traverse }
import cats.std.list._
import cats.std.future._
import cats.syntax.traverse._

import scala.util.Try
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

// Future applicative

object Implicits {
  implicit val futureApplicative: Applicative[Future] =
    new Applicative[Future] {
      override def pure[A](x: A): Future[A] = Future.successful(x)

      override def ap[A, B](ff: Future[A => B])(fa: Future[A]): Future[B] =
        for {
          f <- ff
          a <- fa
        } yield f(a)

      override def product[A, B](fa: Future[A], fb: Future[B]): Future[(A, B)] =
        ???

      override def map[A, B](fa: Future[A])(f: A => B): Future[B] =
        fa.map(f)
    }
}
import Implicits._


/* An abstraction for remote data sources that can be fetched one by one or in batches. */
trait DataSource[T] {
  def identity: String
  def fetch: Future[T]
  def fetchMulti(calls: List[DataSource[T]]): Future[List[T]] =
    calls.map(_.fetch).sequence
}

/* A remote data fetch. */
sealed trait Fetch[T] {
  def map[B](f: T => B): Fetch[B] =
    Fmap(f, this)

  def flatMap[B](f: T => Fetch[B]): Fetch[B] =
    FlatMap(f, this)
}

/* A single call to a remote data source. */
case class Call[A](ds: DataSource[A]) extends Fetch[A]

/* Transformation of a fetch. */
case class Fmap[A, B](f: A => B, fetch: Fetch[A]) extends Fetch[B]

/* Collection of data sources as a single fetch. */
case class Collect[A](ss: List[Fetch[A]]) extends Fetch[List[A]]

/* Sequential composition of fetches. */
case class FlatMap[A, B](f: A => Fetch[B], fetch: Fetch[A]) extends Fetch[B]

/* A fetch that has completed with a value. */
case class Done[A](a: A) extends Fetch[A]

object AST {
  type Env = Map[String, Map[String, T forSome { type T }]]

  def resourceName[A](ds: DataSource[A]): String = {
    ds.getClass.getName
  }

  def fetchResource[A](sources: List[DataSource[_]], env: Env): Future[List[(String, String, _)]] = {
    val fst: DataSource[A] = sources.head.asInstanceOf[DataSource[A]]
    val resource = resourceName(fst)
    fst.fetchMulti(sources.asInstanceOf[List[DataSource[A]]]).map(results =>
     (sources.map(_.identity) zip results).map(v => (resource, v._1, v._2))
    )
  }

  def inject[T](f: Fetch[T], env: Env): Fetch[T] = f match {
    case Call(ds) => {
      val cached = for {
        resources <- env.get(resourceName(ds))
        cachedValue <- resources.get(ds.identity)
      } yield cachedValue
      cached.fold(Call(ds):Fetch[T])(Done(_).asInstanceOf[Fetch[T]])
    }
    case Fmap(f, ftch) => inject(ftch, env) match {
      case Done(x) => Done(f(x))
      case next: Fetch[_] => Fmap(f, next)
    }
    case Collect(ftches) => {
      val collected = ftches.map(inject(_, env))
      val resolved = collected.collect({ case Done(x) => x })
      if (collected.size == resolved.size)
        Done(resolved).asInstanceOf[Fetch[T]]
      else
        Collect(collected).asInstanceOf[Fetch[T]]
    }
    case FlatMap(f, ftch) => inject(ftch, env) match {
      case Done(x) => inject(f(x), env)
      case next: Fetch[_] => {
        FlatMap(f, next)
      }
    }
    case Done(x) => Done(x)
  }

  def updateEnv(results: List[(String, String, _)], env: Env): Env = {
    results.foldLeft(env)((theEnv: Env, result: (String, String, _)) => {
      val (resource, id, value) = result
      lazy val initialResourceCache = Map(id -> value)
      val resourceCache = theEnv.get(resource).fold(initialResourceCache)(_.updated(id, value))
      theEnv.updated(resource, resourceCache)
    })
  }

  def children[T](f: Fetch[T]): List[DataSource[_]] = f match {
    case Call(ds) => List(ds)
    case Fmap(f, ftch) => children(ftch)
    case Collect(ftchs) => ftchs.map(children(_)).flatten
    case FlatMap(f, ftch) => children(ftch)
    case Done(_) => Nil
  }

  def interpret[T](f: Fetch[T], env: Env): Future[T] = {
    val ast = inject(f, env)
    val requests = children(ast)
    requests match {
      case Nil => ast match {
        case Done(x) => Future.successful(x)
        case _ => interpret(ast, env)
      }
      case _ => {
        val requestsByType = requests.groupBy(resourceName(_)).mapValues(_.distinct)
        val responses = requestsByType.mapValues(fetchResource(_, env)).values.toList
        responses.sequence.flatMap(results => {
          val nextEnv = updateEnv(results.flatten, env)
          interpret(ast, nextEnv)
        })
      }
    }
  }
}

object Fetch {
  import AST._

  def apply[T](ds: DataSource[T]): Fetch[T] = Call(ds)

  def pure[T](t: T): Fetch[T] = Done(t)

  def collect[T](ss: List[Fetch[T]]): Fetch[List[T]] =
    Collect(ss)

  def traverse[T, U](ts: List[T])(f: T => Fetch[U]): Fetch[List[U]] =
    Collect(ts.map(f))

  def run[T](f: Fetch[T]): Future[T] = {
    val initialEnv: Env = Map()
    interpret(f, initialEnv)
  }
}
