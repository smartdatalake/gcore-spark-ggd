package application

import ggd.GcoreRunner
import ggd.utils.{DataFrameUtils, GGDtoGCoreParser}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.catalog.Table
import schema.PathPropertyGraph

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

//TODO : get graph subsets and better interfaces to run more complex queries, this is just the starting API


}
