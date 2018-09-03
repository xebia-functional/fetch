pgpPassphrase := Some(getEnvVar("PGP_PASSPHRASE").getOrElse("").toCharArray)
pgpPublicRing := file(s"$gpgFolder/pubring.gpg")
pgpSecretRing := file(s"$gpgFolder/secring.gpg")

addCommandAlias("makeDocs", ";docs/makeMicrosite")

lazy val root = project
  .in(file("."))
  .settings(name := "fetch")
  .settings(moduleName := "root")
  .aggregate(fetchJS, fetchJVM, debugJVM, debugJS)

lazy val fetch = crossProject
  .in(file("."))
  .settings(name := "fetch")
  .jsSettings(sharedJsSettings: _*)
  .crossDepSettings(commonCrossDependencies: _*)

lazy val fetchJVM = fetch.jvm
lazy val fetchJS  = fetch.js

lazy val debug = (crossProject in file("debug"))
  .settings(name := "fetch-debug")
  .dependsOn(fetch)
  .jsSettings(sharedJsSettings: _*)
  .crossDepSettings(commonCrossDependencies: _*)

lazy val debugJVM = debug.jvm
lazy val debugJS  = debug.js

lazy val examples = (project in file("examples"))
  .settings(name := "fetch-examples")
  .dependsOn(fetchJVM)
  .settings(noPublishSettings: _*)
  .settings(examplesSettings: _*)

lazy val docs = (project in file("docs"))
  .dependsOn(fetchJVM, debugJVM)
  .settings(name := "fetch-docs")
  .settings(docsSettings: _*)
  .settings(noPublishSettings)
  .enablePlugins(MicrositesPlugin)

lazy val readme = (project in file("tut"))
  .settings(name := "fetch-readme")
  .dependsOn(fetchJVM)
  .settings(readmeSettings: _*)
  .settings(noPublishSettings)
  .enablePlugins(TutPlugin)
