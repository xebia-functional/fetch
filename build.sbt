lazy val buildSettings = Seq(
  organization := "com.fortysevendeg",
  scalaVersion := "2.11.8",
  crossScalaVersions := Seq("2.10.6", "2.11.8")
)

lazy val commonSettings = Seq(
  resolvers += Resolver.sonatypeRepo("releases"),
  libraryDependencies ++= Seq(
    "org.typelevel" %%% "cats" % "0.5.0",
    "org.scalatest" %%% "scalatest" % "3.0.0-M7" % "test",
    compilerPlugin(
      "org.spire-math" %% "kind-projector" % "0.7.1"
    )
  ),
  scalacOptions ++= Seq("-Ywarn-unused-import", "-Ywarn-dead-code")
)

lazy val allSettings = buildSettings ++ commonSettings

lazy val fetchJSSettings = Seq(
  requiresDOM := false,
  scalaJSUseRhino := false,
  jsEnv := NodeJSEnv().value
)

lazy val fetch = crossProject.in(file("."))
  .settings(moduleName := "fetch")
  .settings(allSettings:_*)
  .jsSettings(fetchJSSettings:_*)

lazy val fetchJVM = fetch.jvm
lazy val fetchJS = fetch.js

lazy val root = project.in(file("."))
  .aggregate(fetchJS, fetchJVM)
  .settings(
    publish := {},
    publishLocal := {}
  )
