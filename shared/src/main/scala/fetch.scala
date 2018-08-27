/*
 * Copyright 2016-2018 47 Degrees, LLC. <http://www.47deg.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fetch

import scala.collection.immutable.Map

import cats.{Functor, Applicative, Monad, Eval}
import cats.data.{NonEmptyList, StateT}
import cats.free.Free
import cats.instances.list._
import cats.effect.Effect


object `package` {
  type DataSourceName     = String
  type DataSourceIdentity = (DataSourceName, Any)

  sealed trait FetchRequest
  case class FetchOne[I, A](id: I, ds: DataSource[I, A]) extends FetchRequest

  sealed trait FetchResult
  case class FetchDone[I, A](id: I, ds: DataSource[I, A]) extends FetchResult
  case class FetchInProgress[I, A](id: I, ds: DataSource[I, A]) extends FetchResult

  sealed trait Fetch[A]
  case class Done[A](x: A) extends Fetch[A]
  case class Blocked[A](r: FetchRequest, cont: Any =>  Fetch[A]) extends Fetch[A]

  implicit val fetchFunctor: Functor[Fetch] = new Functor[Fetch] {
    def map[A, B](fa: Fetch[A])(f: A => B): Fetch[B] = fa match {
      case Done(a) => Done(f(a))
      case Blocked(r, cont) => {
        Blocked(r, (result) => map(cont(result))(f))
      }
    }
  }

  implicit val fetchApplicative: Applicative[Fetch] = new Applicative[Fetch] {
    def pure[A](a: A): Fetch[A] = Done(a)

    def ap[A, B](ff: Fetch[A => B])(fa: Fetch[A]): Fetch[B] = (ff, fa) match {
      case (Done(f), Done(a)) => Done(f(a))
      case (Done(f), Blocked(r, cont)) => Blocked(r, (result) => map(cont(result))(f))
      case (Blocked(r, cont), Done(a)) => Blocked(r, (result) => map(cont(result))((f) => f(a)))
      case (Blocked(r, cont), Blocked(r2, cont2)) =>
        ???
    }
  }

  implicit val fetchFlatmap: Monad[Fetch] = new Monad[Fetch] {
    def pure[A](x: A): Fetch[A] =
      Done(x)

    def tailRecM[A, B](a: A)(f: A => Fetch[Either[A, B]]): Fetch[B] =
      ???

    def flatMap[A, B](fa: Fetch[A])(f: A => Fetch[B]): Fetch[B] = fa match {
      case Done(a) => f(a)
      case Blocked(r, cont) => Blocked(r, (result) => flatMap(cont(result))(f))
    }
  }

  object Fetch {

    /**
     * Lift a plain value to the Fetch monad.
     */
    def pure[A](a: A): Fetch[A] =
      Done(a)

    def apply[I, A](id: I)(implicit ds: DataSource[I, A]): Fetch[A] =
      Blocked[A](
        FetchOne[I, A](id, ds),
        (a) => {
          Done(a.asInstanceOf[A])
        }
      )

    private def fetchOne[I, A, M[_] : Effect](id: I, ds: DataSource[I, A]): M[A] = {
      Effect[M].flatMap(ds.fetchOne(id)) {
        case Some(a) => Effect[M].pure(a)
        case None => ??? // Effect[M].raiseError(NotFound(id, ds.name))
      }
    }

    private[fetch] class FetchRunner[M[_]](val dummy: Boolean = true) extends AnyVal {
      def apply[A](
          fa: Fetch[A]
      )(
        implicit M: Effect[M]
      ): M[A] = fa match {
        case Done(a) => M.pure(a)
        case Blocked(r, cont) => {
          r match {
            case FetchOne(id, ds) => {
              M.flatMap(
                fetchOne(id, ds.castDS[Any, A])
              )(a => apply(cont(a)))
            }
          }
        }
      }
    }

    /**
     * Run a `Fetch`, the result in the monad `M`.
     */
    def run[M[_]]: FetchRunner[M] = new FetchRunner[M]
  }

  private[fetch] implicit class DataSourceCast[A, B](private val ds: DataSource[A, B]) extends AnyVal {
    def castDS[C, D]: DataSource[C, D] = ds.asInstanceOf[DataSource[C, D]]
  }

}
