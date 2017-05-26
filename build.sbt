import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.example",
      scalaVersion := "2.11.11",
      version      := "0.1.0-SNAPSHOT"
    )),
    name := "Hello",
    libraryDependencies += scalaTest % Test,
    libraryDependencies += "org.apache.spark" %% "spark-core" % "2.1.1",
    libraryDependencies += "org.apache.spark" %% "spark-mllib" % "2.1.1",
    libraryDependencies += "org.scalanlp" %% "breeze-natives" % "0.12",
    resolvers ++= Seq(
      // other resolvers here
      // if you want to use snapshot builds (currently 0.12-SNAPSHOT), use this.
      "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
      "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/"
    )
  )
