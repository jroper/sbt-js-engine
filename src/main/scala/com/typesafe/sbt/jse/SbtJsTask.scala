package com.typesafe.sbt.jse

import java.util.concurrent.CopyOnWriteArrayList

import sbt.{Configuration, Def, _}
import sbt.Keys._
import com.typesafe.sbt.web.incremental.OpInputHasher
import spray.json._
import com.typesafe.sbt.web._
import xsbti.{Problem, Severity}
import com.typesafe.sbt.web.incremental.OpResult
import com.typesafe.sbt.web.incremental.OpFailure
import com.typesafe.sbt.web.incremental.OpInputHash

import scala.collection.immutable
import com.typesafe.sbt.jse.engines.{Engine, LocalEngine}
import com.typesafe.sbt.web.incremental
import com.typesafe.sbt.web.CompileProblems
import com.typesafe.sbt.web.incremental.OpSuccess
import sbinary.{Format, Input, Output}

import scala.concurrent.duration._

object JsTaskImport {

  object JsTaskKeys {

    val fileInputHasher = TaskKey[OpInputHasher[File]]("jstask-file-input-hasher", "A function that hashes a given file.")
    val jsOptions = TaskKey[String]("jstask-js-options", "The JSON options to be passed to the task.")
    val taskMessage = SettingKey[String]("jstask-message", "The message to output for a task")
    val shellFile = SettingKey[URL]("jstask-shell-url", "The url of the file to perform a given task.")
    val shellSource = TaskKey[File]("jstask-shell-source", "The target location of the js shell script to use.")
    @deprecated("Timeouts are no longer used", "1.3.0")
    val timeoutPerSource = SettingKey[FiniteDuration]("jstask-timeout-per-source", "The maximum amount of time to wait per source file processed by the JS task.")
    val sourceDependencies = SettingKey[Seq[TaskKey[Seq[File]]]]("jstask-source-dependencies", "Source dependencies between source file tasks.")
  }

}

/**
  * The commonality of JS task execution oriented plugins is captured by this class.
  */
object SbtJsTask extends AutoPlugin {

  override def requires: Plugins = SbtJsEngine

  override def trigger: PluginTrigger = AllRequirements

  val autoImport: JsTaskImport.type = JsTaskImport

  import SbtWeb.autoImport._
  import WebKeys._
  import SbtJsEngine.autoImport._
  import JsEngineKeys._
  import autoImport._
  import JsTaskKeys._

  val jsTaskSpecificUnscopedConfigSettings = Seq(
    fileInputHasher := {
      val options = jsOptions.value
      OpInputHasher[File](f => OpInputHash.hashString(f.getAbsolutePath + "|" + options))
    },
    resourceManaged := target.value / moduleName.value
  )

  val jsTaskSpecificUnscopedProjectSettings: Seq[Setting[_]] =
    inConfig(Assets)(jsTaskSpecificUnscopedConfigSettings) ++
      inConfig(TestAssets)(jsTaskSpecificUnscopedConfigSettings)

  val jsTaskSpecificUnscopedBuildSettings: Seq[Setting[_]] =
    Seq(
      shellSource := {
        SbtWeb.copyResourceTo(
          (target in Plugin).value / moduleName.value,
          shellFile.value,
          streams.value.cacheDirectory / "copy-resource"
        )
      }
    )

  @deprecated("Add jsTaskSpecificUnscopedProjectSettings to AutoPlugin.projectSettings and jsTaskSpecificUnscopedBuildSettings to AutoPlugin.buildSettings", "1.2.0")
  val jsTaskSpecificUnscopedSettings: Seq[Setting[_]] = jsTaskSpecificUnscopedProjectSettings ++ jsTaskSpecificUnscopedBuildSettings

  override def projectSettings: Seq[Setting[_]] = Seq(
    jsOptions := "{}"
  )


  /**
    * Thrown when there is an unexpected problem to do with the task's execution.
    */
  class JsTaskFailure(m: String) extends RuntimeException(m)

