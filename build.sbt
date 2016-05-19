import de.heikoseeberger.sbtheader.AutomateHeaderPlugin
import de.heikoseeberger.sbtheader.license.Apache2_0

lazy val buildSettings = Seq(
  organization := "com.fortysevendeg",
  scalaVersion := "2.11.8",
  crossScalaVersions := Seq("2.10.6", "2.11.8"),
  headers := Map(
    "scala" -> Apache2_0("2016", "47 Degrees, LLC. <http://www.47deg.com>")
  )
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
  scalacOptions ++= Seq(
    "-unchecked",
    "-deprecation",
    "-feature",
    "-Ywarn-unused-import",
    "-Ywarn-dead-code",
    "-language:higherKinds",
    "-language:existentials",
    "-language:postfixOps"
  ),
  scalafmtConfig := Some(file(".scalafmt"))
) ++ reformatOnCompileSettings

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
  .enablePlugins(AutomateHeaderPlugin)

lazy val fetchJVM = fetch.jvm
lazy val fetchJS = fetch.js

lazy val root = project.in(file("."))
  .aggregate(fetchJS, fetchJVM)
  .settings(
    publish := {},
    publishLocal := {}
  )

