package fetch
package implicits

import cats.{ Eval, Id }
import cats.{ MonadError }

object eval {
  val monadError: MonadError[Eval, Throwable] = new MonadError[Eval, Throwable] {
    override def pure[A](x: A): Eval[A] = Eval.now(x)

    override def map[A, B](fa: Eval[A])(f: A ⇒ B): Eval[B] = fa.map(f)

    override def flatMap[A, B](fa: Eval[A])(ff: A => Eval[B]): Eval[B] = fa.flatMap(ff)

    override def raiseError[A](e: Throwable): Eval[A] = Eval.later({ throw e })

    override def handleErrorWith[A](fa: Eval[A])(f: Throwable ⇒ Eval[A]): Eval[A] = Eval.now({
      try{
        fa.value
      } catch {
        case e: Throwable => f(e).value
      }
    })
  }
}

object id {
  val monadError: MonadError[Id, Throwable] = new MonadError[Id, Throwable] {
    override def pure[A](x: A): Id[A] = x

    override def map[A, B](fa: Id[A])(f: A ⇒ B): Id[B] = f(fa)

    override def flatMap[A, B](fa: Id[A])(ff: A => Id[B]): Id[B] = ff(fa)

    override def raiseError[A](e: Throwable): Id[A] = throw e

    override def handleErrorWith[A](fa: Id[A])(f: Throwable ⇒ Id[A]): Id[A] =
      try {
        fa
      } catch {
        case e: Throwable => f(e)
      }
  }
}
