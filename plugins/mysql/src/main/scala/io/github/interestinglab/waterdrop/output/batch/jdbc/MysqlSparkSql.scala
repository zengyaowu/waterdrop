package io.github.interestinglab.waterdrop.output.batch.jdbc

import io.github.interestinglab.waterdrop.config.Config
import org.apache.spark.sql.execution.datasources.jdbc.{JdbcUtils, JdbcUtilsV2}
import org.apache.spark.sql.{DataFrame, Row}


object MysqlSparkSql {

  def saveOrUpadteToMysql(df: DataFrame, cfg: Config): Unit = {
    if (df != null) {
      if (df.isStreaming) {
        throw new UnsupportedOperationException("Streaming Datasets should not be saved with 'saveOrUpadteToMysql()'. ")
      }

      val options = JdbcUtilsV2.createJdbcOptionsInWrite(cfg)
      val isCaseSensitive = df.sqlContext.sparkSession.sessionState.conf.caseSensitiveAnalysis
      val conn = JdbcUtilsV2.createConnectionFactory(options).apply()
      try {
        val tableSchema = JdbcUtils.getSchemaOption(conn, options)
        JdbcUtilsV2.saveTable(df, tableSchema, isCaseSensitive, options)
      } finally {
        conn.close()
      }
    }
  }
}
