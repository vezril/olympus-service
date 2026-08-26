import com.typesafe.sbt.packager.docker.Cmd

ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "com.experimentalneutron"
ThisBuild / organizationName := "experimentalneutron"

// sbt-dynver drives the version from git tags: v1.2.3 -> 1.2.3.
ThisBuild / dynverSeparator := "-"

lazy val pekkoVersion     = "1.1.3"
lazy val pekkoHttpVersion = "1.1.0"
lazy val circeVersion     = "0.14.10"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "olympus-service",
    Compile / mainClass := Some("com.experimentalneutron.olympus.Main"),
    // Fork: unforked, sbt returns the moment main does and kills the bound server.
    run / fork := true,
    run / connectInput := true,
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-source:3.3",
      "-Wunused:all",
      "-Xfatal-warnings"
    ),
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed"     % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream"          % pekkoVersion,
      "org.apache.pekko" %% "pekko-http"            % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-slf4j"           % pekkoVersion,
      "com.typesafe"      % "config"                % "1.4.3",
      "ch.qos.logback"    % "logback-classic"       % "1.5.12",
      "io.circe"         %% "circe-core"            % circeVersion,
      "io.circe"         %% "circe-generic"         % circeVersion,
      "io.circe"         %% "circe-parser"          % circeVersion,
      // test
      "org.apache.pekko" %% "pekko-http-testkit"    % pekkoHttpVersion % Test,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
      "org.scalatest"    %% "scalatest"             % "3.2.19"         % Test
    ),
    // Docker: non-root, slim JRE, no build tooling in the runtime image.
    dockerBaseImage    := "eclipse-temurin:21-jre-alpine",
    dockerUpdateLatest := false,
    Docker / packageName := "olympusservice",
    dockerExposedPorts := Seq(8080),
    // Non-root is native-packager's job; appending our own USER after CMD would
    // work by accident rather than by design.
    Docker / daemonUser := "olympus",
    Docker / daemonUserUid := Some("1001"),
    Docker / daemonGroup := "olympus",
    Docker / daemonGroupGid := Some("1001"),
    // native-packager's launcher is a bash script and jre-alpine ships no bash;
    // without this the container exits 127 with "env: 'bash': No such file".
    dockerCommands := dockerCommands.value.flatMap {
      case cmd @ Cmd("FROM", args @ _*) if args.mkString(" ").contains("mainstage") =>
        Seq(cmd, Cmd("RUN", "apk add --no-cache bash"))
      case other => Seq(other)
    }
  )
