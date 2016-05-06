package fetch

/**
 * A `DataSource` is the recipe for fetching a certain identity `I`, which yields
 * results of type `A` with the concurrency and error handling specified by the Monad
 * `M`.
 */
trait DataSource[I, A, M[_]] {
  def name: DataSourceName = this.toString
  def identity(i: I): DataSourceIdentity = (name, i)
  def fetch(ids: List[I]): M[Map[I, A]]
}
