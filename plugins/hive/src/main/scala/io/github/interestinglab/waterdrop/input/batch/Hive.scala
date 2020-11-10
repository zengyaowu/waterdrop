package io.github.interestinglab.waterdrop.input.batch

import io.github.interestinglab.waterdrop.apis.BaseStaticInput
import io.github.interestinglab.waterdrop.config.{Config, ConfigFactory}
import org.apache.spark.sql.{Dataset, Row, SparkSession}

class Hive extends BaseStaticInput {
  var config: Config = ConfigFactory.empty()

  override def setConfig(config: Config): Unit = {
    this.config = config
  }

  override def getConfig(): Config = {
    this.config
  }

  override def checkConfig(): (Boolean, String) = {
    config.hasPath("pre_sql") match {
      case true => (true, "")
      case false => (false, "please specify [pre_sql]")
    }
  }

  override def getDataset(spark: SparkSession): Dataset[Row] = {

    val regTable = config.getString("table_name")
    val sqls = config.getString("pre_sql").trim.split(";").toList
    val ds = spark.sql(sqls(sqls.length-1))
    ds.createOrReplaceTempView(s"$regTable")
    ds
  }

}
