name := "fetch"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats" % "0.4.1",
  "org.specs2" %% "specs2-core" % "3.7.2" % "test"
)

scalacOptions ++= Seq("-Ywarn-unused-import", "-Ywarn-dead-code")

resolvers += Resolver.sonatypeRepo("releases")

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.7.1")

