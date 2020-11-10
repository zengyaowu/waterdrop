package io.github.interestinglab.waterdrop.output.batch

import io.github.interestinglab.waterdrop.apis.BaseOutput
import io.github.interestinglab.waterdrop.config.{Config, ConfigFactory}
import org.apache.spark.sql.{Dataset, Row, SaveMode, SparkSession}

import scala.collection.JavaConversions._

/**
  * Redis batch output
  */
class Redis extends BaseOutput {

  var config: Config = ConfigFactory.empty()

  var redisCfg: Map[String, String] = Map()


  /**
    * Set Config.
    * */
  override def setConfig(config: Config): Unit = {
    this.config = config
  }

  /**
    * Get Config.
    * */
  override def getConfig(): Config = {
    this.config
  }

  override def checkConfig(): (Boolean, String) = {
    config.hasPath("host")  match {
      case true => {
        val host = config.getString("host")
        // TODO CHECK hosts
        (true, "")
      }
      case false => (false, "please specify [host] as a non-empty string list")
    }
  }


  override def prepare(spark: SparkSession): Unit = {
    super.prepare(spark)
    config.entrySet().foreach(e => {
      val key = e.getKey
      val value = String.valueOf(e.getValue.unwrapped())
      redisCfg += (key -> value)
    })
    println("[INFO] Output Redis Params:")
    for (entry <- redisCfg) {
      val (key, value) = entry
      if ("auth".equals(key)) {
        println("[INFO] \t" + key + " = ********" )
      } else {
        println("[INFO] \t" + key + " = " + value)
      }
    }
  }

  override def process(df: Dataset[Row]): Unit = {
    df.write.format("org.apache.spark.sql.redis")
      .options(redisCfg)
      .mode(SaveMode.Overwrite)
      .save()
  }
}