  /**
    * For automatic transformation of Json structures.
    */
  object JsTaskProtocol extends DefaultJsonProtocol {

    implicit object FileFormat extends JsonFormat[File] {
      def write(f: File): JsValue = JsString(f.getCanonicalPath)

      def read(value: JsValue): sbt.File = value match {
        case s: JsString => new File(s.convertTo[String])
        case x => deserializationError(s"String expected for a file, instead got $x")
      }
    }

    implicit val opSuccessFormat: JsonFormat[OpSuccess] = jsonFormat2(OpSuccess)

    implicit object LineBasedProblemFormat extends JsonFormat[LineBasedProblem] {
      def write(p: LineBasedProblem) = JsObject(
        "message" -> JsString(p.message),
        "severity" -> {
          p.severity match {
            case Severity.Info => JsString("info")
            case Severity.Warn => JsString("warn")
            case Severity.Error => JsString("error")
          }
        },
        "lineNumber" -> JsNumber(p.position.line.get),
        "characterOffset" -> JsNumber(p.position.offset.get),
        "lineContent" -> JsString(p.position.lineContent),
        "source" -> FileFormat.write(p.position.sourceFile.get)
      )

      def read(value: JsValue): LineBasedProblem = value match {
        case o: JsObject => new LineBasedProblem(
          o.fields.get("message").fold("unknown message")(_.convertTo[String]),
          o.fields.get("severity").fold(Severity.Error) {
            case JsString("info") => Severity.Info
            case JsString("warn") => Severity.Warn
            case _ => Severity.Error
          },
          o.fields.get("lineNumber").fold(0)(_.convertTo[Int]),
          o.fields.get("characterOffset").fold(0)(_.convertTo[Int]),
          o.fields.get("lineContent").fold("unknown line content")(_.convertTo[String]),
          o.fields.get("source").fold(file(""))(_.convertTo[File])
        )
        case x => deserializationError(s"Object expected for the problem, instead got $x")
      }

    }

    implicit object OpResultFormat extends JsonFormat[OpResult] {

      def write(r: OpResult): JsValue = r match {
        case OpFailure => JsNull
        case s: OpSuccess => opSuccessFormat.write(s)
      }

      def read(value: JsValue): OpResult = value match {
        case o: JsObject => opSuccessFormat.read(o)
        case JsNull => OpFailure
        case x => deserializationError(s"Object expected for the op result, instead got $x")
      }
    }

    case class ProblemResultsPair(results: Seq[SourceResultPair], problems: Seq[LineBasedProblem])

    case class SourceResultPair(result: OpResult, source: File)

    implicit val sourceResultPairFormat: JsonFormat[SourceResultPair] = jsonFormat2(SourceResultPair)
    implicit val problemResultPairFormat: JsonFormat[ProblemResultsPair] = jsonFormat2(ProblemResultsPair)
  }

  // Used to signal when the script is sending back structured JSON data
  private val JsonEscapeChar: Char = 0x10

  private type FileOpResultMappings = Map[File, OpResult]

  private def FileOpResultMappings(s: (File, OpResult)*): FileOpResultMappings = Map(s: _*)


  private def executeJsOnEngine(engine: Engine, shellSource: File, args: Seq[String],
    stderrSink: String => Unit, stdoutSink: String => Unit): Seq[JsValue] = {

    val results = new CopyOnWriteArrayList[JsValue]()

    val result = engine.executeJs(
      shellSource,
      args.to[immutable.Seq],
      Map.empty,
      line => {
        // Extract structured JSON data out before forwarding to the logger
        if (line.indexOf(JsonEscapeChar) == -1) {
          stdoutSink(line)
        } else {
          val (out, json) = line.span(_ != JsonEscapeChar)
          if (!out.isEmpty) {
            stdoutSink(out)
          }
          results.add(JsonParser(json.drop(1)))
        }
      },
      stderrSink
    )

    if (result.exitValue != 0) {
      throw new JsTaskFailure("")
    }

    import scala.collection.JavaConverters._
    results.asScala.toList
  }

