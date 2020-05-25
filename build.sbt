ThisBuild / scalaVersion := "2.13.2"
ThisBuild / crossScalaVersions := Seq("2.12.11", "2.13.2")
ThisBuild / organization := "com.47deg"

addCommandAlias("ci-test", "scalafmtCheckAll; scalafmtSbtCheck; mdoc; testCovered")
addCommandAlias("ci-docs", "github; mdoc; headerCreateAll; publishMicrosite")
addCommandAlias("ci-publish", "github; ci-release")

skip in publish := true

lazy val fetch = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .settings(commonCrossDependencies)
lazy val fetchJVM = fetch.jvm
lazy val fetchJS  = fetch.js.disablePlugins(ScoverageSbtPlugin)

lazy val `fetch-debug` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .dependsOn(fetch)
  .settings(commonCrossDependencies)
lazy val debugJVM = `fetch-debug`.jvm
lazy val debugJS  = `fetch-debug`.js.disablePlugins(ScoverageSbtPlugin)

lazy val `fetch-examples` = project
  .dependsOn(fetchJVM, debugJVM)
  .settings(skip in publish := true)
  .settings(examplesSettings: _*)

lazy val microsite = project
  .dependsOn(fetchJVM, debugJVM)
  .settings(docsSettings: _*)
  .settings(skip in publish := true)
  .enablePlugins(MicrositesPlugin, MdocPlugin)

lazy val documentation = project
  .dependsOn(fetchJVM)
  .settings(skip in publish := true)
  .settings(mdocOut := file("."))
  .enablePlugins(MdocPlugin)
