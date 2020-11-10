package io.github.interestinglab.waterdrop.output.batch

import HBaseConstants._
import io.github.interestinglab.waterdrop.apis.BaseOutput
import io.github.interestinglab.waterdrop.config.{Config, ConfigFactory}
import org.apache.commons.lang3.StringUtils
import org.apache.hadoop.hbase.HConstants
import org.apache.hadoop.hbase.client.Put
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.TableOutputFormat
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.mapreduce.Job
import org.apache.spark.sql.{Dataset, Row, SparkSession}

class HBase extends BaseOutput{

  var config: Config = ConfigFactory.empty()

  override def getConfig(): Config = {
    return this.config
  }

  override def setConfig(config: Config): Unit = {
    this.config = config
  }

  override def checkConfig(): (Boolean, String) = {
    val requiredOptions = List(ZKHosts, Table, RowKey, Columns);

    val nonExistsOptions = requiredOptions.map(optionName => (optionName, config.hasPath(optionName))).filter { p =>
      val (_, exists) = p
      !exists
    }
    if (nonExistsOptions.nonEmpty) {
      (
        false,
        "please specify " + nonExistsOptions
          .map { case (option) => "[" + option + "]" }
          .mkString(", ") + " as non-empty string")
    } else {
      (true, "")
    }
  }

  private var columns: Array[Tuple2[String,String]] = _
  private var rowKey: String = _

  override def prepare(spark: SparkSession): Unit = {
    columns = config.getString(Columns).split(",").map(tp=>{
      val tparr = tp.split(":")
      Tuple2(tparr(0),tparr(1))
    })
    rowKey = config.getString(RowKey)
  }

  override def process(df: Dataset[Row]): Unit = {
    val hadoopConf = df.sparkSession.sparkContext.hadoopConfiguration

    hadoopConf.set(HConstants.CLIENT_ZOOKEEPER_QUORUM, config.getString(ZKHosts))
    if(config.hasPath(ZKPath) && !StringUtils.isEmpty(config.getString(ZKPath) )){
      hadoopConf.set(HConstants.ZOOKEEPER_ZNODE_PARENT, config.getString(ZKPath))
    }
    hadoopConf.set(TableOutputFormat.OUTPUT_TABLE, config.getString(Table))
    val job = Job.getInstance(hadoopConf)
    job.setOutputKeyClass(classOf[ImmutableBytesWritable])
    job.setOutputValueClass(classOf[Put])

    job.setOutputFormatClass(classOf[TableOutputFormat[ImmutableBytesWritable]])
    df.rdd.map(row => {

        val rowKeyColumnIndex = row.schema.fieldIndex(rowKey)
        val key:String  = String.valueOf(row.get(rowKeyColumnIndex))
        val rowKeyByteVal = Bytes.toBytes(key)
        val put = new Put(rowKeyByteVal)
        columns.foreach(coltuple =>{
          val colIndex = row.schema.fieldIndex(coltuple._2)
          val colValue = String.valueOf(row.get(colIndex))
          if(!StringUtils.isEmpty(colValue)) {
            put.addColumn(Bytes.toBytes(coltuple._1),Bytes.toBytes(coltuple._2),Bytes.toBytes(colValue))
          }
        })
          new ImmutableBytesWritable(rowKeyByteVal) -> put
      }).saveAsNewAPIHadoopDataset(job.getConfiguration)

  }
}
