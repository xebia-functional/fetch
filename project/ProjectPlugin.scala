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

object ProjectPlugin extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  override def requires: Plugins = OrgPoliciesPlugin

  object autoImport {

    lazy val commonCrossDependencies =
      Seq(
		libraryDependencies ++=
		Seq("org.typelevel" %% "cats-effect" % "1.2.0",
            %%("scalatest") % "test"))

    lazy val micrositeSettings: Seq[Def.Setting[_]] = Seq(
      micrositeName := "Fetch",
      micrositeDescription := "Simple & Efficient data fetching",
      micrositeBaseUrl := "fetch",
      micrositeDocumentationUrl := "/fetch/docs",
      micrositeGithubOwner := "47deg",
      micrositeGithubRepo := "fetch",
      micrositeHighlightTheme := "tomorrow",
      micrositeExternalLayoutsDirectory := (resourceDirectory in Compile).value / "microsite" / "_layouts",
      micrositeExternalIncludesDirectory := (resourceDirectory in Compile).value / "microsite" / "_includes",
      micrositeDataDirectory := (resourceDirectory in Compile).value / "microsite" / "_data",
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
          "css/custom.css",
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

    lazy val docsSettings: Seq[Def.Setting[_]] = micrositeSettings ++ commonTutSettings ++ Seq(
      aggregate in doc := true)

    lazy val readmeSettings: Seq[Def.Setting[_]] = commonTutSettings ++ Seq(
      tutSourceDirectory := (baseDirectory in LocalRootProject).value / "tut",
      tutTargetDirectory := baseDirectory.value.getParentFile,
      tutNameFilter := """README.md""".r
    )

    lazy val examplesSettings = Seq(libraryDependencies ++= Seq(
      %%("circe-generic"),
      %%("doobie-core"),
      %%("doobie-h2"),
      "org.tpolecat" %% "atto-core"    % "0.6.5",
      "org.http4s" %% "http4s-blaze-client" % "0.19.0-M2",
      "org.http4s" %% "http4s-circe" % "0.19.0-M2",
      "redis.clients" % "jedis" % "2.9.0",
      "io.monix" %% "monix" % "3.0.0-RC2"
    )) ++ commonCrossDependencies
  }

  lazy val commandAliases: Seq[Def.Setting[_]] =
    addCommandAlias("validate", ";clean;validateJS;validateJVM") ++
      addCommandAlias("validateDocs", List("docs/tut", "readme/tut", "project root").asCmd) ++
      addCommandAlias("validateCoverage", ";coverage;validate;coverageReport;coverageOff") ++
      addCommandAlias(
        "validateJVM",
        List(
          "fetchJVM/compile",
          "fetchJVM/test",
          "project root").asCmd) ++
      addCommandAlias(
        "validateJS",
        List("fetchJS/compile", "fetchJS/test", "project root").asCmd)

  override def projectSettings: Seq[Def.Setting[_]] =
    commandAliases ++
      Seq(
        description := "Simple & Efficient data access for Scala and Scala.js",
        orgProjectName := "Fetch",
        startYear := Option(2016),
        homepage := Option(url("http://47deg.github.io/fetch/")),
        orgBadgeListSetting := List(
          GitterBadge.apply(_),
          TravisBadge.apply(_),
          CodecovBadge.apply(_),
          MavenCentralBadge.apply(_),
          LicenseBadge.apply(_),
          ScalaLangBadge.apply(_),
          ScalaJSBadge.apply(_),
          GitHubIssuesBadge.apply(_)
        ),
        orgSupportedScalaJSVersion := Some("0.6.20"),
        orgScriptTaskListSetting := List(
          "validateDocs".asRunnableItemFull,
          "validateCoverage".asRunnableItemFull
        ),
        orgUpdateDocFilesSetting += baseDirectory.value / "tut",
        scalaOrganization := "org.scala-lang",
        scalaVersion := "2.12.8",
        crossScalaVersions := List("2.11.12", "2.12.8"),
        resolvers += Resolver.sonatypeRepo("snapshots"),
        resolvers += Resolver.sonatypeRepo("releases"),
        addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.7"),
        addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.2.4"),
        scalacOptions := Seq(
          "-unchecked",
          "-deprecation",
          "-feature",
          "-Ywarn-dead-code",
          "-language:higherKinds",
          "-language:existentials",
          "-language:postfixOps",
          "-Ypartial-unification"
        ),
        ScoverageKeys.coverageFailOnMinimum := false
      ) ++ shellPromptSettings

}
