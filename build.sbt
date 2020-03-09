import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

pgpPassphrase := Some(getEnvVar("PGP_PASSPHRASE").getOrElse("").toCharArray)
pgpPublicRing := file(s"$gpgFolder/pubring.gpg")
pgpSecretRing := file(s"$gpgFolder/secring.gpg")

addCommandAlias("makeDocs", ";docs/makeMicrosite")

lazy val root = project
  .in(file("."))
  .settings(name := "fetch")
  .settings(moduleName := "root")
  .settings(orgGithubSetting := GitHubSettings(
    organization = "47degrees",
    project = (name in LocalRootProject).value,
    organizationName = "47 Degrees",
    groupId = "com.47deg",
    organizationHomePage = url("http://47deg.com"),
    organizationEmail = "hello@47deg.com"
  ))
  .aggregate(fetchJS, fetchJVM, debugJVM, debugJS)

lazy val fetch = crossProject(JSPlatform, JVMPlatform)
  .in(file("."))
  .settings(name := "fetch")
  .jsSettings(sharedJsSettings: _*)
  .settings(commonCrossDependencies)

lazy val fetchJVM = fetch.jvm
lazy val fetchJS  = fetch.js

lazy val debug = crossProject(JSPlatform, JVMPlatform)
  .in(file("debug"))
  .settings(name := "fetch-debug")
  .dependsOn(fetch)
  .jsSettings(sharedJsSettings: _*)
  .settings(commonCrossDependencies)

lazy val debugJVM = debug.jvm
lazy val debugJS  = debug.js

lazy val examples = (project in file("examples"))
  .settings(name := "fetch-examples")
  .dependsOn(fetchJVM, debugJVM)
  .settings(noPublishSettings: _*)
  .settings(examplesSettings: _*)
  .settings(Seq(
    resolvers += Resolver.sonatypeRepo("snapshots")
  ))

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
