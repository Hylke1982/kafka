/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ly.stealth.mesos.kafka

import org.apache.log4j.Logger
import java.io.{FileInputStream, File}
import java.net.{URL, URLClassLoader}
import java.util.Properties
import java.util
import scala.collection.JavaConversions._
import ly.stealth.mesos.kafka.BrokerServer.Distro

abstract class BrokerServer {
  def isStarted: Boolean
  def start(broker: Broker, defaults: util.Map[String, String] = new util.HashMap()): Broker.Endpoint
  def stop(): Unit
  def waitFor(): Unit
  def getClassLoader: ClassLoader
}

class KafkaServer extends BrokerServer {
  val logger = Logger.getLogger(classOf[KafkaServer])
  @volatile var server: Object = null

  def isStarted: Boolean = server != null

  def start(broker: Broker, defaults: util.Map[String, String] = new util.HashMap()): Broker.Endpoint = {
    if (isStarted) throw new IllegalStateException("started")

    BrokerServer.Distro.configureLog4j(broker.log4jOptions)
    val options = broker.options(defaults)
    BrokerServer.Distro.startReporters(options)

    logger.info("Starting KafkaServer")
    server = BrokerServer.Distro.newServer(options)
    server.getClass.getMethod("startup").invoke(server)

    new Broker.Endpoint(options.get("host.name"), Integer.parseInt(options.get("port")))
  }

  def stop(): Unit = {
    if (!isStarted) throw new IllegalStateException("!started")

    logger.info("Stopping KafkaServer")
    server.getClass.getMethod("shutdown").invoke(server)

    waitFor()
    server = null
  }

  def waitFor(): Unit = {
    if (server != null)
      server.getClass.getMethod("awaitShutdown").invoke(server)
  }

  def getClassLoader: ClassLoader = Distro.loader
}

object BrokerServer {
  object Distro {
    var dir: File = null
    var loader: URLClassLoader = null
    init()

    def newServer(options: util.Map[String, String]): Object = {
      val serverClass = loader.loadClass("kafka.server.KafkaServerStartable")
      val configClass = loader.loadClass("kafka.server.KafkaConfig")

      val props: Properties = this.props(options, "server.properties")
      val config: Object = configClass.getConstructor(classOf[Properties]).newInstance(props).asInstanceOf[Object]
      val server: Object = serverClass.getConstructor(configClass).newInstance(config).asInstanceOf[Object]

      server
    }

    def startReporters(options: util.Map[String, String]): Object = {
      val configClass = loader.loadClass("kafka.server.KafkaConfig")

      val props: Properties = this.props(options, "server.properties")
      val config: Object = configClass.getConstructor(classOf[Properties]).newInstance(props).asInstanceOf[Object]

      val metricsReporter = loader.loadClass("kafka.metrics.KafkaMetricsReporter$").getField("MODULE$").get(null)
      val metricsReporterClass = metricsReporter.getClass
      val verifiableProps = config.getClass.getMethod("props").invoke(config)
      metricsReporterClass.getMethod("startReporters", verifiableProps.getClass).invoke(metricsReporter, verifiableProps)
    }
    
    def configureLog4j(options: util.Map[String, String]): Unit = {
      val props: Properties = this.props(options, "log4j.properties")
      val configurator: Class[_] = loader.loadClass("org.apache.log4j.PropertyConfigurator")
      configurator.getMethod("configure", classOf[Properties]).invoke(null, props)
    }
    
    private def props(options: util.Map[String, String], defaultsFile: String): Properties = {
      val p: Properties = new Properties()
      val stream: FileInputStream = new FileInputStream(dir + "/config/" + defaultsFile)
      
      try { p.load(stream) }
      finally { stream.close() }
      
      for ((k, v) <- options)
        if (v != null) p.setProperty(k, v)
        else p.remove(k)

      p
    }
    
    private def init(): Unit = {
      // find kafka dir
      for (file <- new File(".").listFiles())
        if (file.isDirectory && file.getName.startsWith("kafka"))
          dir = file

      if (dir == null) throw new IllegalStateException("Kafka distribution dir not found")

      // create kafka classLoader
      val classpath = new util.ArrayList[URL]()
      for (file <- new File(dir, "libs").listFiles())
        classpath.add(new URL("" + file.toURI))

      loader = new Loader(classpath.toArray(Array()))
    }
  }

  // Loader that loads classes in reverse order: 1. from self, 2. from parent.
  // This is required, because current jar have classes incompatible with classes from kafka distro.
  class Loader(urls: Array[URL]) extends URLClassLoader(urls) {
    val snappyHackedClasses = Array[String]("org.xerial.snappy.SnappyNativeAPI", "org.xerial.snappy.SnappyNative", "org.xerial.snappy.SnappyErrorCode")

    override protected def loadClass(name: String, resolve: Boolean): Class[_] = {
      getClassLoadingLock(name) synchronized {
        // Handle Snappy class loading hack:
        // Snappy injects 3 classes and native lib to root ClassLoader
        // See - org.xerial.snappy.SnappyLoader.injectSnappyNativeLoader
        if (snappyHackedClasses.contains(name))
          return super.loadClass(name, true)

        // Check class is loaded
        var c: Class[_] = findLoadedClass(name)

        // Load from self
        try { if (c == null) c = findClass(name) }
        catch { case e: ClassNotFoundException => }

        // Load from parent
        if (c == null) c = super.loadClass(name, true)

        if (resolve) resolveClass(c)
        c
      }
    }
  }
}
