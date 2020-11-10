package io.github.interestinglab.waterdrop.apis

import org.apache.spark.sql.{Dataset, Row}

abstract class BaseOutput extends Plugin {

  def preProcess(df: Dataset[Row]): Unit = {}

  def process(df: Dataset[Row])

  def afterProcess(df: Dataset[Row]): Unit = {}

  def dataSetCount(df: Dataset[Row]) :Unit = {
    val totalCount = df.sparkSession.sparkContext.longAccumulator("totalCount")

    df.foreachPartition(par =>{
      var count: Int = 0;
      while(par.hasNext){
        par.next();
        count +=1;
      }
      totalCount.add(count)
    })
    println("[info] dataframe 总分区数为: " + totalCount.count)
    println("[info] dataframe 总共的记录数为： " + totalCount.sum)
  }




}
