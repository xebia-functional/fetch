package fetch


/**
 * A `Cache` trait so the users of the library can provide their own cache.
 */
trait DataSourceCache {
  def update[I, A](k: DataSourceIdentity, v: A): DataSourceCache

  def get[I](k: DataSourceIdentity): Option[Any]

  def cacheResults[I, A](results: Map[I, A], ds: DataSource[I, A]): DataSourceCache = {
    results.foldLeft(this)({
      case (acc, (i, a)) => acc.update(ds.identity(i), a)
    })
  }
}


