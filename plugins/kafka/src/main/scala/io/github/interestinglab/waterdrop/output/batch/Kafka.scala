package io.github.interestinglab.waterdrop.output.batch

import java.util.Properties

import io.github.interestinglab.waterdrop.config.{Config, ConfigFactory, TypesafeConfigUtils}

import scala.collection.JavaConversions._
import java.util.{Properties, Random}

import io.github.interestinglab.waterdrop.UserRuntimeException
import io.github.interestinglab.waterdrop.apis.BaseOutput
import io.github.interestinglab.waterdrop.config.TypesafeConfigUtils
import io.github.interestinglab.waterdrop.output.utils.KafkaProducerUtil
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql.{Dataset, Row, SparkSession}
import org.codehaus.jackson.map.ObjectMapper

import scala.collection.JavaConversions._

class Kafka extends BaseOutput {

  val producerPrefix = "producer."

  val kafkaTableNameKey = "tableName"

  var tableName=""

  val randomBound = 1024*1024*100

  val keySeprator = "|"


  var hasRandomColum=false

  val random = new Random()

  var kafkaSink: Option[Broadcast[KafkaProducerUtil]] = None

  var config: Config = ConfigFactory.empty()

  lazy val objectMapper = new ObjectMapper()

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

  override def checkConfig(): (Boolean, String) ={

    val producerConfig = TypesafeConfigUtils.extractSubConfig(config, producerPrefix, false)

    config.hasPath("topic") && producerConfig.hasPath("bootstrap.servers") match {
      case true => (true, "")
      case false => (false, "please specify [topic] and [producer.bootstrap.servers]")
    }
  }

  override def prepare(spark: SparkSession): Unit = {
    super.prepare(spark)

    val defaultConfig = ConfigFactory.parseMap(
      Map(
        "serializer" -> "json",
        producerPrefix + "key.serializer" -> "org.apache.kafka.common.serialization.StringSerializer",
        producerPrefix + "value.serializer" -> "org.apache.kafka.common.serialization.StringSerializer"
      )
    )

    config = config.withFallback(defaultConfig)

    if(config.hasPath(kafkaTableNameKey)){
      hasRandomColum = true
    }

    if(config.hasPath(kafkaTableNameKey)){
      tableName = config.getString(kafkaTableNameKey)
    }

    val props = new Properties()
    TypesafeConfigUtils
      .extractSubConfig(config, producerPrefix, false)
      .entrySet()
      .foreach(entry => {
        val key = entry.getKey
        val value = String.valueOf(entry.getValue.unwrapped())
        props.put(key, value)
      })

    println("[INFO] Kafka Output properties: ")
    props.foreach(entry => {
      val (key, value) = entry
      println("[INFO] \t" + key + " = " + value)
    })

    kafkaSink = Some(spark.sparkContext.broadcast(KafkaProducerUtil(props)))
  }

  override def process(df: Dataset[Row]) {

    val totalCount = df.sparkSession.sparkContext.longAccumulator("totalCount")

    df.foreachPartition(par =>{
      var count: Int = 0;
      while(par.hasNext()){
        val item = par.next();
        count +=1;
      }
      totalCount.add(count)
    })
    println("[info] dataframe 总分区数为: " + totalCount.count)
    println("[info] dataframe 总共的记录数为： " + totalCount.sum)


    val topic = config.getString("topic")
    config.getString("serializer") match {
      case "text" => {
        if (df.schema.size != 1) {
          throw new UserRuntimeException(
            s"Text data source supports only a single column," +
              s" and you have ${df.schema.size} columns.")
        } else {

          df.foreach { row =>
            kafkaSink.foreach { ks =>
              ks.value.send(topic, String.valueOf(row.get(0)))
            }
          }
        }
      }
      case _ => {
        df.toJSON.foreach { row =>
          var key :String = ""
          var record: ProducerRecord[String,String] = null
          if(hasRandomColum){
            key = tableName + keySeprator + String.valueOf(random.nextInt(randomBound))
            record = new ProducerRecord(topic,key ,row)
          }else{
            record = new ProducerRecord(topic,row)
          }
          kafkaSink.foreach { ks =>
            ks.value.send(record)
          }
        }
      }
    }
  }
}

