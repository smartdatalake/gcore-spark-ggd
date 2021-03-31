package spark.sql

import java.sql.Connection

import algebra.operators.{Create, Drop, GraphBuild, TableBuild, UnionBuild, View}
import algebra.trees.AlgebraTreeNode
import common.exceptions.UnsupportedOperation
import compiler.{CompileContext, RunTargetCodeStage}
import org.apache.spark.sql.DataFrame
import schema.PathPropertyGraph
import spark.{Directory, SaveGraph}

case class SqlJDBCRunner(compileContext: CompileContext, con: Connection) extends RunTargetCodeStage {

  override def runStage(input: AlgebraTreeNode): PathPropertyGraph = {
    val sparkSqlPlanner: SqlJDBCPlanner = SqlJDBCPlanner(compileContext, con)
    input match {
      case (buildGraph: GraphBuild) =>
        val matchClause: AlgebraTreeNode = buildGraph.matchClause
        val groupConstructs: Seq[AlgebraTreeNode] = buildGraph.groupConstructs
        val matchWhere: AlgebraTreeNode = buildGraph.matchWhere

        val matchData: DataFrame = sparkSqlPlanner.solveBindingTable(matchClause,matchWhere)
        val constructBindingTable : DataFrame = sparkSqlPlanner.generateConstructBindingTable(matchData, groupConstructs)
        val graph: PathPropertyGraph = sparkSqlPlanner.constructGraph(constructBindingTable, groupConstructs)

        println(graph.yarspg)
        //val saveGraph = SaveGraph()
        //saveGraph.saveJsonGraph(graph,compileContext.catalog.databaseDirectory)
        graph

      case (storeGraph : Create) =>
        var graph: PathPropertyGraph = runStage(storeGraph.children.head)
        graph.graphName = storeGraph.getGraphName
        compileContext.catalog.registerGraph(graph)

        val saveGraph = SaveGraph()
        saveGraph.saveJsonGraph(graph,compileContext.catalog.databaseDirectory)
        graph

      case (dropGraph : Drop) =>
        val graph = compileContext.catalog.graph(dropGraph.graphName)
        compileContext.catalog.unregisterGraph(graph)
        val directory: Directory = new Directory
        val dropped = directory.deleteGraph(graph.graphName,compileContext.catalog.databaseDirectory)
        if(dropped)
          println("The graph was successfully dropped")
        else
          println("The graph was only dropped from the catalog, please check database directory.")
        null

      case (viewGraph : View) =>
        val graph: PathPropertyGraph = runStage(viewGraph.children.head)
        graph.graphName = viewGraph.getGraphName
        compileContext.catalog.registerGraph(graph)
        graph

      case (selectGraph: TableBuild) =>
        //println("Table Build!!!")
        val matchClause: AlgebraTreeNode = selectGraph.matchClause
        val selectClause: AlgebraTreeNode = selectGraph.selectClause
        val matchWhere: AlgebraTreeNode = selectGraph.matchWhere

        val matchData: DataFrame = sparkSqlPlanner.solveBindingTable(matchClause,matchWhere)
        //println("Binding Table Match Data Table Build:::")
        //matchData.foreach( Row => println(Row))
        //build binding table -- not needed, for now
        //apply select in matchData table
        //println("Matched data::::::")
        //matchData.show(10)
        //print("Construct Next")
        val result: DataFrame = sparkSqlPlanner.selectMatchData(matchData, selectClause)
        println(result.show())
        //select in select clause - create entity
        //build graph with just one table result - easier to deal (for now)
        val graph: PathPropertyGraph = sparkSqlPlanner.constructSelectGraph(result)
        graph
      case (unionGraph: UnionBuild) =>
        val graph: PathPropertyGraph = runStage(unionGraph.graphBuildQuery) //ok!
        val fullGraph : PathPropertyGraph = sparkSqlPlanner.unionGraph(graph, unionGraph.graphUnion.children)
        //use graph to union with the other graph
        //println(fullGraph.toString)
        //println(fullGraph.schemaString)
        //println(fullGraph.vertexData.head.data.asInstanceOf[DataFrame].show(2))
        fullGraph
      case _ =>
        throw UnsupportedOperation(s"Cannot run query on input type ${input.name}")
    }
  }

}
