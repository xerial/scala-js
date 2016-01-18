/*                     __                                               *\
**     ________ ___   / /  ___      __ ____  Scala.js sbt plugin        **
**    / __/ __// _ | / /  / _ | __ / // __/  (c) 2013, LAMP/EPFL        **
**  __\ \/ /__/ __ |/ /__/ __ |/_// /_\ \    http://scala-js.org/       **
** /____/\___/_/ |_/____/_/ | |__/ /____/                               **
**                          |/____/                                     **
\*                                                                      */


package org.scalajs.sbtplugin

import sbt._

import org.scalajs.core.tools.sem.Semantics
import org.scalajs.core.tools.io._
import org.scalajs.core.tools.linker.LinkingUnit
import org.scalajs.core.tools.linker.backend.OutputMode
import org.scalajs.core.tools.jsdep.{JSDependencyManifest, ResolvedJSDependency}
import org.scalajs.core.tools.jsdep.ManifestFilters.ManifestFilter
import org.scalajs.core.tools.jsdep.DependencyResolver.DependencyFilter

import org.scalajs.core.ir.ScalaJSVersions

import org.scalajs.jsenv.{JSEnv, JSConsole}
import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.jsenv.phantomjs.PhantomJSEnv

object ScalaJSPlugin extends AutoPlugin {
  override def requires: Plugins = plugins.JvmPlugin

  /* The following module-case double definition is a workaround for a bug
   * somewhere in the sbt dependency macro - scala macro pipeline that affects
   * the %%% operator on dependencies (see #1331).
   *
   * If the object AutoImport is written lower-case, it is wrongly identified as
   * dynamic dependency (only if the usage code is generated by a macro). On the
   * other hand, only lower-case autoImport is automatically imported by sbt (in
   * an AutoPlugin, therefore the alias.
   *
   * We do not know *why* this fixes the issue, but it does.
   */
  val autoImport = AutoImport

