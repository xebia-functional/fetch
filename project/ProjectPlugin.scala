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

object ProjectPlugin extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  override def requires: Plugins = OrgPoliciesPlugin

  object autoImport {

    lazy val commonCrossDependencies: Seq[ModuleID] =
      Seq(%%("cats-free"),
        "org.typelevel" %% "cats-effect" % "1.0.0-RC3",
        %%("scalatest") % "test")

    lazy val monixCrossDependencies: Seq[ModuleID] =
      %%("monix-eval") :: Nil

    lazy val twitterUtilDependencies: Seq[ModuleID] = Seq(%%("catbird-util", "18.1.0"))

    lazy val micrositeSettings: Seq[Def.Setting[_]] = Seq(
      micrositeName := "Fetch",
      micrositeDescription := "Simple & Efficient data access for Scala and Scala.js",
      micrositeBaseUrl := "fetch",
      micrositeDocumentationUrl := "/fetch/docs.html",
      micrositeGithubOwner := "47deg",
      micrositeGithubRepo := "fetch",
      micrositeHighlightTheme := "tomorrow",
      micrositePalette := Map(
        "brand-primary"   -> "#FF518C",
        "brand-secondary" -> "#2F2859",
        "brand-tertiary"  -> "#28224C",
        "gray-dark"       -> "#48474C",
        "gray"            -> "#8D8C92",
        "gray-light"      -> "#E3E2E3",
        "gray-lighter"    -> "#F4F3F9",
        "white-color"     -> "#FFFFFF"
      ),
      includeFilter in makeSite := "*.html" | "*.css" | "*.png" | "*.jpg" | "*.gif" | "*.js" | "*.swf" | "*.md"
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

    lazy val examplesSettings: Seq[Def.Setting[_]] = libraryDependencies ++= Seq(
      %%("circe-generic"),
      %%("doobie-core"),
      %%("doobie-h2"),
      %%("http4s-blaze-client"),
      %%("http4s-circe")
    ) ++ commonCrossDependencies
  }

  lazy val commandAliases: Seq[Def.Setting[_]] =
    addCommandAlias("validate", ";clean;validateJS;validateJVM") ++
      addCommandAlias("validateDocs", List("docs/tut", "readme/tut", "project root").asCmd) ++
      addCommandAlias("validateCoverage", ";coverage;validate;coverageReport;coverageOff") ++
      addCommandAlias(
        "validateJVM",
        List(
          "fetchJVM/compile",
          "monixJVM/compile",
          "twitterJVM/compile",
          "fetchJVM/test",
          "monixJVM/test",
          "twitterJVM/test",
          "project root").asCmd) ++
      addCommandAlias(
        "validateJS",
        List("fetchJS/compile", "monixJS/compile", "fetchJS/test", "monixJS/test", "project root").asCmd)

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
          orgValidateFiles.asRunnableItem,
          "validateDocs".asRunnableItemFull,
          "validateCoverage".asRunnableItemFull
        ),
        orgUpdateDocFilesSetting += baseDirectory.value / "tut",
        scalaOrganization := "org.scala-lang",
        scalaVersion := "2.12.6",
        crossScalaVersions := List("2.11.12", "2.12.6"),
        resolvers += Resolver.sonatypeRepo("snapshots"),
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
