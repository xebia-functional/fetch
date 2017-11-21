# Changelog

## 11/21/2017 - Version 0.7.1

Release changes:

* Cleanup CHANGELOG after failed release ([#141](https://github.com/47deg/fetch/pull/141))
* Upgrades to cats 1.0.0-RC1 ([#143](https://github.com/47deg/fetch/pull/143))


## 10/06/2017 - Version 0.7.0

Release changes:

* Update dependencies (Cats 1.0.0-MF) ([#137](https://github.com/47deg/fetch/pull/137))
* update name of twitter module ([#138](https://github.com/47deg/fetch/pull/138))
* Release Fetch v0.7.0 ([#139](https://github.com/47deg/fetch/pull/139))
* Release 0.7.0 ([#140](https://github.com/47deg/fetch/pull/140))


## 08/22/2017 - Version 0.6.3

Release changes:

* Add timeout capability to Future implementation of FetchMonadError ([#127](https://github.com/47deg/fetch/pull/127))
* adds commercial support statement ([#129](https://github.com/47deg/fetch/pull/129))
* Add twitter future support ([#128](https://github.com/47deg/fetch/pull/128))
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
* typo in README ([#120](https://github.com/47deg/fetch/pull/120))
* Refactor interpreters + Change Fetch.traverse ([#123](https://github.com/47deg/fetch/pull/123))
* Releases 0.6.2 ([#125](https://github.com/47deg/fetch/pull/125))


## 04/19/2017 - Version 0.6.1

Release changes:

* add batchingOnly convenience method ([#110](https://github.com/47deg/fetch/pull/110))
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