package spark.sql.operatorsJDBC

import algebra.operators.VertexRelation
import algebra.target_api
import algebra.target_api.BindingTableMetadata
import algebra.types.Graph
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types.StructType
import schema.{Catalog, Table}
import spark.sql.SqlQuery
import spark.sql.SqlQuery.refactorScanSchema
import spark.sql.operators.SqlBindingTableMetadata
import spark.sql.SqlQuery.selectAllPrependRefJDBC

case class VertexScanJDBC(vertexRelation: VertexRelation, graph: Graph, catalog: Catalog)
  extends target_api.VertexScan(vertexRelation, graph, catalog) {
  private val physTable: Table[DataFrame] =
    physGraph.tableMap(tableName).asInstanceOf[Table[DataFrame]]


  private val sqlQuery: SqlQuery = {
    physTable.data.createOrReplaceGlobalTempView(tableName.value)
    val scanQuery: String =
      s"""
      SELECT
      ${selectAllPrependRefJDBC(physTable.data, binding)}
      FROM ${tableName.value} ${binding.refName} """
    /**Older version with SQL table label
     * s"""
     * SELECT
     * ${tableName.value} AS ${binding.refName}0_0${TABLE_LABEL_COL.columnName},
     * ${selectAllPrependRefJDBC(physTable.data, binding)}
     * FROM ${tableName.value} LIMIT 20"""
     */
    SqlQuery(resQuery = scanQuery)
  }

  private val schema: StructType = refactorScanSchema(physTable.data.schema, binding)

  override val bindingTable: BindingTableMetadata =
    SqlBindingTableMetadata(
      sparkSchemaMap = Map(binding -> schema),
      sparkBtableSchema = schema,
      btableOps = sqlQuery)
}
