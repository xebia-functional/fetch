/*
 * Copyright 2016 47 Degrees, LLC. <http://www.47deg.com>
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

import scala.collection.immutable._

import cats.{Applicative, ApplicativeError, Id, Monad, MonadError, Semigroup, ~>}
import cats.data.{Coproduct, EitherT, NonEmptyList, OptionT, StateT, Validated, ValidatedNel}
import cats.free.Free
import cats.instances.option._
import cats.instances.list._
import cats.instances.map._
import cats.instances.tuple._
import cats.syntax.cartesian._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.list._
import cats.syntax.option._
import cats.syntax.reducible._
import cats.syntax.semigroup._
import cats.syntax.traverse._
import cats.syntax.validated._

import cats.free.FreeTopExt

trait FetchInterpreters {

  def interpreter[M[_]: FetchMonadError]: FetchOp ~> FetchInterpreter[M]#f =
    parallelJoinPhase
      .andThen[Fetch](Free.foldMap(maxBatchSizePhase))
      .andThen[FetchInterpreter[M]#f](
        Free.foldMap[FetchOp, FetchInterpreter[M]#f](coreInterpreter[M]))

  def coreInterpreter[M[_]](
      implicit M: FetchMonadError[M]
  ): FetchOp ~> FetchInterpreter[M]#f = {
    new (FetchOp ~> FetchInterpreter[M]#f) {
      def apply[A](fa: FetchOp[A]): FetchInterpreter[M]#f[A] =
        fa match {
          case Join(fl, fr) =>
            Monad[FetchInterpreter[M]#f].tuple2(
              fl.foldMap[FetchInterpreter[M]#f](coreInterpreter[M]),
              fr.foldMap[FetchInterpreter[M]#f](coreInterpreter[M]))

          case other =>
            StateT[M, FetchEnv, A] { env: FetchEnv =>
              other match {
                case Thrown(e)              => M.raiseError(UnhandledException(env, e))
                case one @ FetchOne(_, _)   => processOne(one, env)
                case many @ FetchMany(_, _) => processMany(many, env)
                case conc @ Concurrent(_)   => processConcurrent(conc, env)
                case Join(_, _)             => throw new Exception("join already handled")
              }
            }
        }
    }
  }

  private[this] def processOne[M[_], A](
      one: FetchOne[Any, A],
      env: FetchEnv
  )(
      implicit M: FetchMonadError[M]
  ): M[(FetchEnv, A)] = {
    val FetchOne(id, ds) = one
    val startRound       = System.nanoTime()
    env.cache
      .get[A](ds.identity(id))
      .fold[M[(FetchEnv, A)]](
        M.runQuery(ds.fetchOne(id)).flatMap { (res: Option[A]) =>
          val endRound = System.nanoTime()
          res.fold[M[(FetchEnv, A)]] {
            // could not get result from datasource
            M.raiseError(NotFound(env, one))
          } { result =>
            // found result (and update cache)
            val newCache = env.cache.update(ds.identity(id), result)
            val round    = Round(env.cache, one, result, startRound, endRound)
            M.pure(env.evolve(round, newCache) -> result)
          }
        }
      ) { cached =>
        // get result from cache
        M.pure(env -> cached)
      }
  }

  private[this] def processMany[M[_], A](
      many: FetchMany[Any, Any],
      env: FetchEnv
  )(
      implicit M: FetchMonadError[M],
      ev: List[Any] =:= A
  ): M[(FetchEnv, A)] = {
    val FetchMany(ids, ds) = many
    val startRound         = System.nanoTime()
    val cache              = env.cache

    (for {
      newIdsNel <- EitherT.fromEither[M] {
        many.missingIdentities(cache).toNel.toRight[(FetchEnv, List[Any])] {
          // no missing ids, get all from cache
          val cachedResults = ids.toList.mapFilter(id => cache.get(ds.identity(id)))
          env -> cachedResults
        }
      }
      resMap <- EitherT.right(M.runQuery(ds.fetchMany(newIdsNel)))
      results <- ids.toList
        .traverseU(id => resMap.get(id).toValidNel(id))
        .toEither
        .leftMap { missingIds =>
          // not all identities could be found
          MissingIdentities(env, Map(ds.name -> missingIds.toList))
        }
        .raiseLeftAsError[M, (FetchEnv, List[Any])]
    } yield {
      // found all results (and update cache)
      val endRound = System.nanoTime()
      val newCache = cache.cacheResults(resMap, ds)
      val round    = Round(cache, many, results, startRound, endRound)
      env.evolve(round, newCache) -> results
    }).merge.map { case (env, l) => (env, ev(l)) } // A =:= List[Any]
  }

  private[this] def processConcurrent[M[_]](
      concurrent: Concurrent,
      env: FetchEnv
  )(
      implicit M: FetchMonadError[M]
  ): M[(FetchEnv, InMemoryCache)] = {

    def uncachedQueriessOrCachedResults[I, A](
        queries: NonEmptyList[FetchQuery[I, A]],
        cache: DataSourceCache
    ): ValidatedNel[FetchQuery[I, A], NonEmptyList[(DataSourceIdentity, A)]] = {
      def idOrResult[I, A](id: I, ds: DataSource[I, A]): ValidatedNel[I, (DataSourceIdentity, A)] = {
        val identity = ds.identity(id)
        cache.get[A](identity).map(identity -> _).toValidNel(id)
      }

      def missingIdsToQuery[I, A](ids: NonEmptyList[I], ds: DataSource[I, A]): FetchQuery[I, A] =
        ids match {
          case NonEmptyList(id, Nil) => FetchOne(id, ds)
          case ids                   => FetchMany(ids, ds)
        }

      queries.flatTraverse[ValidatedNel[FetchQuery[I, A], ?], (DataSourceIdentity, A)] {
        case FetchOne(id, ds) =>
          idOrResult(id, ds)
            .map(NonEmptyList(_, Nil))
            .leftMap(ids => NonEmptyList.of(missingIdsToQuery(ids, ds)))
        case FetchMany(ids, ds) =>
          ids
            .traverseU(id => idOrResult(id, ds))
            .leftMap(ids => NonEmptyList.of(missingIdsToQuery(ids, ds)))
      }
    }

    def runFetchQueryAsMap[I, A](op: FetchQuery[I, A]): M[Map[I, A]] =
      op match {
        case FetchOne(a, ds) =>
          OptionT(M.runQuery(ds.fetchOne(a))).map(r => Map(a -> r)).getOrElse(Map.empty)
        case FetchMany(as, ds) => M.runQuery(ds.fetchMany(as))
      }

    type MissingIdentitiesMap = Map[DataSourceName, List[Any]]
    type AnyQuery             = FetchQuery[Any, Any]
    type AnyResult            = Map[Any, Any]

    def missingIdentitiesOrAllFulfilled(
        queriesAndResults: NonEmptyList[(AnyQuery, AnyResult)]
    ): Validated[MissingIdentitiesMap, Unit] =
      queriesAndResults.traverseU_ {
        case (FetchOne(id, ds), resultMap) =>
          Either.cond(resultMap.size == 1, (), Map(ds.name -> List(id))).toValidated
        case (FetchMany(as, ds), resultMap) =>
          Either
            .cond(as.toList.size == resultMap.size,
                  (),
                  Map(ds.name -> as.toList.filter(id => resultMap.get(id).isEmpty)))
            .toValidated
        case _ =>
          Map.empty[DataSourceName, List[Any]].invalid
      }

    // return a MissingIdentities error or a NonEmptyList with all
    // the query - result pairs
    def errorOrAllFound(
        queries: NonEmptyList[AnyQuery],
        results: NonEmptyList[AnyResult]
    ): Either[MissingIdentities, NonEmptyList[(AnyQuery, AnyResult)]] = {
      val zipped = NonEmptyList.fromListUnsafe(queries.toList zip results.toList)
      missingIdentitiesOrAllFulfilled(zipped).toEither
        .bimap(missingIds => MissingIdentities(env, missingIds), _ => zipped)
    }

    val startRound = System.nanoTime()
    val cache      = env.cache

    (for {
      queries <- EitherT.fromEither[M](
        uncachedQueriessOrCachedResults(concurrent.queries, cache).swap.toEither.bimap(
          cachedResults => env -> InMemoryCache(cachedResults.toList.toMap),
          _.asInstanceOf[NonEmptyList[AnyQuery]])
      )
      results <- EitherT.right(queries.traverse(runFetchQueryAsMap))
      endRound = System.nanoTime()
      queriesAndResults <- errorOrAllFound(queries, results)
        .raiseLeftAsError[M, (FetchEnv, InMemoryCache)]
    } yield {
      val round = Round(cache, Concurrent(queries), results, startRound, endRound)

      // since user-provided caches may discard elements, we use an in-memory
      // cache to gather these intermediate results that will be used for
      // concurrent optimizations.
      val (newCache, cachedResults) = queriesAndResults.foldLeft((cache, InMemoryCache.empty)) {
        case ((userCache, internCache), (req, resultMap)) =>
          val anyMap = resultMap.asInstanceOf[AnyResult]
          val anyDS  = req.dataSource.castDS[Any, Any]
          (userCache.cacheResults(anyMap, anyDS),
           internCache.cacheResults(anyMap, anyDS).asInstanceOf[InMemoryCache])
      }

      (env.evolve(round, newCache), cachedResults)
    }).merge
  }

  val maxBatchSizePhase: FetchOp ~> Fetch = new (FetchOp ~> Fetch) {
    def apply[A](op: FetchOp[A]): Fetch[A] =
      op match {
        case many @ FetchMany(_, _) => batchMany(many)
        case conc @ Concurrent(_)   => batchConcurrent(conc)
        case _                      => Free.liftF(op)
      }
  }

  private[this] def manyInBatches[I, A](many: FetchMany[I, A]): NonEmptyList[FetchMany[I, A]] = {
    val FetchMany(ids, ds) = many
    ds.maxBatchSize.fold(NonEmptyList.of(many)) { batchSize =>
      ids.unsafeListOp {
        _.grouped(batchSize)
          .map(batchIds => FetchMany[I, A](NonEmptyList.fromListUnsafe(batchIds), ds))
          .toList
      }
    }
  }

  private[this] def batchMany[I, A](many: FetchMany[I, A]): Fetch[List[A]] = {
    val batchedFetches = manyInBatches(many)
    batchedFetches.reduceLeftM[Fetch, List[A]](Free.liftF[FetchOp, List[A]]) {
      case (results, fetchMany) =>
        Free.liftF(fetchMany).map(results ++ _)
    }
  }

  private[this] def batchConcurrent(conc: Concurrent): Fetch[InMemoryCache] = {
    type Batch = NonEmptyList[FetchQuery[Any, Any]]
    val Concurrent(fetches) = conc
    val individualBatches: NonEmptyList[Batch] = fetches.map {
      case many @ FetchMany(_, _) => manyInBatches(many).asInstanceOf[Batch]
      case other                  => NonEmptyList.of(other.asInstanceOf[FetchQuery[Any, Any]])
    }
    val batchedConcurrents = transposeNelsUnequalLengths(individualBatches).map(Concurrent(_))
    batchedConcurrents.reduceLeftM[Fetch, InMemoryCache](Free.liftF(_)) {
      case (caches, conc) => Free.liftF(conc).map(caches |+| _)
    }
  }

  private[fetch] def transposeNelsUnequalLengths[A](
      nestedNel: NonEmptyList[NonEmptyList[A]]
  ): NonEmptyList[NonEmptyList[A]] = {
    type NEL[A] = NonEmptyList[A]
    // return one transposed line and the rest of the still untransposed lines
    def oneLine(nelnel: NEL[NEL[A]]): (NEL[A], List[NEL[A]]) =
      nelnel.reduceMap(nel => (NonEmptyList.of(nel.head), nel.tail.toNel.toList))

    // keep transposing until oneLine returns an empty list as "rest lines"
    // List[NEL[A]] == Option[NEL[NEL[A]]]
    unfoldNonEmpty[NEL, NEL[A], NEL[NEL[A]]](nestedNel) { nel =>
      val (line, rest) = oneLine(nel)
      (line, rest.toNel)
    }
  }

  private[fetch] def unfoldNonEmpty[F[_], A, B](seed: B)(
      f: B => (A, Option[B]))(implicit F: Applicative[F], S: Semigroup[F[A]]): F[A] = {
    def loop(seed: B)(xs: F[A]): F[A] = f(seed) match {
      case (a, Some(b)) => loop(b)(S.combine(xs, F.pure(a)))
      case (a, None)    => S.combine(xs, F.pure(a))
    }

    f(seed) match {
      case (a, Some(b)) => loop(b)(F.pure(a))
      case (a, None)    => F.pure(a)
    }
  }

  private[this] implicit class RaiseErrorEitherT[E <: FetchException, B](either: Either[E, B]) {
    def raiseLeftAsError[M[_], A](implicit M: FetchMonadError[M]): EitherT[M, A, B] =
      either.fold[EitherT[M, A, B]](error => EitherT.left[M, A, B](M.raiseError(error)),
                                    EitherT.pure[M, A, B](_))
  }

  val parallelJoinPhase: FetchOp ~> Fetch =
    new (FetchOp ~> Fetch) {
      def apply[A](op: FetchOp[A]): Fetch[A] = op match {
        case join @ Join(fl, fr) =>
          val fetchJoin    = Free.liftF(join)
          val indepQueries = combineQueries(independentQueries(fetchJoin))
          parallelJoin(fetchJoin, indepQueries)
        case other => Free.liftF(other)
      }
    }

  private[this] def parallelJoin[A, B](
      fetchJoin: Fetch[(A, B)],
      queries: List[FetchQuery[_, _]]
  ): Fetch[(A, B)] = {
    combineQueries(queries).asInstanceOf[List[FetchQuery[Any, Any]]].toNel.fold(fetchJoin) {
      queriesNel =>
        Free.liftF(Concurrent(queriesNel)).flatMap { cache =>
          val simplerFetchJoin = simplify(cache)(fetchJoin)
          val indepQueries     = independentQueries(simplerFetchJoin)
          indepQueries.toNel.fold(simplerFetchJoin) { queries =>
            parallelJoin(simplerFetchJoin, queries.toList)
          }
        }
    }
  }

  private[this] def independentQueries(f: Fetch[_]): List[FetchQuery[_, _]] =
    // we need the `.step` below to ignore pure values when we search for
    // independent queries, but this also has the consequence that pure
    // values can be executed multiple times.
    //  eg : Fetch.pure(5).map { i => println("hello"); i * 2 }
    FreeTopExt.inspect(f.step).foldMap {
      case Join(ffl, ffr)         => independentQueries(ffl) ++ independentQueries(ffr)
      case one @ FetchOne(_, _)   => one :: Nil
      case many @ FetchMany(_, _) => many :: Nil
      case _                      => Nil
    }

  /**
    * Use a `DataSourceCache` to optimize a `FetchOp`.
    * If the cache contains all the fetch identities, the fetch doesn't need to be
    * executed and can be replaced by cached results.
    */
  private[this] def simplify[A](cache: InMemoryCache)(fetch: Fetch[A]): Fetch[A] =
    FreeTopExt.modify(fetch)(
      new (FetchOp ~> Coproduct[FetchOp, Id, ?]) {
        def apply[X](fetchOp: FetchOp[X]): Coproduct[FetchOp, Id, X] = fetchOp match {
          case one @ FetchOne(id, ds) =>
            Coproduct[FetchOp, Id, X](cache.get[X](ds.identity(id)).toRight(one))
          case many @ FetchMany(ids, ds) =>
            val fetched = ids.traverse(id => cache.get(ds.identity(id)))
            Coproduct[FetchOp, Id, X](fetched.map(_.toList).toRight(many))
          case join @ Join(fl, fr) =>
            val sfl      = simplify(cache)(fl)
            val sfr      = simplify(cache)(fr)
            val optTuple = (FreeTopExt.inspectPure(sfl) |@| FreeTopExt.inspectPure(sfr)).tupled
            Coproduct[FetchOp, Id, X](optTuple.toRight(Join(sfl, sfr)))
          case other =>
            Coproduct.leftc(other)
        }
      }
    )

  /**
    * Combine multiple queries so the resulting `List` only contains one `FetchQuery`
    * per `DataSource`.
    */
  private[this] def combineQueries(qs: List[FetchQuery[_, _]]): List[FetchQuery[_, _]] =
    qs.foldMap[Map[DataSource[_, _], NonEmptyList[Any]]] {
        case FetchOne(id, ds)   => Map(ds -> NonEmptyList.of[Any](id))
        case FetchMany(ids, ds) => Map(ds -> ids.widen[Any])
      }
      .mapValues { nel =>
        // workaround because NEL[Any].distinct would need Order[Any]
        nel.unsafeListOp(_.distinct)
      }
      .toList
      .map {
        case (ds, NonEmptyList(id, Nil)) => FetchOne(id, ds.castDS[Any, Any])
        case (ds, ids)                   => FetchMany(ids, ds.castDS[Any, Any])
      }

}
