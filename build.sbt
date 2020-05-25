addCommandAlias("ci-test", "scalafmtCheckAll; scalafmtSbtCheck; +mdoc; testCovered")
addCommandAlias("ci-docs", "project-docs/mdoc; headerCreateAll")
addCommandAlias("ci-microsite", "docs/publishMicrosite")

skip in publish := true

lazy val fetch = crossProject(JSPlatform, JVMPlatform)
  .settings(commonCrossDependencies)
lazy val fetchJVM = fetch.jvm
lazy val fetchJS  = fetch.js.disablePlugins(ScoverageSbtPlugin)

lazy val `fetch-debug` = crossProject(JSPlatform, JVMPlatform)
  .dependsOn(fetch)
  .settings(commonCrossDependencies)
lazy val debugJVM = `fetch-debug`.jvm
lazy val debugJS  = `fetch-debug`.js.disablePlugins(ScoverageSbtPlugin)

lazy val `fetch-examples` = project
  .dependsOn(fetchJVM, debugJVM)
  .settings(skip in publish := true)
  .settings(examplesSettings: _*)

lazy val docs = (project in file("docs"))
  .dependsOn(fetchJVM, debugJVM)
  .settings(name := "fetch-docs")
  .settings(docsSettings: _*)
  .settings(skip in publish := true)
  .enablePlugins(MicrositesPlugin)
  .enablePlugins(MdocPlugin)

lazy val `project-docs` = (project in file(".docs"))
  .aggregate(fetchJVM)
  .dependsOn(fetchJVM)
  .settings(moduleName := "fetch-project-docs")
  .settings(mdocIn := file(".docs"))
  .settings(mdocOut := file("."))
  .settings(skip in publish := true)
  .enablePlugins(MdocPlugin)
