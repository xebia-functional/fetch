import de.heikoseeberger.sbtheader.AutomateHeaderPlugin
import de.heikoseeberger.sbtheader.license.Apache2_0

lazy val buildSettings = Seq(
  organization := "com.fortysevendeg",
  organizationName := "47 Degrees",
  description := "Simple & Efficient data access for Scala and Scala.js",
  startYear := Option(2016),
  homepage := Option(url("http://47deg.github.io/fetch/")),
  organizationHomepage := Option(new URL("http://47deg.com")),
  scalaVersion := "2.11.8",
  headers := Map(
    "scala" -> Apache2_0("2016", "47 Degrees, LLC. <http://www.47deg.com>")
  )
)

lazy val commonSettings = Seq(
  resolvers += Resolver.sonatypeRepo("releases"),
  libraryDependencies ++= Seq(
    "org.typelevel" %%% "cats" % "0.7.2",
    "org.scalatest" %%% "scalatest" % "3.0.0-M7" % "test",
    compilerPlugin(
      "org.spire-math" %% "kind-projector" % "0.7.1"
    )
  ),
  scalacOptions ++= Seq(
    "-unchecked",
    "-deprecation",
    "-feature",
    "-Ywarn-dead-code",
    "-language:higherKinds",
    "-language:existentials",
    "-language:postfixOps"
  ),
  scalafmtConfig := Some(file(".scalafmt"))
) ++ reformatOnCompileSettings

lazy val allSettings = buildSettings ++ commonSettings ++ publishSettings

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
  .settings(noPublishSettings)

lazy val docsSettings = ghpages.settings ++ buildSettings ++ tutSettings ++ Seq(
  git.remoteRepo := "git@github.com:47deg/fetch.git",
  tutSourceDirectory := sourceDirectory.value / "tut",
  tutTargetDirectory := sourceDirectory.value / "jekyll",
  tutScalacOptions ~= (_.filterNot(Set("-Ywarn-unused-import", "-Ywarn-dead-code"))),
  aggregate in doc := true
)

lazy val docs = (project in file("docs"))
  .settings(
    moduleName := "fetch-docs"
   )
  .dependsOn(fetchJVM, fetchMonixJVM)
  .enablePlugins(JekyllPlugin)
  .settings(docsSettings: _*)
  .settings(noPublishSettings)

addCommandAlias("makeDocs", ";docs/tut;docs/makeSite")

lazy val noPublishSettings = Seq(
  publish := (),
  publishLocal := (),
  publishArtifact := false
)

lazy val gpgFolder = sys.env.getOrElse("GPG_FOLDER", ".")

lazy val publishSettings = Seq(
  pgpPassphrase := Some(sys.env.getOrElse("GPG_PASSPHRASE", "").toCharArray),
  pgpPublicRing := file(s"$gpgFolder/pubring.gpg"),
  pgpSecretRing := file(s"$gpgFolder/secring.gpg"),
  credentials += Credentials("Sonatype Nexus Repository Manager",  "oss.sonatype.org",  sys.env.getOrElse("PUBLISH_USERNAME", ""),  sys.env.getOrElse("PUBLISH_PASSWORD", "")),
  scmInfo := Some(ScmInfo(url("https://github.com/47deg/fetch"), "https://github.com/47deg/fetch.git")),
  licenses := Seq("Apache License, Version 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := Function.const(false),
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("Snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("Releases" at nexus + "service/local/staging/deploy/maven2")
  },
  pomExtra :=
    <developers>
      <developer>
        <name>47 Degrees (twitter: @47deg)</name>
        <email>hello@47deg.com</email>
      </developer>
      <developer>
        <name>47 Degrees</name>
      </developer>
    </developers>
)

lazy val readmeSettings = buildSettings ++ tutSettings ++ Seq(
  tutSourceDirectory := baseDirectory.value,
  tutTargetDirectory := baseDirectory.value.getParentFile,
  tutScalacOptions ~= (_.filterNot(Set("-Ywarn-unused-import", "-Ywarn-dead-code"))),
  tutNameFilter := """README.md""".r
)

lazy val readme = (project in file("tut"))
  .settings(
    moduleName := "fetch-readme"
  )
  .dependsOn(fetchJVM)
  .settings(readmeSettings: _*)
  .settings(noPublishSettings)

lazy val monixSettings = (
  libraryDependencies ++= Seq(
    "io.monix" %%% "monix-eval" % "2.0"
  )
)

lazy val monix = crossProject.in(file("monix"))
  .dependsOn(fetch)
  .settings(moduleName := "fetch-monix")
  .settings(allSettings:_*)
  .jsSettings(fetchJSSettings:_*)
  .settings(monixSettings: _*)
  .enablePlugins(AutomateHeaderPlugin)


lazy val fetchMonixJVM = monix.jvm
lazy val fetchMonixJS = monix.js
