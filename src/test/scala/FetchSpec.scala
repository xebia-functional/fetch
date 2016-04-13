import org.specs2.mutable._

import cats._
import fetch._


class FetchSpec extends Specification {
  implicit def applicativeErrorId(
    implicit
      I: Applicative[cats.Id]
  ): ApplicativeError[Id, Throwable] = new ApplicativeError[Id, Throwable](){
    override def pure[A](x: A): Id[A] = I.pure(x)

    override def ap[A, B](ff: Id[A ⇒ B])(fa: Id[A]): Id[B] = I.ap(ff)(fa)

    override def map[A, B](fa: Id[A])(f: Id[A ⇒ B]): Id[B] = I.map(fa)(f)

    override def product[A, B](fa: Id[A], fb: Id[B]): Id[(A, B)] = I.product(fa, fb)

    override def raiseError[A](e: Throwable): Id[A] =
      throw e

    override def handleErrorWith[A](fa: Id[A])(f: Throwable ⇒ Id[A]): Id[A] = {
      try {
        fa
      } catch {
        case e: Exception ⇒ f(e)
      }
    }
  }

  "Fetch" >> {
    implicit object IntSource extends DataSource[Int, Int] {
      override def fetch(id: Int): Option[Int]= Some(id)
      override def fetchMany(ids: List[Int]): List[Int] = ids
    }

    implicit object StringSource extends DataSource[String, String] {
      override def fetch(id: String): Option[String]= Some(id)
      override def fetchMany(ids: List[String]): List[String] = ids
    }

    "one" >> {
      Fetch.run(Fetch.one(1)) == Some(1)
    }

    "many" >> {
      import cats.implicits._

      println("DEPS " + Fetch.dependencies(Fetch.many(List(1, 2, 3))))

      Fetch.run(Fetch.many(List(1, 2, 3))) == List(1, 2, 3)
    }

    "for comprehension" >> {
      val ftch = for {
        one <- Fetch.one(1).monad
        many <- Fetch.many(List(1, 2, 3)).monad
      } yield (one, many)

      Fetch.run(ftch) == (Some(1), List(1, 2, 3))
    }

    "mixing data sources" >> {
      val ftch = for {
        one <- Fetch.one(1).monad
        two <- Fetch.one("yolo").monad
      } yield (one, two)



      Fetch.run(ftch) == (Some(1), Some("yolo"))
    }

    "Fetch as an applicative" >> {
      import cats.syntax.all._

      val ftch = (Fetch.one(1) |@| Fetch.one("yolo")).map { case (a, b) => (a, b) }
      
      import cats.implicits._
      println("DEPS " + Fetch.dependencies(ftch))

      Fetch.run(ftch) == (Some(1), Some("yolo"))
    }
  }
}