  private def executeSourceFilesJs(
    engine: Engine,
    shellSource: File,
    sourceFileMappings: Seq[PathMapping],
    target: File,
    options: String,
    stderrSink: String => Unit,
    stdoutSink: String => Unit
  ): (FileOpResultMappings, Seq[Problem]) = {

    val args = immutable.Seq(
      JsArray(sourceFileMappings.map(x => JsArray(JsString(x._1.getCanonicalPath), JsString(x._2))).toVector).toString(),
      target.getAbsolutePath,
      options
    )

    val results = executeJsOnEngine(engine, shellSource, args, stderrSink, stdoutSink)
    import JsTaskProtocol._
    val prp = results.foldLeft(ProblemResultsPair(Nil, Nil)) {
      (cumulative, result) =>
        val prp = result.convertTo[ProblemResultsPair]
        ProblemResultsPair(
          cumulative.results ++ prp.results,
          cumulative.problems ++ prp.problems
        )
    }
    (prp.results.map(sr => sr.source -> sr.result).toMap, prp.problems)
  }

  /*
   * For reading/writing binary representations of files.
   */
  private implicit object FileFormat extends Format[File] {

    import sbinary.DefaultProtocol._

    def reads(in: Input): File = file(StringFormat.reads(in))

    def writes(out: Output, fh: File): Unit = StringFormat.writes(out, fh.getAbsolutePath)
  }

  /**
    * Primary means of executing a JavaScript shell script for processing source files. unmanagedResources is assumed
    * to contain the source files to filter on.
    *
    * @param task   The task to resolve js task settings from - relates to the concrete plugin sub class
    * @param config The sbt configuration to use e.g. Assets or TestAssets
    * @return A task object
    */
  def jsSourceFileTask(
    task: TaskKey[Seq[File]],
    config: Configuration
  ): Def.Initialize[Task[Seq[File]]] = Def.task {

    val nodeModulePaths = (nodeModuleDirectories in Plugin).value.map(_.getCanonicalPath)
    val engine = SbtJsEngine.engineTypeToEngine(
      (engineType in task).value,
      (command in task).value,
      LocalEngine.nodePathEnv(nodeModulePaths.to[immutable.Seq])
    )

    val sources = ((Keys.sources in task in config).value ** ((includeFilter in task in config).value -- (excludeFilter in task in config).value)).get.map(f => new File(f.getCanonicalPath))

    val logger: Logger = streams.value.log
    val taskMsg = (taskMessage in task in config).value
    val taskShellSource = (shellSource in task in config).value
    val taskSourceDirectories = (sourceDirectories in task in config).value
    val taskResources = (resourceManaged in task in config).value
    val options = (jsOptions in task in config).value

    implicit val opInputHasher: OpInputHasher[File] = (fileInputHasher in task in config).value
    val results: (Set[File], Seq[Problem]) = incremental.syncIncremental((streams in config).value.cacheDirectory / "run", sources) {
      modifiedSources: Seq[File] =>

        if (modifiedSources.nonEmpty) {

          logger.info(s"$taskMsg on ${modifiedSources.size} source(s)")

          val results: Seq[(FileOpResultMappings, Seq[Problem])] = {
            modifiedSources.map { sources =>
              executeSourceFilesJs(
                engine,
                taskShellSource,
                sources.pair(Path.relativeTo(taskSourceDirectories)),
                taskResources,
                options,
                m => logger.error(m),
                m => logger.info(m)
              )
            }
          }

          results.foldLeft((FileOpResultMappings(), Seq[Problem]())) { (allCompletedResults, completedResult) =>

            val (prevOpResults, prevProblems) = allCompletedResults

            val (nextOpResults, nextProblems) = completedResult

            (prevOpResults ++ nextOpResults, prevProblems ++ nextProblems)
          }

        } else {
          (FileOpResultMappings(), Nil)
        }
    }

    val (filesWritten, problems) = results

    CompileProblems.report((reporter in task).value, problems)

    filesWritten.toSeq
  }

