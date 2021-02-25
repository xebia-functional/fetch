ThisBuild / scalaVersion := scala213
ThisBuild / crossScalaVersions := allScalaVersions
ThisBuild / organization := "com.47deg"

addCommandAlias("ci-test", "scalafmtCheckAll; scalafmtSbtCheck; mdoc; testCovered")
addCommandAlias("ci-docs", "github; mdoc; headerCreateAll; publishMicrosite")
addCommandAlias("ci-publish", "github; ci-release")

lazy val scala212         = "2.12.12"
lazy val scala213         = "2.13.5"
lazy val scala3Version    = "3.0.0-RC1"
lazy val scala2Versions   = Seq(scala212, scala213)
lazy val allScalaVersions = scala2Versions :+ scala3Version

skip in publish := true

lazy val fetch = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .settings(commonCrossDependencies)

lazy val fetchJVM = fetch.jvm
lazy val fetchJS = fetch.js
  .disablePlugins(ScoverageSbtPlugin)
  .settings(crossScalaVersions := scala2Versions)

lazy val `fetch-debug` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .dependsOn(fetch)
  .settings(commonCrossDependencies)

lazy val debugJVM = `fetch-debug`.jvm
lazy val debugJS = `fetch-debug`.js
  .disablePlugins(ScoverageSbtPlugin)
  .settings(crossScalaVersions := scala2Versions)

//lazy val `fetch-examples` = project
//  .dependsOn(fetchJVM, debugJVM)
//  .settings(skip in publish := true)
//  .settings(examplesSettings: _*)

lazy val microsite = project
  .dependsOn(fetchJVM, debugJVM)
  .settings(docsSettings: _*)
  .settings(skip in publish := true)
  .enablePlugins(MicrositesPlugin, MdocPlugin)
  .settings(crossScalaVersions := scala2Versions)

lazy val documentation = project
  .dependsOn(fetchJVM)
  .settings(skip in publish := true)
  .settings(mdocOut := file("."))
  .enablePlugins(MdocPlugin)
  .settings(crossScalaVersions := scala2Versions)