  object AutoImport extends impl.DependencyBuilders
                       with cross.CrossProjectExtra {
    import KeyRanks._

    // Some constants
    val scalaJSVersion = ScalaJSVersions.current
    val scalaJSIsSnapshotVersion = ScalaJSVersions.currentIsSnapshot
    val scalaJSBinaryVersion = ScalaJSCrossVersion.currentBinaryVersion

    // Stage values
    @deprecated("Use FastOptStage instead", "0.6.6")
    val PreLinkStage = Stage.FastOpt
    val FastOptStage = Stage.FastOpt
    val FullOptStage = Stage.FullOpt

    // CrossType
    val CrossType = cross.CrossType

    // Factory methods for JSEnvs

    /**
     *  Creates a [[sbt.Def.Initialize Def.Initialize]] for a NodeJSEnv. Use
     *  this to explicitly specify in your build that you would like to run with Node.js:
     *
     *  {{{
     *  jsEnv := NodeJSEnv().value
     *  }}}
     *
     *  Note that the resulting [[sbt.Def.Setting Setting]] is not scoped at
     *  all, but must be scoped in a project that has the ScalaJSPlugin enabled
     *  to work properly.
     *  Therefore, either put the upper line in your project settings (common
     *  case) or scope it manually, using
     *  [[sbt.ProjectExtra.inScope[* Project.inScope]].
     */
    def NodeJSEnv(
        executable: String = "node",
        args: Seq[String] = Seq.empty,
        env: Map[String, String] = Map.empty
    ): Def.Initialize[Task[NodeJSEnv]] = Def.task {
      new NodeJSEnv(executable, args, env)
    }

    /**
     *  Creates a [[sbt.Def.Initialize Def.Initialize]] for a PhantomJSEnv. Use
     *  this to explicitly specify in your build that you would like to run with
     *  PhantomJS:
     *
     *  {{{
     *  jsEnv := PhantomJSEnv().value
     *  }}}
     *
     *  Note that the resulting [[sbt.Def.Setting Setting]] is not scoped at
     *  all, but must be scoped in a project that has the ScalaJSPlugin enabled
     *  to work properly.
     *  Therefore, either put the upper line in your project settings (common
     *  case) or scope it manually, using
     *  [[sbt.ProjectExtra.inScope[* Project.inScope]].
     */
    def PhantomJSEnv(
        executable: String = "phantomjs",
        args: Seq[String] = Seq.empty,
        env: Map[String, String] = Map.empty,
        autoExit: Boolean = true
    ): Def.Initialize[Task[PhantomJSEnv]] = Def.task {
      val loader = scalaJSPhantomJSClassLoader.value
      new PhantomJSEnv(executable, args, env, autoExit, loader)
    }

    // All our public-facing keys

    val fastOptJS = TaskKey[Attributed[File]]("fastOptJS",
        "Quickly link all compiled JavaScript into a single file", APlusTask)

    val fullOptJS = TaskKey[Attributed[File]]("fullOptJS",
        "Link all compiled JavaScript into a single file and fully optimize", APlusTask)

    val scalaJSIR = TaskKey[Attributed[Seq[VirtualScalaJSIRFile with RelativeVirtualFile]]](
        "scalaJSIR", "All the *.sjsir files on the classpath", CTask)

    val scalaJSNativeLibraries = TaskKey[Attributed[Seq[VirtualJSFile with RelativeVirtualFile]]](
        "scalaJSNativeLibraries", "All the *.js files on the classpath", CTask)

    val scalaJSStage = SettingKey[Stage]("scalaJSStage",
        "The optimization stage at which run and test are executed", APlusSetting)

    val packageScalaJSLauncher = TaskKey[Attributed[File]]("packageScalaJSLauncher",
        "Writes the persistent launcher file. Fails if the mainClass is ambigous", CTask)

    val packageJSDependencies = TaskKey[File]("packageJSDependencies",
        "Packages all dependencies of the preLink classpath in a single file.", AMinusTask)

    val packageMinifiedJSDependencies = TaskKey[File]("packageMinifiedJSDependencies",
        "Packages minified version (if available) of dependencies of the preLink " +
        "classpath in a single file.", AMinusTask)

    val jsDependencyManifest = TaskKey[File]("jsDependencyManifest",
        "Writes the JS_DEPENDENCIES file.", DTask)

    val jsDependencyManifests = TaskKey[Attributed[Traversable[JSDependencyManifest]]](
        "jsDependencyManifests", "All the JS_DEPENDENCIES on the classpath", DTask)

    val scalaJSLinkedFile = TaskKey[VirtualJSFile]("scalaJSLinkedFile",
        "Linked Scala.js file. This is the result of fastOptJS or fullOptJS, " +
        "depending on the stage.", DTask)

    val scalaJSLauncher = TaskKey[Attributed[VirtualJSFile]]("scalaJSLauncher",
        "Code used to run. (Attributed with used class name)", DTask)

    val scalaJSConsole = TaskKey[JSConsole]("scalaJSConsole",
        "The JS console used by the Scala.js runner/tester", DTask)

    val scalaJSUseRhino = SettingKey[Boolean]("scalaJSUseRhino",
        "Whether Rhino should be used", APlusSetting)

    val jsEnv = TaskKey[JSEnv]("jsEnv",
        "A JVM-like environment where Scala.js files can be run and tested.", AMinusTask)

    val resolvedJSEnv = TaskKey[JSEnv]("resolvedJSEnv",
        "The JSEnv used for execution. This equals the setting of jsEnv or a " +
        "reasonable default value if jsEnv is not set.", DTask)

    @deprecated("Use jsEnv instead.", "0.6.6")
    val preLinkJSEnv = jsEnv

    @deprecated("Use jsEnv instead.", "0.6.6")
    val postLinkJSEnv = jsEnv

    val requiresDOM = SettingKey[Boolean]("requiresDOM",
        "Whether this projects needs the DOM. Overrides anything inherited through dependencies.", AMinusSetting)

    val relativeSourceMaps = SettingKey[Boolean]("relativeSourceMaps",
        "Make the referenced paths on source maps relative to target path", BPlusSetting)

    val emitSourceMaps = SettingKey[Boolean]("emitSourceMaps",
        "Whether package and optimize stages should emit source maps at all", BPlusSetting)

    val scalaJSOutputWrapper = SettingKey[(String, String)]("scalaJSOutputWrapper",
        "Custom wrapper for the generated .js files. Formatted as tuple (header, footer).", BPlusSetting)

    val jsDependencies = SettingKey[Seq[AbstractJSDep]]("jsDependencies",
        "JavaScript libraries this project depends upon. Also used to depend on the DOM.", APlusSetting)

    val scalaJSSemantics = SettingKey[Semantics]("scalaJSSemantics",
        "Configurable semantics of Scala.js.", BPlusSetting)

    val scalaJSOutputMode = SettingKey[OutputMode]("scalaJSOutputMode",
        "Output mode of Scala.js.", BPlusSetting)

    val jsDependencyFilter = SettingKey[DependencyFilter]("jsDependencyFilter",
        "The filter applied to the raw JavaScript dependencies before execution", CSetting)

    val jsManifestFilter = SettingKey[ManifestFilter]("jsManifestFilter",
        "The filter applied to JS dependency manifests before resolution", CSetting)

    val resolvedJSDependencies = TaskKey[Attributed[Seq[ResolvedJSDependency]]]("resolvedJSDependencies",
        "JS dependencies after resolution.", DTask)

    val checkScalaJSSemantics = SettingKey[Boolean]("checkScalaJSSemantics",
        "Whether to check that the current semantics meet compliance " +
        "requirements of dependencies.", CSetting)

    val persistLauncher = SettingKey[Boolean]("persistLauncher",
        "Tell optimize/package tasks to write the laucher file to disk. " +
        "If this is set, your project may only have a single mainClass or you must explicitly set it", AMinusSetting)

    val scalaJSOptimizerOptions = SettingKey[OptimizerOptions]("scalaJSOptimizerOptions",
        "All kinds of options for the Scala.js optimizer stages", DSetting)

    val loadedJSEnv = TaskKey[JSEnv]("loadedJSEnv",
        "A JSEnv already loaded up with library and Scala.js code. Ready to run.", DTask)

    /** Class loader for PhantomJSEnv. Used to load jetty8. */
    val scalaJSPhantomJSClassLoader = TaskKey[ClassLoader]("scalaJSPhantomJSClassLoader",
        "Private class loader to load jetty8 without polluting classpath. Only use this " +
        "as the `jettyClassLoader` argument of the PhantomJSEnv",
        KeyRanks.Invisible)

    /** Prints the content of a .sjsir file in human readable form. */
    val scalajsp = InputKey[Unit]("scalajsp",
        "Prints the content of a .sjsir file in human readable form.",
        CTask)
  }

  import autoImport._
  import ScalaJSPluginInternal._

  override def globalSettings: Seq[Setting[_]] = {
    super.globalSettings ++ Seq(
        scalaJSStage := Stage.FastOpt,
        scalaJSUseRhino := true,
        scalaJSClearCacheStats := globalIRCache.clearStats()
    )
  }

  override def projectSettings: Seq[Setting[_]] = (
      scalaJSAbstractSettings ++
      scalaJSEcosystemSettings
  )
}
