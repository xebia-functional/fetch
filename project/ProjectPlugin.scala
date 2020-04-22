import microsites.MicrositesPlugin.autoImport._
import com.typesafe.sbt.site.SitePlugin.autoImport._
import sbt.Keys._
import sbt._
import sbtorgpolicies.OrgPoliciesPlugin
import sbtorgpolicies.OrgPoliciesPlugin.autoImport._
import sbtorgpolicies.runnable.syntax._
import sbtorgpolicies.templates.badges._
import scoverage.ScoverageKeys
import tut.TutPlugin.autoImport._
import microsites._
import sbtorgpolicies.model.GitHubSettings

object ProjectPlugin extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  override def requires: Plugins = OrgPoliciesPlugin

  object autoImport {

    lazy val commonCrossDependencies =
      Seq(
        libraryDependencies ++=
          Seq(
            "org.typelevel" %% "cats-effect" % "2.0.0",
            "org.scalatest" %% "scalatest"   % "3.1.1" % "test"
          )
      )

    lazy val micrositeSettings: Seq[Def.Setting[_]] = Seq(
      micrositeName := "Fetch",
      micrositeCompilingDocsTool := WithTut,
      micrositeDescription := "Simple & Efficient data fetching",
      micrositeBaseUrl := "fetch",
      micrositeDocumentationUrl := "/fetch/docs",
      micrositeGithubOwner := "47degrees",
      micrositeGithubRepo := "fetch",
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
      micrositeGithubToken := getEnvVar("ORG_GITHUB_TOKEN"),
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

    lazy val commonTutSettings: Seq[Def.Setting[_]] = Seq(
      scalacOptions in Tut ~= (_.filterNot(Set("-Ywarn-unused-import", "-Ywarn-dead-code"))),
      scalacOptions in Tut ++= (scalaBinaryVersion.value match {
        case "2.10" => Seq("-Xdivergence211")
        case _      => Nil
      })
    )

    lazy val docsSettings: Seq[Def.Setting[_]] =
      micrositeSettings ++ commonTutSettings ++ Seq(aggregate in doc := true)

    lazy val readmeSettings: Seq[Def.Setting[_]] = commonTutSettings ++ Seq(
      tutSourceDirectory := (baseDirectory in LocalRootProject).value / "tut",
      tutTargetDirectory := baseDirectory.value.getParentFile,
      tutNameFilter := """README.md""".r
    )

    lazy val examplesSettings = Seq(
      libraryDependencies ++= Seq(
        "io.circe"      %% "circe-generic"       % "0.12.0-RC2",
        "org.tpolecat"  %% s"doobie-core"        % "0.8.0-RC1",
        "org.tpolecat"  %% s"doobie-h2"          % "0.8.0-RC1",
        "org.tpolecat"  %% "atto-core"           % "0.7.0",
        "org.http4s"    %% "http4s-blaze-client" % "0.21.0-M4",
        "org.http4s"    %% "http4s-circe"        % "0.21.0-M4",
        "redis.clients" % "jedis"                % "2.9.0",
        "io.monix"      %% "monix"               % "3.0.0"
      )
    ) ++ commonCrossDependencies
  }

  lazy val commandAliases: Seq[Def.Setting[_]] =
    addCommandAlias("validate", ";clean;validateJS;validateJVM") ++
      addCommandAlias("validateDocs", List("docs/tut", "readme/tut", "project root").asCmd) ++
      addCommandAlias("validateCoverage", ";coverage;validate;coverageReport;coverageOff") ++
      addCommandAlias(
        "validateJVM",
        List("fetchJVM/compile", "fetchJVM/test", "project root").asCmd
      ) ++
      addCommandAlias("validateJS", List("fetchJS/compile", "fetchJS/test", "project root").asCmd)

  override def projectSettings: Seq[Def.Setting[_]] =
    commandAliases ++
      Seq(
        description := "Simple & Efficient data access for Scala and Scala.js",
        orgGithubSetting := GitHubSettings(
          organization = "47degrees",
          project = (name in LocalRootProject).value,
          organizationName = "47 Degrees",
          groupId = "com.47deg",
          organizationHomePage = url("http://47deg.com"),
          organizationEmail = "hello@47deg.com"
        ),
        orgProjectName := "Fetch",
        startYear := Option(2016),
        homepage := Option(url("https://47degrees.github.io/fetch/")),
        orgBadgeListSetting := List(
          GitterBadge.apply,
          TravisBadge.apply,
          CodecovBadge.apply,
          MavenCentralBadge.apply,
          LicenseBadge.apply,
          ScalaLangBadge.apply,
          ScalaJSBadge.apply,
          GitHubIssuesBadge.apply
        ),
        orgSupportedScalaJSVersion := Some("0.6.20"),
        orgScriptTaskListSetting := List(
          "validateDocs".asRunnableItemFull,
          "validateCoverage".asRunnableItemFull
        ),
        orgUpdateDocFilesSetting += baseDirectory.value / "tut",
        scalaOrganization := "org.scala-lang",
        scalaVersion := "2.13.0",
        crossScalaVersions := List("2.12.10", "2.13.0"),
        resolvers += Resolver.sonatypeRepo("snapshots"),
        resolvers += Resolver.sonatypeRepo("releases"),
        addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.10.3"),
        addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1"),
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
        }),
        ScoverageKeys.coverageFailOnMinimum := false
      ) ++ shellPromptSettings

}
