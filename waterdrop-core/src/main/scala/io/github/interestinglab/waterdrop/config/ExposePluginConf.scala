package io.github.interestinglab.waterdrop.config

import java.io.File
import java.nio.file.{Files, Paths}
import java.util

import collection.JavaConverters._


object ExposePluginConf {

  //把一些特殊的比如Stream的包所在的plugin注册到这里，方便下面加载到正确的plugin包
  val pluginRegister = Map(
    "mysqlv2" -> "mysql",
    "hdfs" -> "file",
    "s3" -> "file",
    "Kafkastream" -> "kafka",
    "jdbc" -> "mysql"
  )

  def main(args: Array[String]): Unit = {
    assert(args.length ==2)
    val appdir = args(1)

    val config: Config = ConfigFactory.parseFile(new File(args(0)))
      .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
      .resolveWith(ConfigFactory.systemProperties(),ConfigResolveOptions.defaults().setAllowUnresolved(true))

    val jarSet = new util.HashSet[String]()

    val pluginSet = new util.HashSet[String]()

    config.getConfigList("input").asScala.foreach(plugin =>{
      val pluginName = plugin.getString(ConfigBuilder.PluginNameKey)
      pluginSet.add(pluginRegister.getOrElse(pluginName,pluginName))
    })

    config.getConfigList("output").asScala.foreach(plugin =>{

      val pluginName = plugin.getString(ConfigBuilder.PluginNameKey)
      pluginSet.add(pluginRegister.getOrElse(pluginName,pluginName))
    })


    pluginSet.asScala.foreach(plugin =>{
      val pluginPath = Paths.get(appdir,"plugins",plugin)
      Files.list(pluginPath).iterator().asScala.foreach(path =>{
        if(path.toString.endsWith(".jar")){
          jarSet.add(path.toAbsolutePath.toString)
        }
      })
    })

    println(String.join(",",jarSet))

  }
}
