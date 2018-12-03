lazy val `sbt-js-engine` = project in file(".")

description := "sbt js engine plugin"

libraryDependencies ++= Seq(
  "io.spray" %% "spray-json" % "1.3.3",

  // Trireme
  "io.apigee.trireme" % "trireme-core" % "0.8.9",
  "io.apigee.trireme" % "trireme-node10src" % "0.8.9",

  // NPM
  "org.webjars" % "npm" % "4.2.0",
  "org.webjars" % "webjars-locator-core" % "0.35",

  // Test deps
  "org.specs2" %% "specs2-core" % "3.8.9" % "test",
  "org.specs2" %% "specs2-scalacheck" % "3.8.9" % "test",
  "junit" % "junit" % "4.12" % "test"
)

addSbtWeb("1.5.0-SNAPSHOT")

fork in Test := true
