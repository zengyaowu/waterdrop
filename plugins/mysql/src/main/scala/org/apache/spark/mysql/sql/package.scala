package org.apache.spark.mysql

import io.github.interestinglab.waterdrop.config.Config
import io.github.interestinglab.waterdrop.output.batch.jdbc.MysqlSparkSql
import org.apache.spark.sql.DataFrame

package object sql {
  implicit def sparkDataFrameFunctions(df: DataFrame) = new SparkDataFrameFunctions(df)

  class SparkDataFrameFunctions(df: DataFrame) extends Serializable {
    def saveOrUpdateToMysql(cfg: Config): Unit = { MysqlSparkSql.saveOrUpadteToMysql(df, cfg) }
  }

}
