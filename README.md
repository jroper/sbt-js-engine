sbt-js-engine
=============

[![Build Status](https://api.travis-ci.org/sbt/sbt-js-engine.png?branch=master)](https://travis-ci.org/sbt/sbt-js-engine) [![Download](https://api.bintray.com/packages/sbt-web/sbt-plugin-releases/sbt-js-engine/images/download.svg)](https://bintray.com/sbt-web/sbt-plugin-releases/sbt-js-engine/_latestVersion)

This plugin mainly provides support for the authoring of sbt plugins that require js-engine.

Of particular note is SbtJsTaskPlugin. This plugin provides an abstract base intended to be used for creating
the various types of plugin for sbt-web. At this time the types of plugin are "source file plugins" and "other".

Source file plugins use the `sources` and `sourceDirectories` sbt keys and process files from there. Plugins of this
type can optionally produce managed resources.

The other types of plugin are ones that wish to just invoke the js engine and so there are helper functions to do
that.

Enable this plugin in your project by adding it to your project's `plugins.sbt` file:

    addSbtPlugin("com.typesafe.sbt" % "sbt-js-engine" % "1.2.2")

The following options are provided:

Option              | Description
--------------------|------------
command             | The filesystem location of the command to execute. Commands such as "node" default to being known to your path. However there path can be supplied here."
engineType          | The type of engine to use i.e. CommonNode, Node, PhantomJs, Rhino, Trireme, or AutoDetect. The default is AutoDetect, which uses Node if installed or otherwise falls back to Trireme.
parallelism         | The number of parallel tasks for the JavaScript engine. Defaults to the # of available processors + 1 to keep things busy.
npmTimeout          | The maximum amount of time to wait for npm to do its thing.

The following sbt code illustrates how the engine type can be set to Node:

```scala
JsEngineKeys.engineType := JsEngineKeys.EngineType.Node
```

Alternatively, for `command` and `engineType` you can provide a system property via SBT_OPTS, for example:

```bash
export SBT_OPTS="$SBT_OPTS -Dsbt.jse.engineType=Node"
```

and another example:

```bash
export SBT_OPTS="$SBT_OPTS -Dsbt.jse.command=/usr/local/bin/node"
```

## npm

sbt-js-engine also enhances sbt-web with [npm](https://www.npmjs.org/) functionality. If a `package.json` file
is found in the project's base directory then it will cause npm to run.

npm extracts its artifacts into the `node_modules` folder of a base directory and makes the contents available to sbt-web plugins as a whole. Note that sbt-js-engines loads the actual source code of npm via a WebJar and invokes an "npm update". Any external npm activity can therefore be performed interchangeably with sbt-js-engine in place.

> Note that the npm functionality requires JDK 7 when running Trireme given the additional file system support required. If JDK 6 is required then use Node as the engine.

&copy; Lightbend Inc., 2018
