## Changelog

## Version 0.3.0

Date: 2016-07-25

- Separate `DataSource#fetchOne` and `DataSource#fetchMany` into two methods
- Provide `DataSource#batchingNotSupported` for implementing `fetchMany` in terms of `fetchOne`
- Introduce `FetchMonadError[M]` typeclass for being able to run a fetch into the target monad `M`
- Support for blocking and non-blocking data sources through the `Query` type
- [Monix](http://monix.io) integration through the `fetch-monix` project

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

- First relase
