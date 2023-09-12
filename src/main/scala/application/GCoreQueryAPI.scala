package application

import algebra.expressions.Label
import ggd.GcoreRunner
import ggd.utils.{DataFrameUtils, GGDtoGCoreParser}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.catalog.Table
import org.apache.spark.sql.functions.col
import schema.EntitySchema.LabelRestrictionMap
import schema.{PathPropertyGraph, SchemaMap, Table}
import spark.SparkGraph

//class only for querying G-Core
class GCoreQueryAPI(gcoreRunner: GcoreRunner) {

  /**
   * Runs a gcore construct query and returns a Property Graph
   * @param query in G-Core syntax
   * @return PathPropertyGraph
   */
  def runConstructQuery(query: String, limit: Int = -1): PathPropertyGraph = {
    if(limit != -1){
      gcoreRunner.compiler.compilePropertyGraphWithLimit(query, limit).asInstanceOf[PathPropertyGraph]
    } else{
      gcoreRunner.compiler.compilePropertyGraph(query).asInstanceOf[PathPropertyGraph]
    }
  }

  /**
   * Runs a select query, returns a tabular view of all the results
   * @param query in G-Core syntax
   * @return Dataframe
   */
  def runSelectQuery(query: String, limit: Int = -1) : DataFrame = {
    if(limit != -1){
      gcoreRunner.compiler.compilePropertyGraph(query).asInstanceOf[DataFrame].limit(limit)
    } else{
      gcoreRunner.compiler.compilePropertyGraph(query).asInstanceOf[DataFrame]
    }

  }

  /**
   * Runs a query in G-Core and shows in the console a print of the results
   * @param query in G-Core syntax
   */
  def showResults(query: String) : Unit = {
    gcoreRunner.compiler.compile(query)
  }

  /**
   * Runs a select query to return a table format of all neighbors of a node given its id
   * @param nodeLabel
   * @param id
   * @param graphName
   * @param edgeLabel
   * @param limit
   * @return
   */
  def getSelectNeighbors(nodeLabel: String, id: String, graphName: String, edgeLabel: String = "", limit: Int = -1) : DataFrame = {
    var df = gcoreRunner.sparkSession.emptyDataFrame
    if(edgeLabel == ""){
      df = runSelectQuery("SELECT * MATCH (a:" + nodeLabel + ")-[e]->(b) ON " + graphName + " WHERE a.id=" + id)
    }else {
      df = runSelectQuery("SELECT * MATCH (a:" + nodeLabel + ")-[e:"+ edgeLabel + "]->(b) ON " + graphName + " WHERE a.id=" + id)
    }
    val aCols = DataFrameUtils.columnsStartingWith(df, List("a"))//drops the columns starting with to return only the neighbors data
    val result = df.drop(aCols:_*)
    if(limit == -1){
      result
    }else {
      result.limit(limit)
    }
  }

  /**
   * Get the path property graph of all neighbors of a given node and its id
   * @param nodeLabel
   * @param id
   * @param graphName
   * @param edgeLabel
   * @return
   */
  def getConstructNeighbors(nodeLabel: String, id: String, graphName: String, edgeLabel: String = "") : PathPropertyGraph = {
    if(edgeLabel == ""){
      runConstructQuery("CONSTRUCT (a:" + nodeLabel + ")-[e]->(b)  MATCH (a:" + nodeLabel + ")-[e]->(b) ON " + graphName + " WHERE a.id=" + id)
    }else {
      runConstructQuery("CONSTRUCT (a:" + nodeLabel + ")-[e:"+ edgeLabel + "]->(b) MATCH (a:" + nodeLabel + ")-[e:"+ edgeLabel + "]->(b) ON " + graphName + " WHERE a.id=" + id)
    }
  }

  def getGraph(graphName: String) : PathPropertyGraph = {
    gcoreRunner.catalog.graph(graphName)
  }

  /**
   * Get all the nodes of a given label of GCore, returns a tables in which each row contains a node instance of this label in the graph
   * @param nodesLabel
   * @param graphName
   * @param limit
   * @return
   */
  def getNodesOfLabel(nodesLabel: String, graphName: String, limit:Int = -1) : DataFrame = {
    if(limit == -1){
      runSelectQuery("SELECT * MATCH (a:" + nodesLabel + ") ON " + graphName)
    }else {
      runSelectQuery("SELECT * MATCH (a:" + nodesLabel + ") ON " + graphName).limit(limit)
    }
  }


  def constructQueryLabel(label1: String, limit: Int = -1, graph1: String): PathPropertyGraph = {
    val graph = "CordWiki"
    val label = "similar_names"
    val originalGraph = gcoreRunner.catalog.graph(graph.trim)//.graph(graph)
    println(gcoreRunner.catalog.allGraphsKeys.mkString(","))
    println(graph)
    println(originalGraph.schemaString)
    val restriction = originalGraph.edgeRestrictions.get(Label(label)).get
    val labelTable =
      if(limit != -1){
        originalGraph.edgeData.filter(_.name.value == label).head.data.asInstanceOf[DataFrame].where(col("fromId").notEqual(col("toId"))).limit(limit)
      }else originalGraph.edgeData.filter(_.name.value == label).head.data.asInstanceOf[DataFrame]
    val sourceIds = labelTable.select("fromId")
    val targetIds = labelTable.select("toId")
    val sourceTable_1 = originalGraph.vertexData.filter(_.name.value == restriction._1.value).head.data.asInstanceOf[DataFrame]
    val targetTable_1 = originalGraph.vertexData.filter(_.name.value == restriction._2.value).head.data.asInstanceOf[DataFrame]
    val sourceTable = sourceTable_1.join(sourceIds, sourceTable_1.col("id")=== sourceIds.col("fromId"), "inner").dropDuplicates("id")
    val targetTable = targetTable_1.join(targetIds, targetTable_1.col("id")=== targetIds.col("toId"), "inner").dropDuplicates("id")
    targetTable.show(10)
    sourceTable.show(10)
    new SparkGraph {
      override var graphName: String = "result"

      override def edgeRestrictions: LabelRestrictionMap = SchemaMap(Map(Label(label) -> restriction))

      override def storedPathRestrictions: LabelRestrictionMap = SchemaMap.empty

      override def vertexData: Seq[schema.Table[DataFrame]] = Seq(Table(restriction._1, sourceTable), Table(restriction._2,targetTable))

      override def edgeData: Seq[schema.Table[DataFrame]] = Seq(Table(Label(label), labelTable))

      override def pathData: Seq[schema.Table[DataFrame]] = Seq.empty
    }
  }


}
