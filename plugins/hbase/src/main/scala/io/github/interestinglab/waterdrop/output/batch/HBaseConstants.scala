package io.github.interestinglab.waterdrop.output.batch

object HBaseConstants {

  // format: "ip:port,ip:port"
  val ZKHosts = "zkHosts"

  val ZKPath = "zkPath"

  // format: "namespace.table"
  val Table = "table"

  val RowKey = "rowKey"

  // format: "columnFamily:columnName,columnFamily:columnName"
  val Columns = "columns"

}
