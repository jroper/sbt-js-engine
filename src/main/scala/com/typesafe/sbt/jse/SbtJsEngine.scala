package com.typesafe.sbt.jse

import sbt._
import sbt.Keys._
import com.typesafe.jse._
import scala.collection.immutable
import com.typesafe.npm.Npm
import akka.util.Timeout
import scala.concurrent.{ExecutionContext, Await}
import com.typesafe.jse.Node
import com.typesafe.sbt.web.SbtWeb
import scala.concurrent.duration._
import scala.util.Try

object JsEngineImport {

  object JsEngineKeys {

    object EngineType extends Enumeration {
      val CommonNode, Node, PhantomJs, Rhino, Trireme = Value
    }

    val command = SettingKey[Option[File]]("jse-command", "An optional path to the command used to invoke the engine.")
    val engineType = SettingKey[EngineType.Value]("jse-engine-type", "The type of engine to use.")
    val parallelism = SettingKey[Int]("jse-parallelism", "The number of parallel tasks for the JavaScript engine. Defaults to the # of available processors + 1 to keep things busy.")

    val npmTimeout = SettingKey[FiniteDuration]("jse-npm-timeout", "The maximum number amount of time for npm to do its thing.")
    val npmNodeModules = TaskKey[Seq[File]]("jse-npm-node-modules", "Node module files generated by NPM.")
  }

}

/**
 * Declares the main parts of a WebDriver based plugin for sbt.
 */
object SbtJsEngine extends AutoPlugin {

  override def requires = SbtWeb

  override def trigger = AllRequirements

  val autoImport = JsEngineImport

  import SbtWeb.autoImport._
  import WebKeys._
  import autoImport._
  import JsEngineKeys._

  /**
   * Convert an engine type enum to an actor props.
   */
  def engineTypeToProps(engineType: EngineType.Value, command: Option[File], env: Map[String, String]) = {
    engineType match {
      case EngineType.CommonNode => CommonNode.props(command, stdEnvironment = env)
      case EngineType.Node => Node.props(command, stdEnvironment = env)
      case EngineType.PhantomJs => PhantomJs.props(command)
      case EngineType.Rhino => Rhino.props()
      case EngineType.Trireme => Trireme.props(stdEnvironment = env)
    }
  }

  private val NodeModules = "node_modules"
  private val PackageJson = "package.json"

  private val jsEngineUnscopedSettings: Seq[Setting[_]] = Seq(
    npmNodeModules := Def.task {
      val npmDirectory = baseDirectory.value / NodeModules
      val npmPackageJson = baseDirectory.value / PackageJson
      val cacheDirectory = streams.value.cacheDirectory / "npm"
      val runUpdate = FileFunction.cached(cacheDirectory, FilesInfo.hash) {
        _ =>
          if (npmPackageJson.exists) {
            implicit val timeout = Timeout(npmTimeout.value)
            val pendingExitValue = SbtWeb.withActorRefFactory(state.value, this.getClass.getName) {
              arf =>
                val webJarsNodeModulesPath = (webJarsNodeModulesDirectory in Plugin).value.getCanonicalPath
                val nodePathEnv = LocalEngine.nodePathEnv(immutable.Seq(webJarsNodeModulesPath))
                val engineProps = engineTypeToProps(engineType.value, command.value, nodePathEnv)
                val engine = arf.actorOf(engineProps)
                val npm = new Npm(engine, (webJarsNodeModulesDirectory in Plugin).value / "npm" / "lib" / "npm.js")
                import ExecutionContext.Implicits.global
                for (
                  result <- npm.update(global = false, Seq("--prefix", baseDirectory.value.getPath))
                ) yield {
                  // TODO: We need to stream the output and error channels. The js engine needs to change in this regard so that the
                  // stdio sink and sources can be exposed through the NPM library and then adopted here.
                  val logger = streams.value.log
                  new String(result.output.toArray, "UTF-8").split("\n").foreach(s => logger.info(s))
                  new String(result.error.toArray, "UTF-8").split("\n").foreach(s => if (result.exitValue == 0) logger.info(s) else logger.error(s))
                  result.exitValue
                }
            }
            if (Await.result(pendingExitValue, timeout.duration) != 0) {
              sys.error("Problems with NPM resolution. Aborting build.")
            }
            npmDirectory.***.get.toSet
          } else {
            IO.delete(npmDirectory)
            Set.empty
          }
      }
      runUpdate(Set(npmPackageJson)).toSeq
    }.dependsOn(webJarsNodeModules in Plugin).value,

    nodeModuleGenerators <+= npmNodeModules,
    nodeModuleDirectories += baseDirectory.value / NodeModules
  )

  private val defaultEngineType = EngineType.Trireme

  override def projectSettings: Seq[Setting[_]] = Seq(
    engineType := sys.props.get("sbt.jse.engineType").fold(defaultEngineType)(engineTypeStr =>
      Try(EngineType.withName(engineTypeStr)).getOrElse {
        println(s"Unknown engine type $engineTypeStr for sbt.jse.engineType. Resorting back to the default of $defaultEngineType.")
        defaultEngineType
      }),
    command := sys.props.get("sbt.jse.command").map(file),
    parallelism := java.lang.Runtime.getRuntime.availableProcessors() + 1,
    npmTimeout := 2.hours

  ) ++ inConfig(Assets)(jsEngineUnscopedSettings) ++ inConfig(TestAssets)(jsEngineUnscopedSettings)

}