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
            "org.typelevel" %%% "cats-effect" % "2.3.3",
            "org.scalatest" %%% "scalatest"   % "3.2.3" % "test"
          )
      )

    lazy val micrositeSettings: Seq[Def.Setting[_]] = Seq(
      micrositeName := "Fetch",
      micrositeDescription := "Simple & Efficient data fetching",
      micrositeBaseUrl := "fetch",
      micrositeDocumentationUrl := "/fetch/docs",
      micrositeHighlightTheme := "tomorrow",
      micrositeExternalLayoutsDirectory := (resourceDirectory in Compile).value / "microsite" / "_layouts",
      micrositeExternalIncludesDirectory := (resourceDirectory in Compile).value / "microsite" / "_includes",
      micrositeDataDirectory := (resourceDirectory in Compile).value / "microsite" / "_data",
      micrositeTheme := "pattern",
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
      includeFilter in makeSite := "*.html" | "*.css" | "*.png" | "*.svg" | "*.jpg" | "*.gif" | "*.js" | "*.json" | "*.swf" | "*.md",
      micrositeGithubToken := Option(System.getenv().get("GITHUB_TOKEN")),
      micrositePushSiteWith := GitHub4s,
      micrositeConfigYaml := ConfigYml(
        yamlPath = Some((resourceDirectory in Compile).value / "microsite" / "custom-config.yml")
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
        aggregate in doc := true
      )

    lazy val examplesSettings = Seq(
      libraryDependencies ++= Seq(
        "io.circe"     %% "circe-generic"       % "0.13.0",
        "org.tpolecat" %% "doobie-core"         % "0.10.0",
        "org.tpolecat" %% "doobie-h2"           % "0.10.0",
        "org.tpolecat" %% "atto-core"           % "0.9.1",
        "org.http4s"   %% "http4s-blaze-client" % "0.21.19",
        "org.http4s"   %% "http4s-circe"        % "0.21.19",
        "redis.clients" % "jedis"               % "3.5.1",
        "io.monix"     %% "monix"               % "3.3.0"
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
      addCompilerPlugin("org.typelevel" % "kind-projector"     % "0.11.3" cross CrossVersion.full),
      addCompilerPlugin("com.olegpy"   %% "better-monadic-for" % "0.3.1"),
      scalacOptions := Seq(
        "-unchecked",
        "-deprecation",
        "-feature",
        "-Ywarn-dead-code",
        "-language:higherKinds",
        "-language:existentials",
        "-language:postfixOps"
      ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 13)) => Seq()
        case _             => Seq("-Ypartial-unification")
      })
    )

}
