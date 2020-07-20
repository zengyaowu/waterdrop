package io.github.interestinglab.waterdrop.output.batch

import java.text.SimpleDateFormat
import java.util
import java.util.Properties
import java.math.BigDecimal
import java.sql.ResultSet

import io.github.interestinglab.waterdrop.config.{Config, ConfigFactory}
import io.github.interestinglab.waterdrop.apis.BaseOutput
import io.github.interestinglab.waterdrop.config.ConfigRuntimeException
import io.github.interestinglab.waterdrop.config.TypesafeConfigUtils
import org.apache.spark.sql.{Dataset, Row, SparkSession}
import ru.yandex.clickhouse.except.{ClickHouseException, ClickHouseUnknownException}
import ru.yandex.clickhouse.settings.ClickHouseProperties
import ru.yandex.clickhouse.{
  BalancedClickhouseDataSource,
  ClickHouseConnectionImpl,
  ClickHousePreparedStatement,
  ClickhouseJdbcUrlParser
}

import scala.collection.JavaConversions._
import scala.collection.immutable.HashMap
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.WrappedArray
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

class Clickhouse extends BaseOutput {

  var tableSchema: Map[String, String] = new HashMap[String, String]()
  var jdbcLink: String = _
  var initSQL: String = _
  var table: String = _
  var fields: java.util.List[String] = _

  var cluster: String = _

  //contians cluster basic info
  var clusterInfo: ArrayBuffer[(String, Int, Int, String)] = _

  var retryCodes: java.util.List[Integer] = _
  var config: Config = ConfigFactory.empty()
  val clickhousePrefix = "clickhouse."
  val properties: Properties = new Properties()

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

    val requiredOptions = List("host", "table", "database")

    val nonExistsOptions = requiredOptions.map(optionName => (optionName, config.hasPath(optionName))).filter { p =>
      val (optionName, exists) = p
      !exists
    }

    if (TypesafeConfigUtils.hasSubConfig(config, clickhousePrefix)) {
      val clickhouseConfig = TypesafeConfigUtils.extractSubConfig(config, clickhousePrefix, false)
      clickhouseConfig
        .entrySet()
        .foreach(entry => {
          val key = entry.getKey
          val value = String.valueOf(entry.getValue.unwrapped())
          properties.put(key, value)
        })
    }

    if (nonExistsOptions.nonEmpty) {
      (
        false,
        "please specify " + nonExistsOptions
          .map { option =>
            val (name, exists) = option
            "[" + name + "]"
          }
          .mkString(", ") + " as non-empty string")
    }

    val hasUserName = config.hasPath("username")
    val hasPassword = config.hasPath("password")

    if (hasUserName && !hasPassword || !hasUserName && hasPassword) {
      (false, "please specify username and password at the same time")
    }
    if (hasPassword) {
      properties.put("user", config.getString("username"))
      properties.put("password", config.getString("password"))
    }

