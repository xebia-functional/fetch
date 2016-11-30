## Changelog

## Version 0.4.1

Date: Unreleased

- Make `DataSource#name` mandatory to implement
- Add `fetch-debug` project with debugging facilities for Fetch

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

- First relase.
