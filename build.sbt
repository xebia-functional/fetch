ThisBuild / scalaVersion := scala213
ThisBuild / organization := "com.47deg"

addCommandAlias("ci-test", "scalafmtCheckAll; scalafmtSbtCheck; mdoc; ++test")
addCommandAlias("ci-docs", "github; mdoc; headerCreateAll; publishMicrosite")
addCommandAlias("ci-publish", "github; ci-release")

lazy val scala212         = "2.12.12"
lazy val scala213         = "2.13.5"
lazy val scala3Version    = "3.0.0-RC2"
lazy val scala2Versions   = Seq(scala212, scala213)
lazy val allScalaVersions = scala2Versions :+ scala3Version

publish / skip := true

lazy val fetch = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .settings(commonCrossDependencies)
  .settings(crossScalaVersions := allScalaVersions)

lazy val fetchJVM = fetch.jvm
lazy val fetchJS = fetch.js
  .settings(crossScalaVersions := scala2Versions)

lazy val `fetch-debug` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .dependsOn(fetch)
  .settings(commonCrossDependencies)
  .settings(crossScalaVersions := allScalaVersions)

lazy val debugJVM = `fetch-debug`.jvm
lazy val debugJS = `fetch-debug`.js
  .settings(crossScalaVersions := scala2Versions)

lazy val `fetch-examples` = project
  .dependsOn(fetchJVM, debugJVM)
  .settings(publish / skip := true)
  .settings(examplesSettings: _*)
  .settings(crossScalaVersions := scala2Versions)

lazy val microsite = project
  .dependsOn(fetchJVM, debugJVM)
  .settings(docsSettings: _*)
  .settings(publish / skip := true)
  .enablePlugins(MicrositesPlugin, MdocPlugin)
  .settings(crossScalaVersions := scala2Versions)

lazy val documentation = project
  .dependsOn(fetchJVM)
  .settings(publish / skip := true)
  .settings(mdocOut := file("."))
  .enablePlugins(MdocPlugin)
  .settings(crossScalaVersions := scala2Versions)
