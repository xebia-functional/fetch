lazy val buildSettings = Seq(
  organization := "com.fortysevendeg",
  scalaVersion := "2.11.8",
  crossScalaVersions := Seq("2.10.6", "2.11.8")
)

lazy val commonSettings = Seq(
  resolvers += Resolver.sonatypeRepo("releases"),
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats" % "0.5.0",
    "org.specs2" %% "specs2-core" % "3.7.2" % "test",
    compilerPlugin(
      "org.spire-math" %% "kind-projector" % "0.7.1"
    )
  ),
  scalacOptions ++= Seq("-Ywarn-unused-import", "-Ywarn-dead-code")
)

lazy val fetch = project.in(file("."))
  .settings(moduleName := "fetch")
  .settings(buildSettings)
  .settings(commonSettings)

lazy val jsSettings = Seq(
  requiresDOM := false,
  jsEnv := NodeJSEnv().value
)

lazy val fetchJS = project.in(file(".fetchJS"))
  .settings(moduleName := "fetch")
  .settings(buildSettings)
  .settings(commonSettings)
  .enablePlugins(ScalaJSPlugin)

