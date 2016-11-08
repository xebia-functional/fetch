## Changelog

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