    (true, "")
  }

  override def prepare(spark: SparkSession): Unit = {
    this.jdbcLink = String.format("jdbc:clickhouse://%s/%s", config.getString("host"), config.getString("database"))

    val balanced: BalancedClickhouseDataSource = new BalancedClickhouseDataSource(this.jdbcLink, properties)
    val conn = balanced.getConnection.asInstanceOf[ClickHouseConnectionImpl]

    this.table = config.getString("table")
    this.tableSchema = getClickHouseSchema(conn, table)

    if (this.config.hasPath("fields")) {
      this.fields = config.getStringList("fields")
      val (flag, msg) = acceptedClickHouseSchema()
      if (!flag) {
        throw new ConfigRuntimeException(msg)
      }
    }

    val defaultConfig = ConfigFactory.parseMap(
      Map(
        "bulk_size" -> 20000,
        // "retry_codes" -> util.Arrays.asList(ClickHouseErrorCode.NETWORK_ERROR.code),
        "retry_codes" -> util.Arrays.asList(),
        "retry" -> 1
      )
    )

    if (config.hasPath("cluster")) {
      this.cluster = config.getString("cluster")

      this.clusterInfo = getClickHouseClusterInfo(conn, cluster)
      if (this.clusterInfo.size == 0) {
        val errorInfo = s"cloud not find cluster config in system.clusters, config cluster = $cluster"
        logError(errorInfo)
        throw new RuntimeException(errorInfo)
      }
      logInfo(s"get [$cluster] config from system.clusters, the replica info is [$clusterInfo].")
    }

    config = config.withFallback(defaultConfig)
    retryCodes = config.getIntList("retry_codes")
    super.prepare(spark)
  }

  override def process(df: Dataset[Row]): Unit = {
    val dfFields = df.schema.fieldNames
    val bulkSize = config.getInt("bulk_size")
    val retry = config.getInt("retry")

    if (!config.hasPath("fields")) {
      fields = dfFields.toList
    }

    this.initSQL = initPrepareSQL()
    logInfo(this.initSQL)

    df.foreachPartition { iter =>
      var jdbcUrl = this.jdbcLink
      if (this.clusterInfo != null && this.clusterInfo.size > 0) {
        //using random policy to select shard when insert data
        val randomShard = (Math.random() * this.clusterInfo.size).asInstanceOf[Int]
        val shardInfo = this.clusterInfo.get(randomShard)

        val host = shardInfo._4
        val port = getJDBCPort(this.jdbcLink)
        val database = config.getString("database")

        jdbcUrl = s"jdbc:clickhouse://$host:$port/$database"
        logInfo(s"cluster mode, select shard index [$randomShard] to insert data, the jdbc url is [$jdbcUrl].")
      } else {
        logInfo(s"single mode, the jdbc url is [$jdbcUrl].")
      }

      val executorBalanced = new BalancedClickhouseDataSource(jdbcUrl, this.properties)
      val executorConn = executorBalanced.getConnection.asInstanceOf[ClickHouseConnectionImpl]
      val statement = executorConn.createClickHousePreparedStatement(this.initSQL, ResultSet.TYPE_FORWARD_ONLY)
      var length = 0
      while (iter.hasNext) {
        val row = iter.next()
        length += 1

        renderStatement(fields, row, dfFields, statement)
        statement.addBatch()

        if (length >= bulkSize) {
          execute(statement, retry)
          length = 0
        }
      }

      execute(statement, retry)
    }
  }

  private def getJDBCPort(jdbcUrl: String): Int = {
    val clickHouseProperties: ClickHouseProperties = ClickhouseJdbcUrlParser.parse(jdbcUrl, properties)
    clickHouseProperties.getPort
  }

  private def execute(statement: ClickHousePreparedStatement, retry: Int): Unit = {
    val res = Try(statement.executeBatch())
    res match {
      case Success(_) => {
        logInfo("Insert into ClickHouse succeed")
        statement.close()
      }
      case Failure(e: ClickHouseException) => {
        val errorCode = e.getErrorCode
        if (retryCodes.contains(errorCode)) {
          logError("Insert into ClickHouse failed. Reason: ", e)
          if (retry > 0) {
            execute(statement, retry - 1)
          } else {
            logError("Insert into ClickHouse failed and retry failed, drop this bulk.")
            statement.close()
          }
        } else {
          throw e
        }
      }
      case Failure(e: ClickHouseUnknownException) => {
        statement.close()
        throw e
      }
      case Failure(e: Exception) => {
        throw e
      }
    }
  }

  private def getClickHouseSchema(conn: ClickHouseConnectionImpl, table: String): Map[String, String] = {
    val sql = s"desc $table"
    val resultSet = conn.createStatement.executeQuery(sql)
    var schema = new HashMap[String, String]()
    while (resultSet.next()) {
      schema += (resultSet.getString(1) -> resultSet.getString(2))
    }
    schema
  }

  private def getClickHouseClusterInfo(
    conn: ClickHouseConnectionImpl,
    cluster: String): ArrayBuffer[(String, Int, Int, String)] = {
    val sql =
      s"SELECT cluster, shard_num, shard_weight, host_address FROM system.clusters WHERE cluster = '$cluster' AND replica_num = 1"
    val resultSet = conn.createStatement.executeQuery(sql)

    val clusterInfo = ArrayBuffer[(String, Int, Int, String)]()
    while (resultSet.next()) {
      val shardWeight = resultSet.getInt("shard_weight")
      for (_ <- 1 to shardWeight) {

        val custerName = resultSet.getString("cluster")
        val shardNum = resultSet.getInt("shard_num")
        val hostAddress = resultSet.getString("host_address")

        val shardInfo = Tuple4(custerName, shardNum, shardWeight, hostAddress)
        clusterInfo += shardInfo
      }
    }
    clusterInfo
  }

  private def initPrepareSQL(): String = {
    val prepare = List.fill(fields.size)("?")
    val sql = String.format(
      "insert into %s (%s) values (%s)",
      this.table,
      this.fields.map(a => s"`$a`").mkString(","),
      prepare.mkString(","))

    sql
  }

  private def acceptedClickHouseSchema(): (Boolean, String) = {

    val nonExistsFields = fields
      .map(field => (field, tableSchema.contains(field)))
      .filter { case (_, exist) => !exist }

    if (nonExistsFields.nonEmpty) {
      (
        false,
        "field " + nonExistsFields
          .map { case (option) => "[" + option + "]" }
          .mkString(", ") + " not exist in table " + this.table)
    } else {
      val nonSupportedType = fields
        .map(field => (tableSchema(field), Clickhouse.supportOrNot(tableSchema(field))))
        .filter { case (_, exist) => !exist }
      if (nonSupportedType.nonEmpty) {
        (
          false,
          "clickHouse data type " + nonSupportedType
            .map { case (option) => "[" + option + "]" }
            .mkString(", ") + " not support in current version.")
      } else {
        (true, "")
      }
    }
  }

  private def renderDefaultStatement(index: Int, fieldType: String, statement: ClickHousePreparedStatement): Unit = {
    fieldType match {
      case "DateTime" | "Date" | "String" =>
        statement.setString(index + 1, Clickhouse.renderStringDefault(fieldType))
      case "Int8" | "UInt8" | "Int16" | "Int32" | "UInt32" | "UInt16" =>
        statement.setInt(index + 1, 0)
      case "UInt64" | "Int64" =>
        statement.setLong(index + 1, 0)
      case "Float32" => statement.setFloat(index + 1, 0)
      case "Float64" => statement.setDouble(index + 1, 0)
      case Clickhouse.lowCardinalityPattern(lowCardinalityType) =>
        renderDefaultStatement(index, lowCardinalityType, statement)
      case Clickhouse.arrayPattern(_) => statement.setArray(index + 1, List())
      case Clickhouse.nullablePattern(nullFieldType) => renderNullStatement(index, nullFieldType, statement)
      case _ => statement.setString(index + 1, "")
    }
  }

  private def renderNullStatement(index: Int, fieldType: String, statement: ClickHousePreparedStatement): Unit = {
    fieldType match {
      case "String" =>
        statement.setNull(index + 1, java.sql.Types.VARCHAR)
      case "DateTime" => statement.setNull(index + 1, java.sql.Types.DATE)
      case "Date" => statement.setNull(index + 1, java.sql.Types.TIME)
      case "Int8" | "UInt8" | "Int16" | "Int32" | "UInt32" | "UInt16" =>
        statement.setNull(index + 1, java.sql.Types.INTEGER)
      case "UInt64" | "Int64" =>
        statement.setNull(index + 1, java.sql.Types.BIGINT)
      case "Float32" => statement.setNull(index + 1, java.sql.Types.FLOAT)
      case "Float64" => statement.setNull(index + 1, java.sql.Types.DOUBLE)
      case "Array" => statement.setNull(index + 1, java.sql.Types.ARRAY)
      case Clickhouse.decimalPattern(_) => statement.setNull(index + 1, java.sql.Types.DECIMAL)
    }
  }

  private def renderBaseTypeStatement(
    index: Int,
    fieldIndex: Int,
    fieldType: String,
    item: Row,
    statement: ClickHousePreparedStatement): Unit = {
    fieldType match {
      case "DateTime" | "Date" | "String" =>
        statement.setString(index + 1, item.getAs[String](fieldIndex))
      case "Int8" | "UInt8" | "Int16" | "UInt16" | "Int32" =>
        statement.setInt(index + 1, item.getAs[Int](fieldIndex))
      case "UInt32" | "UInt64" | "Int64" =>
        statement.setLong(index + 1, item.getAs[Long](fieldIndex))
      case "Float32" => statement.setFloat(index + 1, item.getAs[Float](fieldIndex))
      case "Float64" => statement.setDouble(index + 1, item.getAs[Double](fieldIndex))
      case Clickhouse.arrayPattern(_) =>
        statement.setArray(index + 1, item.getAs[WrappedArray[AnyRef]](fieldIndex))
      case "Decimal" => statement.setBigDecimal(index + 1, item.getAs[BigDecimal](fieldIndex))
      case _ => statement.setString(index + 1, item.getAs[String](fieldIndex))
    }
  }

  private def renderStatementEntry(
    index: Int,
    fieldIndex: Int,
    fieldType: String,
    item: Row,
    statement: ClickHousePreparedStatement): Unit = {
    fieldType match {
      case "String" | "DateTime" | "Date" | Clickhouse.arrayPattern(_) =>
        renderBaseTypeStatement(index, fieldIndex, fieldType, item, statement)
      case Clickhouse.floatPattern(_) | Clickhouse.intPattern(_) | Clickhouse.uintPattern(_) =>
        renderBaseTypeStatement(index, fieldIndex, fieldType, item, statement)
      case Clickhouse.nullablePattern(dataType) =>
        renderStatementEntry(index, fieldIndex, dataType, item, statement)
      case Clickhouse.lowCardinalityPattern(dataType) =>
        renderBaseTypeStatement(index, fieldIndex, dataType, item, statement)
      case Clickhouse.decimalPattern(_) =>
        renderBaseTypeStatement(index, fieldIndex, "Decimal", item, statement)
      case _ => statement.setString(index + 1, item.getAs[String](fieldIndex))
    }
  }

  private def renderStatement(
    fields: util.List[String],
    item: Row,
    dsFields: Array[String],
    statement: ClickHousePreparedStatement): Unit = {
    for (i <- 0 until fields.size()) {
      val field = fields.get(i)
      val fieldType = tableSchema(field)
      if (dsFields.indexOf(field) == -1) {
        // specified field does not existed in row.
        renderDefaultStatement(i, fieldType, statement)
      } else {
        val fieldIndex = item.fieldIndex(field)
        if (item.isNullAt(fieldIndex)) {
          // specified field is Null in Row.
          renderDefaultStatement(i, fieldType, statement)
        } else {
          renderStatementEntry(i, fieldIndex, fieldType, item, statement)
        }
      }
    }
  }
}

