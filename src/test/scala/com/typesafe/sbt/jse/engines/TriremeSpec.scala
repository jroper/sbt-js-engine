package com.typesafe.sbt.jse.engines

import org.specs2.mutable.Specification
import java.io.File
import scala.collection.immutable

class TriremeSpec extends Specification {

  "The Trireme engine" should {
    "execute some javascript by passing in a string arg and comparing its return value" in {
      val f = new File(classOf[TriremeSpec].getResource("test-node.js").toURI)
      val out = new StringBuilder
      val err = new StringBuilder
      Trireme().executeJs(f, immutable.Seq("999"), Map.empty, out.append(_), err.append(_))
      err.toString.trim must_== ""
      out.toString.trim must_== "999"
    }

    "execute some javascript by passing in a string arg and comparing its return value expecting an error" in {
      val f = new File(classOf[TriremeSpec].getResource("test-rhino.js").toURI)
      val out = new StringBuilder
      val err = new StringBuilder
      Trireme().executeJs(f, immutable.Seq("999"), Map.empty, out.append(_), err.append(_))
      out.toString.trim must_== ""
      err.toString.trim must startWith("""ReferenceError: "readFile" is not defined""")
    }
  }

  private def runSimpleTest() = {
    val f = new File(classOf[TriremeSpec].getResource("test-node.js").toURI)
    val out = new StringBuilder
    val err = new StringBuilder
    Trireme().executeJs(f, immutable.Seq("999"), Map.empty, out.append(_), err.append(_))
    err.toString.trim must_== ""
    out.toString.trim must_== "999"
  }

  "not leak threads" in {
    // this test assumes that there are no other trireme tests running concurrently, if there are, the trireme thread
    // count will be non 0
    runSimpleTest()

    Thread.sleep(1)

    import scala.collection.JavaConverters._
    val triremeThreads = Thread.getAllStackTraces.keySet.asScala
      .filter(_.getName.contains("Trireme"))

    ("trireme threads: " + triremeThreads) <==> (triremeThreads.size === 0)
    ok
  }


  "not leak file descriptors" in {
    import java.lang.management._
    val os = ManagementFactory.getOperatingSystemMXBean
    // To get the open file descriptor count, you need to use non portable APIs, so use reflection
    try {
      val method = os.getClass.getMethod("getOpenFileDescriptorCount")
      // method is public native, needs to be set accessible to be invoked using reflection
      method.setAccessible(true)

      def getCount = method.invoke(os).asInstanceOf[Long]

      // brew a little first
      runSimpleTest()
      runSimpleTest()
      runSimpleTest()
      runSimpleTest()

      val openFds = getCount
      runSimpleTest()

      getCount must_== openFds
    } catch {
      case _: NoSuchMethodException =>
        println("Skipping file descriptor leak test because OS mbean doesn't have getOpenFileDescriptorCount")
        ok
    }
  }
}
