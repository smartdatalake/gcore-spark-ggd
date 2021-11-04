package ggd

import java.sql.Connection

import akka.actor.ActorSystem
import algebra.expressions.Label
import common.RandomIdGenerator.randomId
import common.RandomNameGenerator.randomString
import ggd.utils.{DataFrameUtils, GGDtoGCoreParser, RAWUtils, selectMatch}
import org.apache.hadoop.hbase.types.RawBytes
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, Row}
import schema.EntitySchema.LabelRestrictionMap
import schema.{PathPropertyGraph, SchemaMap, Table}
import spark.SparkGraph

import scala.collection.mutable.ArrayBuffer

case class GraphGenerationV3(gcoreRunner: GcoreRunner) {
  var dependency: GraphGenDep = null
  val generationInformation : ArrayBuffer[generatedInfo] = new ArrayBuffer[generatedInfo]()

  def deleteNullValues(df: DataFrame): DataFrame = {
    var dfNoNulls = df
    val exprs = df.columns.map((_ -> "count")).toMap
    val cnts = df.agg(exprs).first
    for (c <- df.columns) {
      val uses = cnts.getAs[Long]("count(" + c + ")")
      if (uses == 0) {
        dfNoNulls = dfNoNulls.drop(c)
      }
    }
    return dfNoNulls
  }

  def checkDistanceTargetUDF(row: Row, cons: Constraint, ggd: GraphGenDep): Boolean = {
    var distResult: Double = 0
    var firstValue, secondValue: String = ""
    val variablesInSourceGP = ggd.sourceGP.map(_.edges.map(_.variable)) ++ ggd.sourceGP.map(_.vertices.map(_.variable))
    try {
      var firstValue = row.getAs[String](cons.var1 + "$" + cons.attr1)
      var secondValue = row.getAs[String](cons.var2 + "$" + cons.attr2)
    } catch {
      case e: Exception => return false
    }
    val dist: distanceFunctions = new distanceFunctions
    cons.distance match {
      case "edit" => distResult = dist.editDistance(firstValue, secondValue) //value.variable.attr1
      case "euclidean" => distResult = dist.EuclideanDistance(dist.getArray(firstValue), dist.getArray(secondValue))
      case "diff" => distResult = dist.Diff(dist.getArray(firstValue), dist.getArray(secondValue))
      case "equal" => if (row.getAs[String](cons.var1 + "$id") == row.getAs[String](cons.var2 + "$id")) return true else return false
    }
    cons.operator match {
      case "<=" => return (distResult <= cons.threshold)
      case "=" => return (distResult == cons.threshold)
      case ">=" => return (distResult >= cons.threshold)
      case "<" => return (distResult < cons.threshold)
      case ">" => return (distResult > cons.threshold)
    } //returns true if satisfiable/ false if not
  }


  // def constraintScoreTarget(row: Row, ggd : GraphGenDep) : Double = {
  def constraintScoreTarget(row: Row): Double = {
    val ggd = dependency
    var score: Int = 0
    for (c <- ggd.targetCons) {
      if (checkDistanceTargetUDF(row, c, ggd)) {
        score = score + 1
      }
    }
    score
  }

  def constraintUdf = udf((row: Row) => constraintScoreTarget(row))

  def optionalFullQuery(ggd: GraphGenDep, violatedDf: DataFrame): SparkGraph = {
    dependency = ggd
    val sourceVariables: List[String] = ggd.sourceGP.map(x => x.parseToGCoreSelect()).flatMap(_.variables)
    val targetVariables: List[String] = ggd.targetGP.head.vertices.map(_.variable) ++ ggd.targetGP.head.edges.map(_.variable)
    val commonVar: List[String] = GGDtoGCoreParser.commonVariables(sourceVariables, targetVariables)
    violatedDf.show(10)
    val commonVarIds: List[String] = commonVar.map(x => x + "$id")
    if (!commonVar.isEmpty) {
      var queryResult = gcoreRunner.sparkSession.emptyDataFrame
      var violatedResult = gcoreRunner.sparkSession.emptyDataFrame
      if(ggd.sourceGP.map(x => x.name).distinct.size > 1 || (ggd.sourceGP.size > 1 && !gcoreRunner.catalog.graph(ggd.sourceGP.head.name).edgeSchema.labels.contains(ggd.targetGP.head.name))){
        val matchOptionalClause = GGDtoGCoreParser.partialPattern(ggd.targetGP.head, commonVar)
        val queries = matchOptionalClause.matchClause.split(",").map( x =>{
          "SELECT * MATCH " + x + " OPTIONAL " + matchOptionalClause.optionalClause
        })
        val results = queries.map( query => {
          try{
            gcoreRunner.compiler.compilePropertyGraph(query).asInstanceOf[DataFrame]
          }catch {
            case e: Exception => {
              gcoreRunner.compiler.compilePropertyGraph(query.split("OPTIONAL").apply(0)).asInstanceOf[DataFrame]
            }
          }
        })
        //join results with violated result
        val commonIds = results.head.asInstanceOf[DataFrame].columns.map(e => e.split('$').apply(0)).distinct.filter(p => p != "sid").map(x => (x + "$id"))
        violatedResult = violatedDf.join(results.head.asInstanceOf[DataFrame], commonIds)
        for(i <- 1 to results.size-1){
          val commonIds = results.apply(i).asInstanceOf[DataFrame].columns.map(e => e.split('$').apply(0)).distinct.filter(p => p != "sid").map(x => (x + "$id"))
          violatedResult = violatedResult.join(results.apply(i).asInstanceOf[DataFrame], commonIds)
        }
        violatedResult = DataFrameUtils.removeDuplicateColumns(violatedResult).cache()
        //println(violatedResult.count())
      }else{
        val matchOptionalClause = GGDtoGCoreParser.partialPattern(ggd.targetGP.head, commonVar)
        var query: String = "SELECT * MATCH " + matchOptionalClause.matchClause + " OPTIONAL " + matchOptionalClause.optionalClause
        println("Query:" + query)
        try {
          queryResult = gcoreRunner.compiler.compilePropertyGraph(query).asInstanceOf[DataFrame]
        } catch {
          case e: Exception => {
            queryResult = gcoreRunner.compiler.compilePropertyGraph(query.split("OPTIONAL").apply(0)).asInstanceOf[DataFrame]
          }
        }
        println("Optional Query Result:: " + queryResult.count())
        queryResult.show(5)
        var consQuery: String = ""
        violatedResult = DataFrameUtils.removeDuplicateColumns(violatedDf.join(queryResult, commonVarIds, "Inner"))//.cache()
      }
      if (ggd.targetCons.nonEmpty) {
        val violatedResultScore = violatedResult.withColumn("ConstraintScore", constraintUdf(struct(violatedResult.columns.map(col): _*)))
        //val groupedData = violatedResultScore.groupBy(commonVar.head, commonVar.tail: _*).max("ConstraintScore")
        val groupedDataScore = violatedResultScore.groupBy(commonVarIds.head, commonVarIds.tail: _*).max("ConstraintScore")
        val groupedData = groupedDataScore.join(violatedResultScore, commonVarIds, "Inner")
        val resultData: DataFrame = deleteNullValues(groupedData)
        val resultGraph: SparkGraph = generateGraph(ggd, resultData, commonVar)
        gcoreRunner.catalog.registerGraph(resultGraph)
        return resultGraph
      } else {
        val resultData = violatedDf
        val resultGraph: SparkGraph = generateGraph(ggd, resultData, commonVar)
        println(resultGraph.graphName)
        gcoreRunner.catalog.registerGraph(resultGraph)
        return resultGraph
      }
    } else {
      //run construct query matching anything
      val fullPatternConstruct: selectMatch = GGDtoGCoreParser.parseGCoreSelectMatch(ggd.targetGP)
      val resultGraph: SparkGraph = gcoreRunner.compiler.compilePropertyGraph("CONSTRUCT" + fullPatternConstruct.matchClause + "MATCH (c:Person) WHERE c.id=101 ON people_graph").asInstanceOf[SparkGraph]
      gcoreRunner.catalog.registerGraph(resultGraph)
      return resultGraph
    }
  }

  //left outer joins not supported, always assume that needs to be generated --> prototype: does not consider constraints of the target or information might already be in in the graph
  def jdbcGeneration(ggd: GraphGenDep, violatedDf: DataFrame, connection: Connection, path: String, rawURI: String, rawToken: String, rawSave: String): SparkGraph = {
    implicit val system = ActorSystem()
    val rawUtils = new RAWUtils()
    val variablesX = (ggd.sourceGP.map(x => x.vertices.map(_.variable)).flatten ++ ggd.sourceGP.map(x => x.edges.map(_.variable)).flatten).distinct
    val variablesTarget = (ggd.targetGP.map(x => x.vertices.map(_.variable)).flatten ++ ggd.targetGP.map(x => x.edges.map(_.variable)).flatten).distinct
    val variablesY = variablesX.diff(variablesTarget)
    val commonVar = variablesX.intersect(variablesTarget)
    //assume that there are no Y variables in the target GP if its violated
    //violated GGDs --> violatedDf
    //check what is not in the pattern --> variablesY
    //generate the edges
    val resultData = violatedDf
    val resultGraph: SparkGraph = generateGraph(ggd, resultData, commonVar)
    println(resultGraph.graphName)
    //save to graph
    gcoreRunner.catalog.registerGraph(resultGraph)
    //save to proteus --> use the generated information log to check which files should be saved to proteus
    val tablesGenerated = generationInformation.map(_.name)
    if(!path.isEmpty){
      //save as json to hdfs or to path selected
      val tablesData = resultGraph.vertexData.filter(p => tablesGenerated.contains(p.name.value))
      val edgesData = resultGraph.edgeData.filter(p => tablesGenerated.contains(p.name.value))
      val name = ggd.targetGP.head.name//target graph name --> resultGraph is intermediate data
      if(!tablesData.isEmpty){
        tablesData.foreach(x => {
          saveFileRAW(path, x.name.value, x.data.asInstanceOf[DataFrame], x.graphName, rawSave)
          val rawQuery = getQuerySaveType(rawSave, path, x.name.value)
          rawUtils.createViews(rawURI, x.name.value, rawQuery, rawToken)
        })
      }
      if(!edgesData.isEmpty){
        edgesData.foreach(x => {
          saveFileRAW(path, x.name.value, x.data.asInstanceOf[DataFrame], x.graphName, rawSave)
          //query to create view
          val rawQuery = getQuerySaveType(rawSave, path, x.name.value)
          rawUtils.createViews(rawURI, x.name.value, rawQuery, rawToken)
        })
      }
    }
    return resultGraph
    //return new graph
  }

  def getQuerySaveType(saveType: String, path: String, fileName: String): String = {
    saveType match {
      /*case "s3" => "read_json(\"s3://" + path+"/" + fileName + ".json" + "\")"
      case "hdfs" => "read_json(\"hdfs://" + path+"/" + fileName + ".json" + "\")"
      case "file" =>  "read_json(\"file:/" + path+"/" + fileName + ".json" + "\")"*/
      case "s3" => "read_many_json(\"s3://" + path+"/" + fileName + "/p*" + "\")"
      case "hdfs" => "read_many_json(\"hdfs://" + path+"/" + fileName + "/p*" + "\")"
      case "file" => "read_many_json(\"file:/" + path+"/" + fileName + "/p*" + "\")"
    }
  }
  //read_many_json(path + filename + "/*.json)

  def saveFileRAW(path: String, labelName: String, data: DataFrame, graphName: String, saveType: String): Unit = {
    //it will always save to an hdfs so RAW can access it, be careful with the path value
    val pathSave = saveType match {
      case "s3" => "s3://" + path + "/" + labelName
      case "hdfs" => "hdfs://" + path + "/" + labelName
      case "file" => path+"/" + labelName
    }
    val count = data.count()
    data.toJSON
      //.coalesce(1) // make sure it is only one partition and in consequence one output file
      .rdd
      .zipWithIndex()
      .map { case(json, idx) =>
        if(idx == 0) "[" + json + ","
        else if(idx == count-1) json + "]"
        else json + ","
      }
      .saveAsTextFile(path)
    //save as a correct json file
  }

  def generateGraph(ggd: GraphGenDep, dfResult: DataFrame, commonVar: List[String]) : SparkGraph = {
    var df = dfResult
    val targetGraph = gcoreRunner.catalog.graph(ggd.targetGP.head.name)// where the nodes/edges should be generated
    val CommonVarPattern: GraphPattern = GGDtoGCoreParser.createPattern(ggd.targetGP.head, commonVar)
    val whatTobeGenerated : GraphPattern = graphPatternDifference(ggd.targetGP.head, CommonVarPattern)
    val generatedVertices : ArrayBuffer[Table[DataFrame]] = new ArrayBuffer[Table[DataFrame]]()
    if(ggd.targetCons.isEmpty){ //if there are no target constraints can generate just one node for the whole matched table
      for(vertex <- whatTobeGenerated.vertices){
        try{ //when there are few vertices in the table already
          var hasNoVertex : DataFrame = df.filter(col(vertex.variable+"$id").isNull).withColumn(vertex.variable+"$id", lit(vertex.variable+randomId(6).toInt))
          val hasVertex: DataFrame = df.filter(col(vertex.variable+"$id").isNotNull)
          val tableData = hasNoVertex.select(vertex.variable+"$id").distinct().withColumnRenamed(vertex.variable+"$id", "id")
          df = hasNoVertex.union(hasVertex)
          val generatedVertexTable = new Table(Label(vertex.label), tableData)
          generationInformation += new generatedInfo(vertex.label, tableData.count())
          generatedVertices += generatedVertexTable
        }catch {
          case e: Exception => {
            println("df size:" + df.count())
            df = df.withColumn(vertex.variable+"$id", monotonically_increasing_id())//generates only one or one per project? -> if one per project monotonically increasing
            df.show(10)
            val tableData = df.select(vertex.variable+"$id").distinct().withColumnRenamed(vertex.variable+"$id", "id")
            tableData.show(10)
            val generatedVertexTable = new Table[DataFrame](Label(vertex.label), tableData)
            generationInformation += new generatedInfo(vertex.label, tableData.count())
            generatedVertices += generatedVertexTable
          }
        }
      }
    }
    println("Number of tables generated:" + generatedVertices.size)
    val edgesTable: Seq[Table[DataFrame]] = whatTobeGenerated.edges.map(e => generateTableEdge(e,df))
    edgesTable.foreach(r => {
      println("Edge table: " + r.data.count())
    })
    //build restriction map based on target pattern
    val edgesRestrictions = SchemaMap(whatTobeGenerated.edges.map( e => (Label(e.label.trim) -> (Label(e.fromLabel.trim), Label(e.toLabel.trim)))).toMap)
    println(edgesRestrictions.toString)
    val resultGraph : SparkGraph = new SparkGraph {
      override var graphName: String = ggd.name

      override def edgeRestrictions: LabelRestrictionMap = edgesRestrictions

      override def storedPathRestrictions: LabelRestrictionMap = SchemaMap.empty

      override def vertexData: Seq[Table[DataFrame]] = generatedVertices.toSeq

      override def edgeData: Seq[Table[DataFrame]] = edgesTable

      override def pathData: Seq[Table[DataFrame]] = Seq.empty
    }
    return resultGraph
  }

  val columnFunctionId = udf((v: String) => randomString(6).concat(v))

  def generateTableEdge(e: EdgesPattern, result: DataFrame) : Table[DataFrame] = {
    val fromToId = result.select(col(e.fromVariable+"$id").as("fromId"), col(e.toVariable+"$id").as("toId")).withColumn("table_var", lit(e.variable)).dropDuplicates()
    println("Edge generation!")
    val tableEdge = fromToId.withColumn("id", columnFunctionId(fromToId.col("table_var"))).drop("table_var")
    println(tableEdge.count())
    println(tableEdge.show(10))
    generationInformation += new generatedInfo(e.label, tableEdge.count())
    return new Table[DataFrame](Label(e.label), tableEdge)
  }


  def generateIds(targetGraph: PathPropertyGraph, generateSize: Long, label: Label, variable: String) : Seq[String] = {
    if(targetGraph.vertexSchema.labels.contains(label)){
      val idSeq: Seq[String] = Seq.fill[String](generateSize.toInt){variable+randomString(6)}
      //TODO verify if there is any ids equals generated in the table
      return idSeq
    }else{
      val idSeq: Seq[String] = Seq.fill[String](generateSize.toInt){variable+randomString(6)}
      return idSeq
    } //generateRandomValues that are not in the graph
  }

  def graphPatternDifference(target: GraphPattern, common: GraphPattern) : GraphPattern = {
    new GraphPattern(target.name, target.vertices.diff(common.vertices), target.edges.diff(common.edges))
  }


}

case class generatedInfo(name: String, number: Long, ggd: String = "")

case class generatedInfoGraph(name: String, number: Long, ggd:String = "", graphName: String, id: Int)