package fetch

/**
 * A marker trait for cache implementations.
 */
trait DataSourceCache

/**
 * A `Cache` trait so the users of the library can provide their own cache.
 */
trait Cache[T <: DataSourceCache] {
  def update[I, A](c: T, k: DataSourceIdentity, v: A): T

  def get[I](c: T, k: DataSourceIdentity): Option[Any]

  def cacheResults[I, A, M[_]](cache: T, results: Map[I, A], ds: DataSource[I, A, M]): T = {
    results.foldLeft(cache)({
      case (acc, (i, a)) => update(acc, ds.identity(i), a)
    })
  }
}


