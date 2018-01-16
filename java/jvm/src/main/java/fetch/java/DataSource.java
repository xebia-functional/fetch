package fetch.java;

import cats.data.NonEmptyList;
import fetch.ExecutionType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import scala.None;

/**
 * A `DataSource` is the recipe for fetching a certain identity `I`, which yields
 * results of type `A`.
 */
abstract class DataSource<I, A> {
  /**
   * The name of the data source.
   */
  abstract String name();

  /**
   * Fetch one identity, returning a None if it wasn't found.
   */
  abstract Query<Optional<A>> fetchOne(I id);

  /**
   * Derive a `DataSourceIdentity` from an identity, suitable for storing the result
   * of such identity in a `DataSourceCache`.
   */
  public DataSourceIdentity identity(I id) {
    return new DataSourceIdentity<>(name(), id);
  }

  /**
   * Fetch many identities, returning a mapping from identities to results. If an
   * identity wasn't found, it won't appear in the keys.
   */
  abstract Query<Map<I, A>> fetchMany(List<I> ids);

  /**
   * Use `fetchOne` for implementing of `fetchMany`. Use only when the data
   * source doesn't support batching.
   */
  final Query<Map<I, A>> batchingNotSupported(List<I> ids) {

    Stream<Query<Optional<A>>> queryStream = ids.stream().map(this::fetchOne);
    queryStream

    Function<I, Query<Optional<DataSourceIdentity<I>>>> fetchOneWithId = (id) -> {
      fetchOne()

      return fetchOne(id).map(_.tupleLeft(id));
    }

    ids.toList.traverse(fetchOneWithId).map(_.collect {
      case Some(x) =>x
    }.toMap)

  }

  def batchingOnly(id:I):Query[Option[A]]=

  fetchMany(NonEmptyList.one(id)).

  map(_ get id)

  def maxBatchSize:Option[Int]=None

  def batchExecution:ExecutionType =Parallel

  @Override

  public String toString() {
    return "DataSource:" + name();
  }
}
