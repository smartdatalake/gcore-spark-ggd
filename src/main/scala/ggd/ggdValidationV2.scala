package ggd

import java.io.PrintWriter
import java.sql.Connection

import SimSQL.SimilarityAPI
import ggd.utils.{DataFrameUtils, GGDtoGCoreParser, VernicaJoinAthena, selectMatch}
import org.apache.commons.configuration2.tree.QueryResult
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{col, lower}
import org.apache.spark.storage.StorageLevel
import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization
import schema.EntitySchema.LabelRestrictionMap
import schema.{PathPropertyGraph, Table}
import spark.SparkGraph

import scala.collection.mutable.ArrayBuffer

case class Violated(data: DataFrame, ggd: GraphGenDep)

case class SelectedSource(numberElements: Long, ggd: String)

case class ggdValidationV2(gcoreRunner: GcoreRunner) {
  val sourceSize: ArrayBuffer[SelectedSource] = new ArrayBuffer[SelectedSource]()
  var genInfo: ArrayBuffer[generatedInfo] = new ArrayBuffer[generatedInfo]()
  implicit val formats = DefaultFormats
  import gcoreRunner.sparkSession.implicits._

  def graphGenerationProteus(violatedGGDs: Violated, connection: Connection, path: String, raw_uri: String, raw_token: String, raw_save: String): PathPropertyGraph = {
    val y: GraphGenerationV3 = new GraphGenerationV3(gcoreRunner)
    val graph: SparkGraph = y.jdbcGeneration(violatedGGDs.ggd, violatedGGDs.data, connection, path, raw_uri, raw_token, raw_save)
    genInfo = y.generationInformation
    graph.vertexData.foreach(a => a.graphName = graph.graphName)
    graph.edgeData.foreach(a => a.graphName = graph.graphName)
    graph.pathData.foreach(a => a.graphName = graph.graphName)
    val unionGraph = unionTargetGraph(graph, violatedGGDs.ggd.targetGP.head.name)
    println("Graph unioned:::")
    println(unionGraph.schemaString)
    gcoreRunner.catalog.unregisterGraph(violatedGGDs.ggd.targetGP.head.name)
    gcoreRunner.catalog.registerGraph(unionGraph)
    gcoreRunner.catalog.setDefaultGraph(unionGraph.graphName)
    println(gcoreRunner.catalog.graph(unionGraph.graphName).schemaString)
    unionGraph
  }

  def graphGenerationV3(violatedGGDs: Violated) : PathPropertyGraph = {
    val y: GraphGenerationV3 = new GraphGenerationV3(gcoreRunner)
    val graph: SparkGraph = y.optionalFullQuery(violatedGGDs.ggd, violatedGGDs.data)
    genInfo = y.generationInformation
    graph.vertexData.foreach(a => a.graphName = graph.graphName)
    graph.edgeData.foreach(a => a.graphName = graph.graphName)
    graph.pathData.foreach(a => a.graphName = graph.graphName)
    val unionGraph = unionTargetGraph(graph, violatedGGDs.ggd.targetGP.head.name)
    println("Graph unioned:::")
    println(unionGraph.schemaString)
    gcoreRunner.catalog.unregisterGraph(violatedGGDs.ggd.targetGP.head.name)
    gcoreRunner.catalog.registerGraph(unionGraph)
    gcoreRunner.catalog.setDefaultGraph(unionGraph.graphName)
    println(gcoreRunner.catalog.graph(unionGraph.graphName).schemaString)
    unionGraph
  }

  def unionTargetGraph(graph: SparkGraph, targetGraph: String) : SparkGraph = {
    val catalogGraph: SparkGraph =  gcoreRunner.catalog.graph(targetGraph).asInstanceOf[SparkGraph]
    catalogGraph.vertexData.foreach(a => a.graphName = catalogGraph.graphName)
    catalogGraph.edgeData.foreach(a => a.graphName = catalogGraph.graphName)
    catalogGraph.pathData.foreach(a => a.graphName = catalogGraph.graphName)
    //create Graph
    val vData: Seq[Table[DataFrame]] = unionData(graph.vertexData ++ catalogGraph.vertexData)
    val eData: Seq[Table[DataFrame]] = unionData(graph.edgeData ++ catalogGraph.edgeData)
    val pData: Seq[Table[DataFrame]] = unionData(graph.pathData ++ catalogGraph.pathData)

    val resultGeneratedGraph = new SparkGraph {
      override var graphName: String =  targetGraph//+"UnionedGraph"
      //seq of seqs
      override def vertexData: Seq[Table[DataFrame]] = vData

      override def edgeData: Seq[Table[DataFrame]] = eData
      override def pathData: Seq[Table[DataFrame]] = pData
      override def edgeRestrictions: LabelRestrictionMap = graph.edgeRestrictions.union(catalogGraph.edgeRestrictions)

      override def storedPathRestrictions: LabelRestrictionMap = graph.storedPathRestrictions.union(catalogGraph.storedPathRestrictions)
    }
    resultGeneratedGraph
  }

  def unionData(buffer: Seq[Table[SparkGraph#StorageType]]) : Seq[Table[DataFrame]] = {
    val seqTable: ArrayBuffer[Table[DataFrame]] = new ArrayBuffer[Table[DataFrame]]()
    for(a<-buffer){
      for(b<-buffer){
        if(a.name == b.name && !seqTable.map(_.name).contains(a.name) && a.graphName != b.graphName){
          seqTable += Table(a.name, a.data.join(b.data, a.data.columns, "fullOuter").persist(StorageLevel.DISK_ONLY))
        }
      }
    }
    for(a <- buffer){
      if(!seqTable.map(_.name).contains(a.name)){
        seqTable += a
      }
    }
    seqTable
  }

  //check the generation of only the violated data -> only for unit tests
  //when having more than1 constraints the other constratins can generate data that violates another constraints this function does not consider that
  def checkGeneration(violated : Violated, generatedGraph : PathPropertyGraph) : Boolean = {
    val query: String = GGDtoGCoreParser.parseGCore(violated.ggd.targetGP)
    val parsed: selectMatch = GGDtoGCoreParser.parseGCoreSelectMatch(violated.ggd.targetGP)
    val targetQuery: DataFrame = gcoreRunner.compiler.compilePropertyGraph(query).asInstanceOf[DataFrame]
    val commonVar = GGDtoGCoreParser.commonVariablesGP(violated.ggd.sourceGP, violated.ggd.targetGP)
    val columnsToSelect = GGDtoGCoreParser.commonVariablesGP(violated.ggd.sourceGP, violated.ggd.targetGP).map( a => a + "$id")
    var df: DataFrame = violated.data
    val forConstraintCheck: DataFrame = df.join(targetQuery, GGDtoGCoreParser.commonColumns(commonVar, df.columns), "Inner")
    //for constraint check -> check if in join all original ids are in the dataframe
    val joinedIds = forConstraintCheck.select(columnsToSelect.head, columnsToSelect.tail:_*)
    if(joinedIds.intersect(df.select(columnsToSelect.head, columnsToSelect.tail:_*)).count() != df.count()) return false
    if(forConstraintCheck.filter(TargetConstraintFilter(violated.ggd.targetCons, violated.ggd.sourceGP)).count() == df.count()) return true
    return false
  }

  def ValidationProteus(ggd: GraphGenDep, con: Connection) : Violated = {
    //source graph pattern
    val dist: distanceFunctions = new distanceFunctions;
    val gcoreSelectMatch : selectMatch = GGDtoGCoreParser.parseGCoreSelectMatch(ggd.sourceGP)
    var queryResults = Array.empty[DataFrame]
    var result = gcoreRunner.sparkSession.emptyDataFrame
    if(ggd.sourceGP.size > 1){
      val queries = ggd.sourceGP.map(x => GGDtoGCoreParser.parseGCoreSelectMatch(List(x)))
      val graphPatterns = queries.map(m => "SELECT * MATCH " + m.matchClause).toArray
      queryResults = graphPatterns.map(query => gcoreRunner.compiler.compilerJDBC(query, con).asInstanceOf[DataFrame].persist(StorageLevel.MEMORY_AND_DISK_SER))
      result = filteredResults(ggd, queryResults, ggd.sourceCons)
    }else{
      val query = "SELECT * MATCH " + gcoreSelectMatch.matchClause
      queryResults = Array(gcoreRunner.compiler.compilerJDBC(query, con).asInstanceOf[DataFrame])
      result = filteredResults(ggd, queryResults, ggd.sourceCons)
    }
    println("filtered Results!!")
    println("Satisfied source patterns::" + result.show(10))
    //sourceSize += SelectedSource(result.count(), ggd.name)
    if(result.isEmpty){
      return Violated(gcoreRunner.sparkSession.emptyDataFrame, ggd) //if result is empty non ggd should be violated
    }
    //target graph pattern
    //target GP -> only takes head pattern (one graph pattern, if needed more than that declare in different GGDs
    // change target to only does not have in source (same as graph generation)
    val sourceVariables: List[String] = ggd.sourceGP.map(x => x.parseToGCoreSelect()).flatMap(_.variables)
    val targetVariables: List[String] = ggd.targetGP.head.vertices.map(_.variable) ++ ggd.targetGP.head.edges.map(_.variable)
    val commonVarPattern: List[String] = GGDtoGCoreParser.commonVariables(sourceVariables, targetVariables)
    val matchOptionalClause = GGDtoGCoreParser.partialPattern(ggd.targetGP.head, commonVarPattern)
    val targetQuery: String = "SELECT * MATCH " + matchOptionalClause.optionalClause
    var targetResult: DataFrame = gcoreRunner.sparkSession.emptyDataFrame
    result = DataFrameUtils.removeDuplicateColumns(result)
    if(matchOptionalClause.optionalClause == ""){ //case for empty target -> only source variables in the target constraint
      val filtered = filteredResults(ggd, Array(result), ggd.targetCons)
      return Violated(result.except(filtered), ggd)
    }
    var caughtExeception: Boolean = false
    try{
      targetResult =  gcoreRunner.compiler.compilerJDBC(targetQuery, con).asInstanceOf[DataFrame]//.cache()
    }catch {
      case nonExistingLabel : algebra.exceptions.DisjunctLabelsException => {
        return new Violated(result, ggd)
      }
      case e: Exception => {
        e.printStackTrace()
        return new Violated(result, ggd)
      }
    }
    if(targetResult.isEmpty) return Violated(result, ggd)
    targetResult.show(10)
    //get common variables for constraint checking + getting all the data needed for the ggds checking in one df
    val commonVar: List[String] = result.columns.map(_.split('$').apply(0)).intersect(targetResult.columns.map(_.split('$').apply(0))).distinct.toList
    if(!commonVar.isEmpty){
      val filteredSourceTarget = DataFrameUtils.removeDuplicateColumns(filteredResults(ggd, Array(targetResult, result), ggd.targetCons))
      val variables = ggd.sourceGP.map(_.vertices.map(_.variable)).flatten ++ ggd.sourceGP.map(_.edges.map(_.variable)).flatten
      val sourceColumnsId = variables.map(x =>  x + "$id")
      val filteredSource = filteredSourceTarget.select(sourceColumnsId.head, sourceColumnsId.tail:_*)//.dropDuplicates(sourceColumnsId.head, sourceColumnsId.tail:_*)
      val violatedSelect = result.select(sourceColumnsId.head, sourceColumnsId.tail:_*)//.except(filteredSource)
      //anti join for checking ids
      val violatedIds = violatedSelect.except(filteredSource)
      if(violatedIds.isEmpty)return Violated(gcoreRunner.sparkSession.emptyDataFrame, ggd)
      val violatedSource = result.join(violatedIds, sourceColumnsId, "inner")
      Violated(violatedSource, ggd)
    }else {
      val targetNoCommon = filteredResults(ggd, Array(targetResult), ggd.targetCons)
      if(targetNoCommon.isEmpty){
        Violated(result, ggd)
      }else{
        Violated(gcoreRunner.sparkSession.emptyDataFrame, ggd)
      }
    }
  }

  /***
   *
   * @param ggd - A graph generating dependency (one of the set inputted in the main class)
   * @return Violated class in which has the ggd which was violated and a Dataframe containing the source graph pattern instances which violates the GGDs.
   */
  def ValidationV3(ggd: GraphGenDep) : Violated = {
    val dist: distanceFunctions = new distanceFunctions;
    val gcoreSelectMatch : selectMatch = GGDtoGCoreParser.parseGCoreSelectMatch(ggd.sourceGP)
    var queryResults = Array.empty[DataFrame]
    var result = gcoreRunner.sparkSession.emptyDataFrame
    if(ggd.sourceGP.size > 1){
      val queries = ggd.sourceGP.map(x => GGDtoGCoreParser.parseGCoreSelectMatch(List(x)))
      val graphPatterns = queries.map(m => "SELECT * MATCH " + m.matchClause).toArray
      queryResults = graphPatterns.map(query => gcoreRunner.compiler.compilePropertyGraph(query).asInstanceOf[DataFrame].persist(StorageLevel.MEMORY_AND_DISK_SER)) //sequence of dataframes with graph patterns results
      result = filteredResults(ggd, queryResults, ggd.sourceCons)
    }else{
      val query = "SELECT * MATCH " + gcoreSelectMatch.matchClause
      queryResults = Array(gcoreRunner.compiler.compilePropertyGraph(query).asInstanceOf[DataFrame])
      result = filteredResults(ggd, queryResults, ggd.sourceCons)
    }
    val df = DataFrameUtils.removeDuplicateColumns(result).repartition().take(1)
    if(df.isEmpty) {
      //if(result.isEmpty){
      return Violated(gcoreRunner.sparkSession.emptyDataFrame, ggd) //if result is empty non ggd should be violated
    }
    //target graph pattern
    //target GP -> only takes head pattern (one graph pattern, if needed more than that declare in different GGDs
    // change target to only does not have in source (same as graph generation)
    val sourceVariables: List[String] = ggd.sourceGP.map(x => x.parseToGCoreSelect()).flatMap(_.variables)
    val targetVariables: List[String] = ggd.targetGP.head.vertices.map(_.variable) ++ ggd.targetGP.head.edges.map(_.variable)
    val commonVarPattern: List[String] = GGDtoGCoreParser.commonVariables(sourceVariables, targetVariables)
    val matchOptionalClause = GGDtoGCoreParser.partialPattern(ggd.targetGP.head, commonVarPattern)
    val targetQuery: String = "SELECT * MATCH " + matchOptionalClause.optionalClause
    var targetResult: DataFrame = gcoreRunner.sparkSession.emptyDataFrame
    result = DataFrameUtils.removeDuplicateColumns(result)
    if(matchOptionalClause.optionalClause == ""){ //case for empty target -> only source variables in the target constraint
      val filtered = filteredResults(ggd, Array(result), ggd.targetCons)
      return Violated(result.except(filtered), ggd)
    }
    var caughtExeception: Boolean = false
    try{
      targetResult = gcoreRunner.compiler.compilePropertyGraph(targetQuery).asInstanceOf[DataFrame]//.cache()
    }catch {
      case nonExistingLabel : algebra.exceptions.DisjunctLabelsException => {
        return new Violated(result, ggd)
      }
      case e: Exception => {
        e.printStackTrace()
        return new Violated(result, ggd)
      }
    }
    if(targetResult.isEmpty) return Violated(result, ggd)
    targetResult.repartition().cache()
    //get common variables for constraint checking + getting all the data needed for the ggds checking in one df
    val commonVar: List[String] = result.columns.map(_.split('$').apply(0)).intersect(targetResult.columns.map(_.split('$').apply(0))).distinct.toList
    if(!commonVar.isEmpty){
      val filteredSourceTarget = DataFrameUtils.removeDuplicateColumns(filteredResults(ggd, Array(targetResult, result), ggd.targetCons))
      filteredSourceTarget.cache()
      val variables = ggd.sourceGP.map(_.vertices.map(_.variable)).flatten ++ ggd.sourceGP.map(_.edges.map(_.variable)).flatten
      val sourceColumnsId = variables.intersect(commonVar).map(x =>  x + "$id")
      val filteredSource = filteredSourceTarget.select(sourceColumnsId.head, sourceColumnsId.tail:_*).repartition(sourceColumnsId.map(x => col(x)):_*)
      val violatedSelect = result.select(sourceColumnsId.head, sourceColumnsId.tail:_*).dropDuplicates().repartition(sourceColumnsId.map(x => col(x)):_*)
      violatedSelect.cache()
      filteredSource.cache()
      //anti join for checking ids
      val violatedIds = violatedSelect.except(filteredSource)
      if(violatedIds.isEmpty)return Violated(gcoreRunner.sparkSession.emptyDataFrame, ggd)
      val violatedSource = result.join(violatedIds, sourceColumnsId, "inner")
      Violated(violatedSource, ggd)
    }else {
      val targetNoCommon = filteredResults(ggd, Array(targetResult), ggd.targetCons)
      if(targetNoCommon.isEmpty){
        Violated(result, ggd)
      }else{
        Violated(gcoreRunner.sparkSession.emptyDataFrame, ggd)
      }
    }
  }

  def filteredResults(ggd: GraphGenDep, queryResults: Array[DataFrame], ggdCons: List[Constraint]) : DataFrame = {
    var result = Seq[DataFrame]()
    var constraintsForSimJoin = new ArrayBuffer[Constraint]()
    if(ggdCons.isEmpty){
      result = queryResults.toSeq
    }else{
      for(cons <- ggdCons){
        if(cons.distance == "jaccard"){
          constraintsForSimJoin += cons
        }
        if(cons.distance == "edit"){
          constraintsForSimJoin += cons
        }
      }
      result = SimJoinHandler(constraintsForSimJoin, queryResults)
    }
    if(ggdCons.isEmpty){
      if(result.size > 1){
        val a = result(0)
        val b = result(1)
        val variablesA = a.columns.map(col => col.split('$').apply(0))
        val variablesB = b.columns.map(col => col.split('$').apply(0)).distinct
        val commonVariables = GGDtoGCoreParser.commonVariables(variablesA.toList, variablesB.toList).filter(p => p!="sid").map(t => {
          t+"$id"
        })
        if(!commonVariables.isEmpty) a.select(commonVariables.head, commonVariables.tail:_*).dropDuplicates().join(b.select(commonVariables.head, commonVariables.tail:_*).dropDuplicates(), commonVariables)
        else a.crossJoin(b)
      }else {
        result(0)
      }
    }
    else{
      val originalResult = if(result.size > 1){
        val a = result(0)
        val b = result(1)
        val variablesA = a.columns.map(col => col.split('$').apply(0)).distinct
        val variablesB = b.columns.map(col => col.split('$').apply(0)).distinct
        val commonVariables = GGDtoGCoreParser.commonVariables(variablesA.toList, variablesB.toList).filter(p => p!="sid").map(t => {
          t+"$id"
        }).distinct
        if(!commonVariables.isEmpty) a.drop("sid").join(b.drop("sid"), commonVariables)//a.select(commonVariables.head, commonVariables.tail:_*).join(b.select(commonVariables.head, commonVariables.tail:_*), commonVariables)
        else a.crossJoin(b)
      }else {
        result(0)
      }
      val constraintsFilter = ggdCons.diff(constraintsForSimJoin)
      originalResult.filter(ConstraintFilter(constraintsFilter))//.cache()
    }
  }

  def runSingleJoin(constraint: Constraint, queryResults: Array[DataFrame]): DataFrame = {
    val c = constraint
    val var1 = c.var1+"$"+c.attr1
    val var2 = c.var2+"$"+c.attr2
    val columnCons = Seq(var1, var2)
    val dataFramesInCons : Seq[DataFrame] = queryResults.filter(x => x.columns.intersect(columnCons).size > 0)
    var distanceMeasure = "jaccardsimilarity"
    if(c.distance == "edit") distanceMeasure = "editsimilarity"
    if(dataFramesInCons.size == 2){
      var result: DataFrame = gcoreRunner.sparkSession.emptyDataFrame
      if(dataFramesInCons.head.columns.contains(columnCons.head)){
        result= RunSimilarityJoin(dataFramesInCons(0), dataFramesInCons(1), columnCons(0), columnCons(1),c.threshold, distanceMeasure, "simjoin")
        result.cache()
      }else{
        result = RunSimilarityJoin(dataFramesInCons.apply(1), dataFramesInCons.apply(0), columnCons.apply(1), columnCons.apply(0), c.threshold, distanceMeasure, "simjoin")
        result.cache()
      }
      return result
    }else if(dataFramesInCons.size == 1 && c.distance == "edit"){ //self similarity
      println("Size of dataframeInCons:" + dataFramesInCons.head.count())
      val result: DataFrame = RunSimilarityJoin(dataFramesInCons.head, dataFramesInCons.head, columnCons.apply(0), columnCons.apply(1),c.threshold, distanceMeasure, "simjoin")
      println("Result size:" + result.count())
      result.rdd.localCheckpoint()
      return result
    }else if(dataFramesInCons.size == 1 && c.distance == "jaccard") { //self similarity
      println("Size of dataframeInCons:" + dataFramesInCons.head.count())
      val result: DataFrame = RunSimilarityJoin(dataFramesInCons.head, dataFramesInCons.head, columnCons.apply(0), columnCons.apply(1), c.threshold, distanceMeasure, "simjoin")
      //println("Result size:" + result.count())
      result.rdd.localCheckpoint()
      return result
    }else if(dataFramesInCons.size == 1) { //self similarity
      println("Size of dataframeInCons:" + dataFramesInCons.head.count())
      val result: DataFrame = RunSimilarityJoin(dataFramesInCons.head, dataFramesInCons.head, columnCons.apply(0), columnCons.apply(1), c.threshold, distanceMeasure, "self")
      //println("Result size:" + result.count())
      result.rdd.localCheckpoint()
      return result
    }
    gcoreRunner.sparkSession.emptyDataFrame
  }


  def SimJoinHandler(constraints: ArrayBuffer[Constraint], queryResults: Array[DataFrame]) : Seq[DataFrame] ={
    val columnsCons = constraints.map(c => c.var1+"$"+c.attr1) ++ constraints.map(c => c.var2+"$"+c.attr2)
    queryResults.foreach(r => r.show(10))
    val subsetConstraints : Seq[DataFrame] = queryResults.filter(x => x.columns.intersect(columnsCons).size > 0)
    val notUsedDfs: Seq[DataFrame] = queryResults.diff(subsetConstraints)    //mantr lista para verificar quais dataframes foram joined e quais nao foram
    val dfResults = new ArrayBuffer[DataFrame]()
    for(c <- constraints){
      val columnCons = Seq(c.var1+"$"+c.attr1, c.var2+"$"+c.attr2)
      val dataFramesInCons : Seq[DataFrame] = queryResults.filter(x => x.columns.intersect(columnsCons).size > 0)
      var distanceMeasure = "jaccardsimilarity"
      if(c.distance == "edit") distanceMeasure = "editsimilarity"
      if(dataFramesInCons.size == 2){
        var result: DataFrame = gcoreRunner.sparkSession.emptyDataFrame
        if(dataFramesInCons.head.columns.contains(columnCons.head)){
          result= RunSimilarityJoin(dataFramesInCons(0), dataFramesInCons(1), columnCons(0), columnCons(1),c.threshold, distanceMeasure, "simjoin")
          result.count()
        }else{
          result = RunSimilarityJoin(dataFramesInCons.apply(1), dataFramesInCons.apply(0), columnCons.apply(1), columnCons.apply(0), c.threshold, distanceMeasure, "simjoin")
          result.count()
        }
        dfResults += result
      }else if(dataFramesInCons.size == 1){ //self similarity
        println("Size of dataframeInCons:" + dataFramesInCons.head.count())
        println("here!!! - self similarity")
        val result: DataFrame = RunSimilarityJoin(dataFramesInCons.head, dataFramesInCons.head, columnCons.apply(0), columnCons.apply(1),c.threshold, distanceMeasure, "self")
        println("Result size:" + result.count())
        result.rdd.localCheckpoint()
        dfResults += result
      }
    }
    dfResults ++ notUsedDfs //not used dfs addings stuff that should not be added
  }

  def RunSimilarityJoin(df1: DataFrame, df2: DataFrame, col1: String, col2: String, threshold: Double, distanceMeasure: String, type_join: String) : DataFrame = {
    val id1 = col1.split('$').apply(0).concat("$id")
    val id2 = col2.split('$').apply(0).concat("$id")
    val df1_1 = if(df1.schema(col1).dataType.typeName == "string") {
      df1.select(id1, col1).filter(col(col1).isNotNull)
        .filter(col(col1).notEqual("null"))
        .dropDuplicates()
        .withColumn("low"+col1, lower(col(col1)))
        .drop(col1)
        .withColumnRenamed("low"+col1, col1)
    }else {
      df1.select(id1, col1)
    }
    val df2_2 = if(df2.schema(col2).dataType.typeName == "string") {
      df2.select(id2, col2).filter(col(col2).isNotNull)
        .filter(col(col2).notEqual("null"))
        .dropDuplicates()
        .withColumn("low"+col2, lower(col(col2)))
        .drop(col2)
        .withColumnRenamed("low"+col2, col2)
    }else {
      df2.select(id2, col2)
    }
    println(df1_1.count())
    println("df2 size" + df2_2.count())
    df1_1.show(10)
    df2_2.show(10)
    val simjoiAPI = new SimilarityAPI(gcoreRunner)
    var result: DataFrame = gcoreRunner.sparkSession.emptyDataFrame
    if(threshold == 0){
      result = df1_1.join(df2_2, df1_1.col(col1) === df2_2.col(col2), "inner")
      println(result.count())
    }
    else if(distanceMeasure == "jaccardsimilarity"){
      var d1RDD = gcoreRunner.sparkSession.sparkContext.emptyRDD[(String, String)]
      var d2RDD = gcoreRunner.sparkSession.sparkContext.emptyRDD[(String, String)]
      try{
        df1_1.sample(0.1).map(x => (x.getAs[String](id1), x.getAs[String](col1))).show(20)
        d1RDD = df1_1.map(x => (x.getAs[String](id1), x.getAs[String](col1)))
          .filter(x => (x._2 != null)).filter(x => !x._2.isEmpty).rdd
        d1RDD.take(3).foreach(x => println(x._1.length))
      }catch {
        case (e: Exception) => {
          d1RDD = df1_1.map(x => (x.getAs[Long](id1).toString, x.getAs[String](col1))).filter(x => (x._2 != null)).filter(x => !x._2.isEmpty).rdd
          println("SHOW!!!!")
          df1_1.map(x => (x.getAs[Long](id1).toString, x.getAs[String](col1))).filter(x => (x._2 != null || x._2 != "")).show(100)
        }
      }
      try{
        df2_2.sample(0.1).map(x => (x.getAs[String](id2), x.getAs[String](col2))).show(20)
        d2RDD = df2_2.map(x => (x.getAs[String](id2), x.getAs[String](col2))) .filter(x => (x._2 != null)).filter(x => !x._2.isEmpty).rdd
        d2RDD.take(3).foreach(x => println(x._1.length))
      }catch {
        case (e: Exception) => {
          d2RDD = df2_2.map(x => (x.getAs[Long](id2).toString, x.getAs[String](col2))) .filter(x => (x._2 != null)).filter(x => !x._2.isEmpty).rdd
        }
      }
      val vernica = new VernicaJoinAthena(threshold, gcoreRunner.sparkSession)
      result = vernica.vernicaJoin(d1RDD, d2RDD, id1, id2).dropDuplicates(id1, id2)
    }else if(distanceMeasure == "editsimilarity"){
      result = simjoiAPI.SimJoin(df1_1, df2_2, col1, col2, distanceMeasure, threshold, "<")
    }
    if(type_join == "self"){
      result.join(df1, result.col(id1)===df1.col(id1) and result.col(id2)===df1.col(id2))
    }else
      result.join(df1.drop("sid"), id1).join(df2.drop("sid"),id2)
  }


}

