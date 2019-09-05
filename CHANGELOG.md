# Changelog

## 06/04/2019 - Version 1.1.0

Release changes:

* Run a Fetch into a Monix Task ([#170](https://github.com/47deg/fetch/pull/170))
* Don't run examples tests every time ([#186](https://github.com/47deg/fetch/pull/186))
* Loosen implicit constraints ([#187](https://github.com/47deg/fetch/pull/187))
* Fetch#liftIO ([#182](https://github.com/47deg/fetch/pull/182))
* Lift Concurrent actions to Fetch ([#189](https://github.com/47deg/fetch/pull/189))
* Switch to Scala Code of Conduct ([#192](https://github.com/47deg/fetch/pull/192))
* Add Fetch#runAll ([#194](https://github.com/47deg/fetch/pull/194))
* Upgrades sbt-org-policies ([#196](https://github.com/47deg/fetch/pull/196))
* Improve Redis example ([#197](https://github.com/47deg/fetch/pull/197))
* 1.1.0 release ([#198](https://github.com/47deg/fetch/pull/198))

## 02/26/2019 - Version 1.0.0

The 1.0.0 release of Fetch is a redesign of the library in terms of `cats-effect` abstractions. It's a backwards-incompatible release that introduces numerous breaking changes, as well as a couple of new features. It should now be easier to use, and require less work from the user of the library, especially when you are already using `cats-effect`.

### Breaking changes

- Introduced the `Data` typeclass to identify requests to a data source
- Redesigned `DataSource` to take an extra `F[_]` type parameter
  + Renamed `fetchOne` to `fetch` and `fetchMany` to `batch`
  + Data sources now can be implicitly constructed
  + Automatic parallel implementation of `DataSource#batch` in terms of `ConcurrentEffect[F]`
- Removed `FetchMonadError`, a Fetch is now interpreted to a `ConcurrentEffect`
- Removed `Query`, a data source now returns a `F` that has an implicit `ConcurrentEffect[F]`
- Renamed `DataSourceCache` to `DataCache`, it now takes a `Data` instance as a parameter for insert and lookup and is parameterised to F
- Renamed `Env` to `Log`

### New features

- Introduced `Fetch#optional`, an alternative to `Fetch#apply` for optional fetches
- Different Data Sources can now have the same identity and result types

### API changes

- `Fetch#run` now requires a `Timer[F]` and `ContextShift[F]` from `cats-effect`
- `Fetch#apply` no longer requires an implicit `DataSource` and must be passed explicitly
- Renamed `Fetch#runEnv` to `Fetch#runLog`
- `Fetch#traverse`, `Fetch#sequence`, `Fetch#join` & `Fetch#collect` deleted in favor of usign cats typeclass ops

### Documentation

- Proof-of-concept Redis cache implementation of `DataCache` ([#161](https://github.com/47deg/fetch/pull/161))
- Removed Monix, Future, and Twitter Future subprojects. Most of them should work with `cats-effect` abstractions already
- GrapQL query interpreter example ([#178](https://github.com/47deg/fetch/pull/178))
- Example using Monix Scheduler and Task to run fetches ([#178](https://github.com/47deg/fetch/pull/178))

## 08/21/2018 - Version 0.7.3

Release changes:

* Updated sbt-org-policies version to 0.9.1 ([#150](https://github.com/47deg/fetch/pull/150))
* Release Fetch v0.7.3  ([#151](https://github.com/47deg/fetch/pull/151))


## 06/13/2018 - Version 0.7.3

Release changes:

* Updated sbt-org-policies version to 0.9.1 ([#150](https://github.com/47deg/fetch/pull/150))
* Release Fetch v0.7.3  ([#151](https://github.com/47deg/fetch/pull/151))


## 01/23/2018 - Version 0.7.2

Release changes:

* Update dependencies, especially to cats 1.0.1 ([#146](https://github.com/47deg/fetch/pull/146))
* Releases 0.7.2 for cats 1.0.1 with #146 ([#147](https://github.com/47deg/fetch/pull/147))


## 11/21/2017 - Version 0.7.1

Release changes:

* Cleanup CHANGELOG after failed release ([#141](https://github.com/47deg/fetch/pull/141))
* Upgrades to cats 1.0.0-RC1 ([#143](https://github.com/47deg/fetch/pull/143))


## 10/06/2017 - Version 0.7.0

Release changes:

* Update dependencies (Cats 1.0.0-MF) ([#137](https://github.com/47deg/fetch/pull/137))
* Update name of Twitter module ([#138](https://github.com/47deg/fetch/pull/138))
* Release Fetch v0.7.0 ([#139](https://github.com/47deg/fetch/pull/139))
* Release 0.7.0 ([#140](https://github.com/47deg/fetch/pull/140))


## 08/22/2017 - Version 0.6.3

Release changes:

* Add timeout capability to Future implementation of FetchMonadError ([#127](https://github.com/47deg/fetch/pull/127))
* Adds commercial support statement ([#129](https://github.com/47deg/fetch/pull/129))
* Add Twitter future support ([#128](https://github.com/47deg/fetch/pull/128))
* Enforce consistent arrow symbols using scalafmt ([#130](https://github.com/47deg/fetch/pull/130))
* Generalize timeout tests. Fix twitter timeout. ([#131](https://github.com/47deg/fetch/pull/131))
* Move and reuse TestHelper ([#132](https://github.com/47deg/fetch/pull/132))
* Release v0.6.3 ([#133](https://github.com/47deg/fetch/pull/133))
* Change delay in flaky timeout test ([#134](https://github.com/47deg/fetch/pull/134))


## 06/01/2017 - Version 0.6.2

Release changes:

* Removes dup doc files ([#117](https://github.com/47deg/fetch/pull/117))
* orgScriptCI task integration ([#118](https://github.com/47deg/fetch/pull/118))
* Installs Travis to be able to publish the Microsite automatically ([#119](https://github.com/47deg/fetch/pull/119))
* Typo in README ([#120](https://github.com/47deg/fetch/pull/120))
* Refactor interpreters + Change Fetch.traverse ([#123](https://github.com/47deg/fetch/pull/123))
* Releases 0.6.2 ([#125](https://github.com/47deg/fetch/pull/125))


## 04/19/2017 - Version 0.6.1

Release changes:

* Add batchingOnly convenience method ([#110](https://github.com/47deg/fetch/pull/110))
* Update AUTHORS.md ([#111](https://github.com/47deg/fetch/pull/111))
* Add curried apply to Fetch object ([#109](https://github.com/47deg/fetch/pull/109))
* Allow sequential and parallel batches ([#113](https://github.com/47deg/fetch/pull/113))
* 0.6.0 release ([#114](https://github.com/47deg/fetch/pull/114))
* Reduce stack consumption ([#95](https://github.com/47deg/fetch/pull/95))
* Integrates sbt-org-policies plugin ([#115](https://github.com/47deg/fetch/pull/115))
* Auto-updating fetch version in docs ([#116](https://github.com/47deg/fetch/pull/116))


## 2017-03-17 - Version 0.6.0

- Add `DataSource#batchingOnly` for batch-only data sources (thanks @aleczorab)
- Add `DataSource#batchExecution` for controlling how batches are executed

## Version 0.5.0

Date: 2017-01-26

- Make `DataSource#name` mandatory to implement
- Add `fetch-debug` project with debugging facilities for Fetch
- Update cats to 0.9.0
- Update Monix to 2.2.0

## Version 0.4.0

Date: 2016-11-14

- Added support for Scala 2.12.0
- Updated cats to 0.8.1 (and monix to the compatible 2.1.0).
- Support a maximum batch size per `DataSource` (https://github.com/47deg/fetch/pull/76).
- Provided a new implementation for Parallel joins. This new impl changes the way fetches are inspected to parallelize independent fetches. It does this by adding a new Join construct to the Fetch structure. This Join is parallelized in a new intermediate interpreter which inspects the actual Free constructs and replaces cached fetches by Free.Pure.
- Updated the scalafmt version which triggered some code reformatting. There is apparently an issue with sbt and scalafmt (sbt/sbt#2786) which keeps us from using sbt 0.13.13.

Thanks @peterneyens!

## Version 0.3.0

Date: 2016-11-08

- Improved and documented error handling and reporting facilities
- Simplify query constructors
- Minor changes to the DataSource trait, making their methods type parameters explicit
- Internal simplification and improvements, thanks @peterneyens

## Version 0.2.0

Date: 2016-05-22

- Delete the `MonadError[Id, Throwable]` instance in `fetch.implicits`, is not lawful
- Add fetch syntax in `fetch.syntax`, thanks to Raúl Raja
- Modify `DataSource#fetch` signature to receive a [NonEmptyList](https://github.com/typelevel/cats/blob/eb3caf83e879ed20df85b76c93014fa513a2c46c/core/src/main/scala/cats/data/package.scala#L4)
- Upgrade cats dependency to 0.6.0

## Version 0.1.1

Date: 2016-05-20

- Fix bug with incorrent handling of missing identities when performing concurrent fetches

## Version 0.1.0

Date: 2016-05-19

- First release.
