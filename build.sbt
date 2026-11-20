import scala.sys.process.Process

val awssdk = "2.56.0"
val zio    = "2.1.21"

ThisBuild / organization     := "io.github.achird-labs"
ThisBuild / organizationName := "Achird Labs"
ThisBuild / scalaVersion     := "3.3.7"
ThisBuild / homepage         := Some(url("https://achird-labs.github.io/aws-emulator-jvm/"))
ThisBuild / licenses         := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / versionScheme    := Some("early-semver")
ThisBuild / developers := List(
  Developer("EtaCassiopeia", "Mohsen Zainalpour", "zainalpour@gmail.com", url("https://github.com/EtaCassiopeia"))
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/achird-labs/aws-emulator-jvm"),
    "scm:git@github.com:achird-labs/aws-emulator-jvm.git"
  )
)

/** Builds the native library the binding loads and answers its path. */
lazy val cargoBuild = taskKey[File]("Build emu-core with cargo and return the native library")

lazy val root = (project in file("."))
  .aggregate(jvm)
  .settings(publish / skip := true)

lazy val jvm = (project in file("jvm"))
  .settings(
    name := "aws-emulator-jvm",
    scalacOptions ++= Seq("-deprecation", "-feature", "-Werror"),
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "http-client-spi" % awssdk,
      "software.amazon.awssdk" % "dynamodb"        % awssdk % Test,
      "software.amazon.awssdk" % "s3"              % awssdk % Test,
      "software.amazon.awssdk" % "kms"             % awssdk % Test,
      "dev.zio"               %% "zio-test"        % zio    % Test,
      "dev.zio"               %% "zio-test-sbt"    % zio    % Test
    ),
    cargoBuild := {
      val workspace = (ThisBuild / baseDirectory).value
      val exit      = Process(Seq("cargo", "build", "-p", "emu-core"), workspace).!
      if (exit != 0) sys.error(s"cargo build -p emu-core failed with exit code $exit")
      workspace / "target" / "debug" / System.mapLibraryName("emu_core")
    },
    Test / fork := true,
    Test / javaOptions ++= Seq(
      "--enable-native-access=ALL-UNNAMED",
      s"-Demu.core.library=${cargoBuild.value.getAbsolutePath}"
    )
  )
