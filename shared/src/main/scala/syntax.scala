package fetch

object syntax {

  /** Implicit syntax to lift any value to the context of Fetch via pure */
  implicit class FetchIdSyntax[A](val a: A) extends AnyVal {

    def fetch: Fetch[A] =
      Fetch.pure(a)

  }

  /** Implicit syntax to lift exception to Fetch errors */
  implicit class FetchErrorSyntax[A <: Throwable](val a: A) extends AnyVal {

    def fetch: Fetch[A] =
      Fetch.error(a)

  }

  /** Implicit syntax for Fetch ops in Free based Fetches */
  implicit class FetchSyntax[A](val fa: Fetch[A]) extends AnyVal {

    def join[B](fb: Fetch[B]): Fetch[(A, B)] =
      Fetch.join(fa, fb)

    def runF[M[_]: FetchMonadError]: M[(FetchEnv, A)] =
      Fetch.runFetch[M](fa)

    def runE[M[_]: FetchMonadError]: M[FetchEnv] =
      Fetch.runEnv[M](fa)

    def run[M[_]: FetchMonadError]: M[A] =
      Fetch.run[M](fa)

  }

}