object Clickhouse {

  val arrayPattern: Regex = "(Array.*)".r
  val nullablePattern: Regex = "Nullable\\((.*)\\)".r
  val lowCardinalityPattern: Regex = "LowCardinality\\((.*)\\)".r
  val intPattern: Regex = "(Int.*)".r
  val uintPattern: Regex = "(UInt.*)".r
  val floatPattern: Regex = "(Float.*)".r
  val decimalPattern: Regex = "(Decimal.*)".r

  /**
   * Waterdrop support this clickhouse data type or not.
   *
   * @param dataType ClickHouse Data Type
   * @return Boolean
   * */
  private[waterdrop] def supportOrNot(dataType: String): Boolean = {
    dataType match {
      case "Date" | "DateTime" | "String" =>
        true
      case arrayPattern(_) | nullablePattern(_) | floatPattern(_) | intPattern(_) | uintPattern(_) =>
        true
      case lowCardinalityPattern(_) =>
        true
      case decimalPattern(_) =>
        true
      case _ =>
        false
    }
  }

  private[waterdrop] def renderStringDefault(fieldType: String): String = {
    fieldType match {
      case "DateTime" =>
        val dateFormat: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        dateFormat.format(System.currentTimeMillis())
      case "Date" =>
        val dateFormat: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd")
        dateFormat.format(System.currentTimeMillis())
      case "String" =>
        ""
    }
  }
}
