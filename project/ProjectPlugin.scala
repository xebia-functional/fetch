import microsites.MicrositesPlugin.autoImport._
import com.typesafe.sbt.site.SitePlugin.autoImport._
import sbt.Keys._
import sbt._
import microsites._
import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._

object ProjectPlugin extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  object autoImport {

    lazy val commonCrossDependencies =
      Seq(
        libraryDependencies ++=
          Seq(
            "org.typelevel" %%% "cats-effect" % "3.3.13",
            "org.scalatest" %%% "scalatest"   % "3.2.12" % "test"
          )
      )

    lazy val micrositeSettings: Seq[Def.Setting[_]] = Seq(
      micrositeName             := "Fetch",
      micrositeDescription      := "Simple & Efficient data fetching",
      micrositeBaseUrl          := "fetch",
      micrositeDocumentationUrl := "/fetch/docs",
      micrositeHighlightTheme   := "tomorrow",
      micrositeExternalLayoutsDirectory := (Compile / resourceDirectory).value / "microsite" / "_layouts",
      micrositeExternalIncludesDirectory := (Compile / resourceDirectory).value / "microsite" / "_includes",
      micrositeDataDirectory := (Compile / resourceDirectory).value / "microsite" / "_data",
      micrositeTheme         := "pattern",
      micrositePalette := Map(
        "brand-primary"   -> "#DD4949",
        "brand-secondary" -> "#104051",
        "brand-tertiary"  -> "#EFF2F3",
        "gray-dark"       -> "#48474C",
        "gray"            -> "#8D8C92",
        "gray-light"      -> "#E3E2E3",
        "gray-lighter"    -> "#F4F3F9",
        "white-color"     -> "#FFFFFF"
      ),
      makeSite / includeFilter := "*.html" | "*.css" | "*.png" | "*.svg" | "*.jpg" | "*.gif" | "*.js" | "*.json" | "*.swf" | "*.md",
      micrositeGithubToken  := Option(System.getenv().get("GITHUB_TOKEN")),
      micrositePushSiteWith := GitHub4s,
      micrositeConfigYaml := ConfigYml(
        yamlPath = Some((Compile / resourceDirectory).value / "microsite" / "custom-config.yml")
      ),
      micrositeCDNDirectives := CdnDirectives(
        cssList = List(
          "css/custom.css"
        )
      )
    )

    lazy val docsSettings: Seq[Def.Setting[_]] =
      micrositeSettings ++ Seq(
        scalacOptions ~= (_.filterNot(Set("-Ywarn-unused-import", "-Ywarn-dead-code"))),
        doc / aggregate := true
      )

    lazy val examplesSettings = Seq(
      libraryDependencies ++= Seq(
        "io.circe"     %% "circe-generic"       % "0.14.2",
        "org.tpolecat" %% "doobie-core"         % "1.0.0-RC2",
        "org.tpolecat" %% "doobie-h2"           % "1.0.0-RC2",
        "org.tpolecat" %% "atto-core"           % "0.9.5",
        "org.http4s"   %% "http4s-blaze-client" % "0.23.12",
        "org.http4s"   %% "http4s-circe"        % "0.23.13",
        "redis.clients" % "jedis"               % "4.2.3"
      )
    ) ++ commonCrossDependencies
  }

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      scalacOptions := {
        val withStripedLinter = scalacOptions.value filterNot Set("-Xlint", "-Xfuture").contains
        (CrossVersion.partialVersion(scalaBinaryVersion.value) match {
          case Some((2, 13)) => withStripedLinter :+ "-Ymacro-annotations"
          case _             => withStripedLinter
        }) :+ "-language:higherKinds"
      },
      libraryDependencies ++= {
        CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((3, _)) => Seq()
          case _ =>
            Seq(
              compilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full),
              compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
            )
        }
      },
      scalacOptions := Seq(
        "-unchecked",
        "-deprecation",
        "-feature",
        "-Ywarn-dead-code",
        "-language:higherKinds",
        "-language:existentials",
        "-language:postfixOps"
      ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((3, _))  => Seq("-source:3.0-migration", "-Ykind-projector")
        case Some((2, 13)) => Seq("-Ywarn-dead-code")
        case _             => Seq("-Ywarn-dead-code", "-Ypartial-unification")
      })
    )

}
