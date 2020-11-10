package io.github.interestinglab.waterdrop.output.batch

import io.github.interestinglab.waterdrop.apis.{BaseOutput}
import io.github.interestinglab.waterdrop.config.{Config, ConfigFactory}
import org.apache.commons.lang3.StringUtils
import org.apache.spark.sql.execution.datasources.jdbc.JdbcUtilsV2
import org.apache.spark.sql.{Dataset, Row, SparkSession}

import scala.collection.JavaConversions._

class MysqlV2 extends BaseOutput  {

  var firstProcess = true

  var config: Config = ConfigFactory.empty()

  val prop = new java.util.Properties

  override def process(df: Dataset[Row]): Unit = {

    dataSetCount(df);

    import org.apache.spark.mysql.sql._
    df.saveOrUpdateToMysql(config)

  }


  /**
    * set config
    * @param config
    */
  override def setConfig(config: Config): Unit = {
    this.config = config
  }


  /**
    * get config
    * @return this.config
    */
  override def getConfig(): Config = {
    this.config
  }

  /**
    * checkconfig
    * @return
    */
  override def checkConfig(): (Boolean, String) = {

    val requiredOptions = List("url", "table", "user", "password");

    val nonExistsOptions = requiredOptions.map(optionName => (optionName, config.hasPath(optionName))).filter { p =>
      val (_, exists) = p
      !exists
    }

    if (nonExistsOptions.length > 0) {
      return (false, "please specify " + nonExistsOptions.map("[" + _._1 + "]").mkString(", ") + " as non-empty string")
    }

    if ("overwrite".equals(config.getString("save_mode")) && !config.hasPath("update_fields")) {
      return (false, "please specify value of [update_fields]")
    }
    (true, "")

  }


  /**
    * prepare config and execute the presql
    * @param spark
    */
  override def prepare(spark: SparkSession): Unit = {
    super.prepare(spark)

    val defaultConfig = ConfigFactory.parseMap(Map("save_mode" -> "append"))
    config = config.withFallback(defaultConfig)
    if(!config.hasPath("presql") || StringUtils.isEmpty(config.getString("presql"))){
      return
    }

    val presql = config.getString("presql")
    if (StringUtils.isNotEmpty(presql)) {
      val options = JdbcUtilsV2.createJdbcOptionsInWrite(config)
      val conn = JdbcUtilsV2.createConnectionFactory(options)()
      val statement =conn.createStatement()
      statement.execute(presql)
      statement.close()
      conn.close()
    }

  }
}
