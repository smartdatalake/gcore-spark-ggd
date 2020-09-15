
package spark.sql

import algebra.operators._
import algebra.trees.AlgebraTreeNode
import common.exceptions.UnsupportedOperation
import compiler.{CompileContext, RunTargetCodeStage}
import ggd.utils.DataFrameUtils
import org.apache.spark.sql.Row
//import gui.GcoreGUI
import org.apache.commons.io.output.ByteArrayOutputStream
import org.apache.spark.sql.DataFrame
import schema.PathPropertyGraph
import spark.{Directory, SaveGraph}


/** Runs the query plan created by the [[SqlPlanner]] on Spark. */
case class SqlPartialRunner(compileContext: CompileContext, data: DataFrame)  extends RunTargetCodeStage {

  override def runStage(input: AlgebraTreeNode): PathPropertyGraph = {
    val sparkSqlPlanner: SqlPlanner = SqlPlanner(compileContext)
    input match {
      case (buildGraph: GraphBuild) =>
        val matchClause: AlgebraTreeNode = buildGraph.matchClause
        val groupConstructs: Seq[AlgebraTreeNode] = buildGraph.groupConstructs
        val matchWhere: AlgebraTreeNode = buildGraph.matchWhere

        //val matchData: DataFrame = sparkSqlPlanner.solveBindingTable(matchClause, matchWhere)
        //val matchData: DataFrame = data
        val matchData: DataFrame = DataFrameUtils.removeDuplicateColumns(data)
        //matchData.show(10)
        val constructBindingTable: DataFrame = sparkSqlPlanner.generateConstructBindingTable(matchData, groupConstructs)
        //constructBindingTable.show(10)
        //drop columns with duplicated name
        val constructBindingTableNonDuplicate = DataFrameUtils.removeDuplicateColumns(constructBindingTable)
        //constructBindingTableNonDuplicate.show(10)
       // val graph: PathPropertyGraph = sparkSqlPlanner.constructGraph(constructBindingTable, groupConstructs)
        val graph: PathPropertyGraph = sparkSqlPlanner.constructGraph(constructBindingTableNonDuplicate, groupConstructs)
        /*if(GcoreGUI.resultArea != null)
          GcoreGUI.resultArea.setText(graph.yarspg)
        if (GcoreGUI.resultTabularArea != null)
        {
          val outCapture = new ByteArrayOutputStream
          Console.withOut(outCapture) {
            constructBindingTable.show(false)
          }
          val result = new String(outCapture.toByteArray)
          GcoreGUI.resultTabularArea.setText(result)

        }
        if (GcoreGUI.resultInfo != null)
          GcoreGUI.resultInfo.setText(graph.toString)*/

        println(graph.yarspg)
        //val saveGraph = SaveGraph()
        //saveGraph.saveJsonGraph(graph,compileContext.catalog.databaseDirectory)
        graph
      case _ =>
        throw UnsupportedOperation(s"Cannot run query on input type ${input.name}")
    }
  }
}
