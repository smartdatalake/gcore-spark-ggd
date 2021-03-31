/*
 * gcore-spark is the reference implementation of the G-CORE graph query
 * language by the Linked Data Benchmark Council (LDBC) - ldbcouncil.org
 *
 * The copyrights of the source code in this file belong to:
 * - CWI (www.cwi.nl), 2017-2018
 *
 * This software is released in open source under the Apache License, 
 * Version 2.0 (the "License"); you may not use this file except in 
 * compliance with the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spark.sql.operators

import java.sql.{Connection, ResultSet, Statement}

import algebra.expressions.Reference
import algebra.target_api.BindingTableMetadata
import ggd.utils.ProteusUtils
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.types.{StructField, StructType}
import spark.sql.SqlQuery

/**
  * Metadata of a binding table, where the schema is stored as a [[StructType]] and the operation
  * on the binding table is a [[SqlQuery]].
  */
case class SqlBindingTableMetadata(sparkSchemaMap: Map[Reference, StructType],
                                   sparkBtableSchema: StructType,
                                   btableOps: SqlQuery) extends BindingTableMetadata {

  override type SchemaType = StructType
  override type QueryOperand = SqlQuery

  override val schemaMap: Map[Reference, StructType] = sparkSchemaMap
  override val btableSchema: StructType = sparkBtableSchema
  override val btable: SqlQuery = btableOps

  /**
    * Runs the SQL queries in [[btable]]. First runs the queries in the [[SqlQuery.prologue]], then
    * the [[SqlQuery.resQuery]], then the queries in the [[SqlQuery.epilogue]]. The result is the
    * [[DataFrame]] obtained by running the [[SqlQuery.resQuery]].
    */
  def solveBtableOps(sparkSession: SparkSession): DataFrame = {
    btable.prologue.foreach(sparkSession.sql)
    val result: DataFrame = sparkSession.sql(btable.resQuery)
    btable.epilogue.foreach(sparkSession.sql)
    result
  }

  def solveBtableOps(sparkSession: SparkSession, con: Connection) : DataFrame = {
    btable.prologue.foreach(u => executeQuery(sparkSession, u, con))
    val result: DataFrame = executeQuery(sparkSession, btable.resQuery, con)
    btable.epilogue.foreach(u => executeQuery(sparkSession, u, con))
    result
  }

  def getbtableJDBCSchema() : StructType = {
    val x = btableSchema.map(field => {
      new StructField(field.name.replace("$", "0_0"), field.dataType)
    })
    StructType(x)
  }


  def executeQuery(sparkSession: SparkSession, query: String, con: Connection): DataFrame = {
    val stmt:Statement =con.createStatement();
    println("Connected!!!")
    println("Query string::::" + query)
    //val newQuery = query.replaceAll("global_temp.", "")//.replace('$', '0').replaceAll("`", "").replaceAll("0", "0_0")
    //println("newQuery:::::" + newQuery)
    //val newQuery = "SELECT id AS p0_0id, price AS p0_0price, " +
    //  "name AS p0_0name, description AS p0_0description, idGoogle AS p0_0idGoogle, label AS p0_0label  FROM ProductGoogle LIMIT 20"
    //val newQuery =  "SELECT * FROM (" +
    //  "SELECT id AS x0_0id, fromId AS x0_0fromId FROM producedbyGoogle JOIN (" +
    //  "SELECT id AS p0_0id FROM ProductGoogle) ON x0_0fromId = p0_0id) LIMIT 20"
    //val newQuery = "SELECT p.id AS p0_0id, p.price AS p0_0price, x.id AS x0_0id, x.fromId AS x0_0fromId FROM ProductGoogle p, producedbyGoogle x WHERE p.id = x.fromId LIMIT 20"
    //val rs:ResultSet =stmt.executeQuery(newQuery);
    val rs:ResultSet = stmt.executeQuery(query)
    val schema = ProteusUtils.extractSchema(rs, "0_0")
    ProteusUtils.parallelizeResultSet(rs, sparkSession, schema)
  }

  /**
    * Pretty prints the schemas of each variable in the [[schemaMap]], then that of the binding
    * table's.
    */
  def showSchemas(): Unit = {
    schemaMap.foreach(kv => {
      println(kv._1)
      kv._2.printTreeString()
    })

    println("Btable schema:")
    btableSchema.printTreeString()
  }
}