  private def addUnscopedJsSourceFileTasks(sourceFileTask: TaskKey[Seq[File]]): Seq[Setting[_]] = {
    Seq(
      resourceGenerators += sourceFileTask.taskValue,
      managedResourceDirectories += (resourceManaged in sourceFileTask).value
    ) ++ inTask(sourceFileTask)(Seq(
      managedSourceDirectories ++= Def.settingDyn {
        sourceDependencies.value.map(resourceManaged in _).join
      }.value,
      managedSources ++= Def.taskDyn {
        sourceDependencies.value.join.map(_.flatten)
      }.value,
      sourceDirectories := unmanagedSourceDirectories.value ++ managedSourceDirectories.value,
      sources := unmanagedSources.value ++ managedSources.value
    ))
  }

  /**
    * Convenience method to add a source file task into the Asset and TestAsset configurations, along with adding the
    * source file tasks in to their respective collection.
    *
    * @param sourceFileTask The task key to declare.
    * @return The settings produced.
    */
  def addJsSourceFileTasks(sourceFileTask: TaskKey[Seq[File]]): Seq[Setting[_]] = {
    Seq(
      sourceDependencies in sourceFileTask := Nil,
      sourceFileTask in Assets := jsSourceFileTask(sourceFileTask, Assets).dependsOn(nodeModules in Plugin).value,
      sourceFileTask in TestAssets := jsSourceFileTask(sourceFileTask, TestAssets).dependsOn(nodeModules in Plugin).value,
      resourceManaged in sourceFileTask in Assets := webTarget.value / sourceFileTask.key.label / "main",
      resourceManaged in sourceFileTask in TestAssets := webTarget.value / sourceFileTask.key.label / "test",
      sourceFileTask := (sourceFileTask in Assets).value
    ) ++
      inConfig(Assets)(addUnscopedJsSourceFileTasks(sourceFileTask)) ++
      inConfig(TestAssets)(addUnscopedJsSourceFileTasks(sourceFileTask))
  }

  /**
    * Execute some arbitrary JavaScript.
    *
    * This method is intended to assist in building SBT tasks that execute generic JavaScript.  For example:
    *
    * {{{
    * myTask := {
    *   executeJs(state.value, engineType.value, Seq((nodeModules in Plugin).value.getCanonicalPath,
    *     baseDirectory.value / "path" / "to" / "myscript.js", Seq("arg1", "arg2"), 30.seconds)
    * }
    * }}}
    *
    * @param state       The SBT state.
    * @param engineType  The type of engine to use.
    * @param command     An optional path to the engine.
    * @param nodeModules The node modules to provide (if the JavaScript engine in use supports this).
    * @param shellSource The script to execute.
    * @param args        The arguments to pass to the script.
    * @param timeout     The maximum amount of time to wait for the script to finish.
    * @return A JSON status object if one was sent by the script.  A script can send a JSON status object by, as the
    *         last thing it does, sending a DLE character (0x10) followed by some JSON to std out.
    */
  def executeJs(
    state: State,
    engineType: EngineType.Value,
    command: Option[File],
    nodeModules: Seq[String],
    shellSource: File,
    args: Seq[String],
  ): Seq[JsValue] = {
    val engine = SbtJsEngine.engineTypeToEngine(
      engineType,
      command,
      LocalEngine.nodePathEnv(nodeModules.to[immutable.Seq])
    )

    executeJsOnEngine(engine, shellSource, args, m => state.log.error(m), m => state.log.info(m))
  }

  @deprecated("Use the other executeJs instead", "1.3.0")
  def executeJs(
    state: State,
    engineType: EngineType.Value,
    command: Option[File],
    nodeModules: Seq[String],
    shellSource: File,
    args: Seq[String],
    timeout: FiniteDuration
  ): Seq[JsValue] = executeJs(state, engineType, command, nodeModules, shellSource, args)
}
