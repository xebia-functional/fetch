name := "uranio"

version := "0.1.0"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats" % "0.4.1",
  "org.specs2" %% "specs2-core" % "3.7.2" % "test"
)

scalacOptions in Test ++= Seq("-Yrangepos")
