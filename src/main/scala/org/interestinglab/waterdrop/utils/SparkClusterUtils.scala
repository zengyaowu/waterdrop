package org.interestinglab.waterdrop.utils

import java.nio.file.Paths

import org.apache.spark.sql.SparkSession
import org.interestinglab.waterdrop.config.Common

object SparkClusterUtils {

  def addFiles(sparkSession: SparkSession): Unit = {
    sparkSession.sparkContext.addFile(Paths.get(Common.appRootDir().toString, "config").toString, true)
    sparkSession.sparkContext.addFile(Paths.get(Common.appRootDir().toString, "lib").toString, true)
    sparkSession.sparkContext.addFile(Paths.get(Common.appRootDir().toString, "plugins").toString, true)
  }

  def addJarDependencies(sparkSession: SparkSession): Unit = {

    // TODO: spark-submit --jars 指定了jars,还有必要在这里addJar("")????
//    sparkSession.sparkContext.addJar("")
  }
}
