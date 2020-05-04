import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

lazy val checkScalafmt = "+scalafmtCheckAll; +scalafmtSbtCheck;"
lazy val checkDocs     = "+docs/mdoc;"
lazy val checkJSTests  = "+fetchJS/test; +debugJS/test;"
lazy val checkJVMTests =
  "+coverage; +fetchJVM/test; +debugJVM/test; +examples/test; +coverageReport; +coverageAggregate;"

addCommandAlias(
  "ci-test",
  s"$checkScalafmt $checkDocs $checkJSTests $checkJVMTests"
)
addCommandAlias("ci-docs", "project-docs/mdoc; headerCreateAll")
addCommandAlias("ci-microsite", "docs/publishMicrosite")

lazy val root = project
  .in(file("."))
  .settings(name := "fetch")
  .settings(moduleName := "root")
  .aggregate(fetchJS, fetchJVM, debugJVM, debugJS)

lazy val fetch = crossProject(JSPlatform, JVMPlatform)
  .in(file("."))
  .settings(name := "fetch")
  .settings(commonCrossDependencies)

lazy val fetchJVM = fetch.jvm
lazy val fetchJS  = fetch.js.disablePlugins(ScoverageSbtPlugin)

lazy val debug = crossProject(JSPlatform, JVMPlatform)
  .in(file("debug"))
  .settings(name := "fetch-debug")
  .dependsOn(fetch)
  .settings(commonCrossDependencies)

lazy val debugJVM = debug.jvm
lazy val debugJS  = debug.js.disablePlugins(ScoverageSbtPlugin)

lazy val examples = (project in file("examples"))
  .settings(name := "fetch-examples")
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
