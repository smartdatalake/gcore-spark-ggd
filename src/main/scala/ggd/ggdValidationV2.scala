package ggd

import java.io.PrintWriter
import java.sql.Connection

import SimSQL.SimilarityAPI
import ggd.utils.{DataFrameUtils, GGDtoGCoreParser, VernicaJoinAthena, selectMatch}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.col
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

  //validation algorithm without the similarity join operator. Constraints are validated by using row by row comparison
  def ValidationV2(ggd: GraphGenDep) : Violated = {
    val dist: distanceFunctions = new distanceFunctions;
    val gcoreSelectMatch : selectMatch = GGDtoGCoreParser.parseGCoreSelectMatch(ggd.sourceGP)
    val gcoreQuery: String = "SELECT " + gcoreSelectMatch.selectClause + " MATCH " + gcoreSelectMatch.matchClause
    println("GCore Query" + gcoreQuery)
    var originalResult = gcoreRunner.compiler.compilePropertyGraph(gcoreQuery).asInstanceOf[DataFrame] //satisfiable source patterns
    println("Size of Result of source query:" + originalResult.count())
    println(ggd.prettyPrint())
    val result = originalResult.filter(ConstraintFilter(ggd.sourceCons))//.cache()
    println("Satisfied source patterns::" + result.count())
    sourceSize += SelectedSource(result.count(), ggd.name)
    if(result.isEmpty){
      return new Violated(gcoreRunner.sparkSession.emptyDataFrame, ggd) //if result is empty non ggd should be violated
    }
    //target graph pattern
    //val targetQuery: String = GGDtoGCoreParser.parseGCore(ggd.targetGP) - change target to only does not have in source (same as graph generation)
    val sourceVariables: List[String] = ggd.sourceGP.map(x => x.parseToGCoreSelect()).flatMap(_.variables)
    val targetVariables: List[String] = ggd.targetGP.head.vertices.map(_.variable) ++ ggd.targetGP.head.edges.map(_.variable)
    val commonVarPattern: List[String] = GGDtoGCoreParser.commonVariables(sourceVariables, targetVariables)
    val matchOptionalClause = GGDtoGCoreParser.partialPattern(ggd.targetGP.head, commonVarPattern)
    val targetQuery: String = "SELECT * MATCH " + matchOptionalClause.optionalClause
    var targetResult: DataFrame = gcoreRunner.sparkSession.emptyDataFrame
    if(targetQuery == ""){ //case for empty target -> only source variables in the target constraint
      var filtered = result.filter(ConstraintFilter(ggd.targetCons)) //source rows that satisfies target constraints
     // val diff = result.except(filtered) //retira as linhas do dataframe que satisfazem o target constraints (sobra apenas as rows que não satisfazem)
      return new Violated(result.except(filtered), ggd)
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
    val commonVar: List[String] = result.columns.map(_.split('$').apply(0)).intersect(targetResult.columns.map(_.split('$').apply(0))).distinct.toList
    if(!commonVar.isEmpty){
      val commonColumns: List[String] = commonVar.map( x => x + "$id")
      val commonColumnsAll: List[String] = result.columns.intersect(targetResult.columns).distinct.toList
      //joins spark scala
      val joinSourceTarget = result.as("s").join(targetResult.as("t"), commonColumns, "inner")//.dropDuplicates(commonColumnsAll)
      val filteredSourceTarget = joinSourceTarget.filter(TargetConstraintFilter(ggd.targetCons, ggd.sourceGP))//.cache() //has all the data that validates both source and target
      val variables = ggd.sourceGP.map(_.vertices.map(_.variable)).flatten ++ ggd.sourceGP.map(_.edges.map(_.variable)).flatten
      val sourceColumnsId = variables.map(x =>  x + "$id")
      val filteredSource = filteredSourceTarget.select(sourceColumnsId.head, sourceColumnsId.tail:_*)//.dropDuplicates(sourceColumnsId.head, sourceColumnsId.tail:_*)
      val violatedSelect = result.select(sourceColumnsId.head, sourceColumnsId.tail:_*)//.except(filteredSource)
      violatedSelect.show(10)
      val violatedIds = violatedSelect.except(filteredSource)
      if(violatedIds.isEmpty)return Violated(gcoreRunner.sparkSession.emptyDataFrame, ggd)
      val violatedSource = result.join(violatedIds, sourceColumnsId, "inner")
      Violated(violatedSource, ggd)
    }else {
      if(targetResult.filter(ConstraintFilter(ggd.targetCons)).isEmpty){
        Violated(result, ggd)
      }else{
        Violated(gcoreRunner.sparkSession.emptyDataFrame, ggd)
      }
    }
  }

  //graph generation function
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
    //val save = new SaveGraph()
    //save.saveJsonGraph(unionGraph, "/media/larissacsh/Data/SDLdatasets/keywordsResults2/")
    //union with previous graph
    val genInfoJson =  Serialization.write(genInfo)
    new PrintWriter("/media/larissacsh/Data/SDLdatasets/keywordsResults2info.json") { write(genInfoJson); close }
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
      //gcoreRunner.catalog.unregisterGraph(catalogGraph)

      val resultGeneratedGraph = new SparkGraph {
        override var graphName: String =  targetGraph//+"UnionedGraph"
        //seq of seqs
        override def vertexData: Seq[Table[DataFrame]] = vData

        override def edgeData: Seq[Table[DataFrame]] = eData
        override def pathData: Seq[Table[DataFrame]] = pData
        override def edgeRestrictions: LabelRestrictionMap = graph.edgeRestrictions.union(catalogGraph.edgeRestrictions)

        override def storedPathRestrictions: LabelRestrictionMap = graph.storedPathRestrictions.union(catalogGraph.storedPathRestrictions)

        /*override def vertexSchema: EntitySchema = generatedGraphs.reduce((a,b) => a.vertexSchema.union(b.vertexSchema))

        override def pathSchema: EntitySchema = generatedGraphs.reduce((a,b) => a.pathSchema.union(b.pathSchema))

        override def edgeSchema: EntitySchema = generatedGraphs.reduce((a,b) => a.edgeSchema.union(b.edgeSchema))*/
      }
      resultGeneratedGraph
    }

    def unionData(buffer: Seq[Table[SparkGraph#StorageType]]) : Seq[Table[DataFrame]] = {
    val seqTable: ArrayBuffer[Table[DataFrame]] = new ArrayBuffer[Table[DataFrame]]()
    for(a<-buffer){
      for(b<-buffer){
        if(a.name == b.name && !seqTable.map(_.name).contains(a.name) && a.graphName != b.graphName){
          seqTable += Table(a.name, a.data.join(b.data, a.data.columns, "fullOuter"))
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
    //println(" Query:::" + query)
    val targetQuery: DataFrame = gcoreRunner.compiler.compilePropertyGraph(query).asInstanceOf[DataFrame]
    val commonVar = GGDtoGCoreParser.commonVariablesGP(violated.ggd.sourceGP, violated.ggd.targetGP)
    val columnsToSelect = GGDtoGCoreParser.commonVariablesGP(violated.ggd.sourceGP, violated.ggd.targetGP).map( a => a + "$id")
    //join to check only what was generated
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
      //val graphPatterns = gcoreSelectMatch.matchClause.split(",").map(m => "SELECT * MATCH " + m) //array of match clauses
      queryResults = graphPatterns.map(query => gcoreRunner.compiler.compilerProteus(query, con).asInstanceOf[DataFrame].persist(StorageLevel.MEMORY_AND_DISK_SER))
      result = filteredResults(ggd, queryResults, ggd.sourceCons)
    }else{
      val query = "SELECT * MATCH " + gcoreSelectMatch.matchClause
      queryResults = Array(gcoreRunner.compiler.compilerProteus(query, con).asInstanceOf[DataFrame])
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
      //targetResult = filteredResultsTarget(selectMatchTarget, ggd)
      targetResult =  gcoreRunner.compiler.compilerProteus(targetQuery, con).asInstanceOf[DataFrame]//.cache()
      //targetResult = gcoreRunner.compiler.compilePropertyGraph(targetQuery).asInstanceOf[DataFrame]//.cache()
    }catch {
      case nonExistingLabel : algebra.exceptions.DisjunctLabelsException => {
        return new Violated(result, ggd)
      }
      case e: Exception => {
        e.printStackTrace()
        return new Violated(result, ggd)
      }
    }
    //1 filter rows which has equal id of the common variables in the graph pattern
    if(targetResult.isEmpty) return Violated(result, ggd)
    targetResult.show(10)
    //get common variables for constraint checking + getting all the data needed for the ggds checking in one df
    val commonVar: List[String] = result.columns.map(_.split('$').apply(0)).intersect(targetResult.columns.map(_.split('$').apply(0))).distinct.toList
    if(!commonVar.isEmpty){
      //val commonColumns: List[String] = commonVar.map( x => x + "$id").diff(Seq("sid$id"))
      //val commonColumnsAll: List[String] = result.columns.intersect(targetResult.columns).distinct.toList
      val filteredSourceTarget = DataFrameUtils.removeDuplicateColumns(filteredResults(ggd, Array(targetResult, result), ggd.targetCons))
      //val filteredSourceTarget = filteredTargetResults(ggd, targetResult, result, ggd.targetCons)
      val variables = ggd.sourceGP.map(_.vertices.map(_.variable)).flatten ++ ggd.sourceGP.map(_.edges.map(_.variable)).flatten
      val sourceColumnsId = variables.map(x =>  x + "$id")
      val filteredSource = filteredSourceTarget.select(sourceColumnsId.head, sourceColumnsId.tail:_*)//.dropDuplicates(sourceColumnsId.head, sourceColumnsId.tail:_*)
      val violatedSelect = result.select(sourceColumnsId.head, sourceColumnsId.tail:_*)//.except(filteredSource)
      //violatedSelect.show(10)
      //anti join for checking ids
      val violatedIds = violatedSelect.except(filteredSource)
      if(violatedIds.isEmpty)return Violated(gcoreRunner.sparkSession.emptyDataFrame, ggd)
      val violatedSource = result.join(violatedIds, sourceColumnsId, "inner")
      Violated(violatedSource, ggd)
    }else {
      val targetNoCommon = filteredResults(ggd, Array(targetResult), ggd.targetCons)
      //targetResult.filter(ConstraintFilter(ggd.targetCons))
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
    //source graph pattern
    val dist: distanceFunctions = new distanceFunctions;
    val gcoreSelectMatch : selectMatch = GGDtoGCoreParser.parseGCoreSelectMatch(ggd.sourceGP)
    var queryResults = Array.empty[DataFrame]
    var result = gcoreRunner.sparkSession.emptyDataFrame
    if(ggd.sourceGP.size > 1){
      val queries = ggd.sourceGP.map(x => GGDtoGCoreParser.parseGCoreSelectMatch(List(x)))
      val graphPatterns = queries.map(m => "SELECT * MATCH " + m.matchClause).toArray
      //val graphPatterns = gcoreSelectMatch.matchClause.split(",").map(m => "SELECT * MATCH " + m) //array of match clauses
      queryResults = graphPatterns.map(query => gcoreRunner.compiler.compilePropertyGraph(query).asInstanceOf[DataFrame].persist(StorageLevel.MEMORY_AND_DISK_SER)) //sequence of dataframes with graph patterns results
      result = filteredResults(ggd, queryResults, ggd.sourceCons)
    }else{
      val query = "SELECT * MATCH " + gcoreSelectMatch.matchClause
      queryResults = Array(gcoreRunner.compiler.compilePropertyGraph(query).asInstanceOf[DataFrame])
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
      //targetResult = filteredResultsTarget(selectMatchTarget, ggd)
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
    //1 filter rows which has equal id of the common variables in the graph pattern
    if(targetResult.isEmpty) return Violated(result, ggd)
    targetResult.show(10)
    //get common variables for constraint checking + getting all the data needed for the ggds checking in one df
    val commonVar: List[String] = result.columns.map(_.split('$').apply(0)).intersect(targetResult.columns.map(_.split('$').apply(0))).distinct.toList
    if(!commonVar.isEmpty){
      //val commonColumns: List[String] = commonVar.map( x => x + "$id").diff(Seq("sid$id"))
      //val commonColumnsAll: List[String] = result.columns.intersect(targetResult.columns).distinct.toList
      val filteredSourceTarget = DataFrameUtils.removeDuplicateColumns(filteredResults(ggd, Array(targetResult, result), ggd.targetCons))
      //val filteredSourceTarget = filteredTargetResults(ggd, targetResult, result, ggd.targetCons)
      val variables = ggd.sourceGP.map(_.vertices.map(_.variable)).flatten ++ ggd.sourceGP.map(_.edges.map(_.variable)).flatten
      val sourceColumnsId = variables.map(x =>  x + "$id")
      val filteredSource = filteredSourceTarget.select(sourceColumnsId.head, sourceColumnsId.tail:_*)//.dropDuplicates(sourceColumnsId.head, sourceColumnsId.tail:_*)
      val violatedSelect = result.select(sourceColumnsId.head, sourceColumnsId.tail:_*)//.except(filteredSource)
      //violatedSelect.show(10)
      //anti join for checking ids
      val violatedIds = violatedSelect.except(filteredSource)
      if(violatedIds.isEmpty)return Violated(gcoreRunner.sparkSession.emptyDataFrame, ggd)
      val violatedSource = result.join(violatedIds, sourceColumnsId, "inner")
      Violated(violatedSource, ggd)
    }else {
      val targetNoCommon = filteredResults(ggd, Array(targetResult), ggd.targetCons)
        //targetResult.filter(ConstraintFilter(ggd.targetCons))
      if(targetNoCommon.isEmpty){
        Violated(result, ggd)
      }else{
        Violated(gcoreRunner.sparkSession.emptyDataFrame, ggd)
      }
    }
  }

  //New contraint filter function -> calls similarity joins and constraints filter
  def filteredResults(ggd: GraphGenDep, queryResults: Array[DataFrame], ggdCons: List[Constraint]) : DataFrame = {
    //println("Size of Result of source query:" + queryResults.map(d => d.count()).mkString(","))//.reduce((a,b) => a+b))
    //println(ggd.prettyPrint())
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
        /*if(cons.distance == "euclidean"){
          constraintsForSimJoin += cons
        }*///euclidean not working as physical operator yet
      }
      result = SimJoinHandler(constraintsForSimJoin, queryResults)
    }
    val originalResult = result.reduce((a,b) =>{
      val variablesA = a.columns.map(col => col.split('$').apply(0))
      val variablesB = b.columns.map(col => col.split('$').apply(0)).distinct
      val commonVariables = GGDtoGCoreParser.commonVariables(variablesA.toList, variablesB.toList).filter(p => p!="sid").map(t => {
        t+"$id"
      })//.diff(Seq("sid$id"))
      if(!commonVariables.isEmpty) a.join(b, commonVariables)
      else a.crossJoin(b)
    })
    val constraintsFilter = ggdCons.diff(constraintsForSimJoin)
    originalResult.filter(ConstraintFilter(constraintsFilter))//.cache()
  }

  def SimJoinHandler(constraints: ArrayBuffer[Constraint], queryResults: Array[DataFrame]) : Seq[DataFrame] ={
    val columnsCons = constraints.map(c => c.var1+"$"+c.attr1) ++ constraints.map(c => c.var2+"$"+c.attr2)
    queryResults.foreach(r => r.show(10))
    val subsetConstraints : Seq[DataFrame] = queryResults.filter(x => x.columns.intersect(columnsCons).size > 0)
    val notUsedDfs: Seq[DataFrame] = queryResults.diff(subsetConstraints)    //mantr lista para verificar quais dataframes foram joined e quais nao foram
    val dfResults = new ArrayBuffer[DataFrame]()
    for(c <- constraints){
      //verificar que colunas precisam nas constraints de cada dataframe
      //mandar similarity search
      val columnCons = Seq(c.var1+"$"+c.attr1, c.var2+"$"+c.attr2)
      val dataFramesInCons : Seq[DataFrame] = queryResults.filter(x => x.columns.intersect(columnsCons).size > 0)
      dataFramesInCons.foreach(r => r.show(10))
      //val dataFramesInCons : Seq[DataFrame] = dataFramesInCons_temp.map(df => df.filter())
      var distanceMeasure = "jaccardsimilarity"
      if(c.distance == "edit") distanceMeasure = "editsimilarity"
      if(dataFramesInCons.size == 2){
        var result: DataFrame = gcoreRunner.sparkSession.emptyDataFrame
        if(dataFramesInCons.head.columns.contains(columnCons.head)){
          result= RunSimilarityJoin(dataFramesInCons.apply(0), dataFramesInCons.apply(1), columnCons.apply(0), columnCons.apply(1),c.threshold, distanceMeasure, "simjoin")
          result.show(10)
          /*result = dataFramesInCons.apply(0).SimJoin(dataFramesInCons.apply(1), dataFramesInCons.apply(0).col(columnCons.apply(0)),
            dataFramesInCons.apply(1).col(columnCons.apply(1)), distanceMeasure, c.threshold, c.operator).dropDuplicates()*/
        }else{
          result = RunSimilarityJoin(dataFramesInCons.apply(1), dataFramesInCons.apply(0), columnCons.apply(1), columnCons.apply(0), c.threshold, distanceMeasure, "simjoin")
          /*result = dataFramesInCons.apply(1).filter(col(columnCons.apply(0)).isNotNull).SimJoin(dataFramesInCons.apply(0).filter(col(columnCons.apply(1)).isNotNull), dataFramesInCons.apply(0).col(columnCons.apply(1)),
            dataFramesInCons.apply(1).col(columnCons.apply(0)), distanceMeasure, c.threshold, c.operator).dropDuplicates()*/
        //  println("Result size else:" + result.count())
        }
        dfResults += result
      }else if(dataFramesInCons.size == 1){ //self similarity
        println("Size of dataframeInCons:" + dataFramesInCons.head.count())
        println("here!!! - self similarity")
        val result: DataFrame = RunSimilarityJoin(dataFramesInCons.head, dataFramesInCons.head, columnCons.apply(0), columnCons.apply(1),c.threshold, distanceMeasure, "self")
        result.show(10)
        //println("Result size:" + result.count())
        dfResults += result
      }
    }
    dfResults ++ notUsedDfs //not used dfs addings stuff that should not be added
  }

  def RunSimilarityJoin(df1: DataFrame, df2: DataFrame, col1: String, col2: String, threshold: Double, distanceMeasure: String, type_join: String) : DataFrame = {
    val id1 = col1.split('$').apply(0).concat("$id")
    val id2 = col2.split('$').apply(0).concat("$id")
    val df1_1 = df1.select(id1, col1).filter(col(col1).isNotNull).dropDuplicates()
    val df2_2 = df2.select(id2, col2).filter(col(col2).isNotNull).dropDuplicates()
    val simjoiAPI = new SimilarityAPI(gcoreRunner)
    var result: DataFrame = gcoreRunner.sparkSession.emptyDataFrame
    if(distanceMeasure == "jaccardsimilarity"){
      if(df1_1.count() < 10000){
        //result = df1_1.SimJoin(df2_2, df1_1(col1), df2_2(col2), distanceMeasure, threshold, "<").dropDuplicates()//.cache()
        result = simjoiAPI.SimJoin(df1, df2, col1, col2, distanceMeasure, threshold, "<")
      }else{
        val vernica = new VernicaJoinAthena(threshold, gcoreRunner.sparkSession)
        result = vernica.vernicaJoin(df1_1.map(x => (x.getAs(id1).toString, x.getAs(col1).toString)).rdd,
          df2_2.map(x => (x.getAs(id2).toString, x.getAs(col2).toString)).rdd)
      }
    }else if(distanceMeasure == "editsimilarity"){
      //result = df1_1.SimJoin(df2_2, df1_1(col1), df2_2(col2), distanceMeasure, threshold, "<").dropDuplicates()//.cache()
      result = simjoiAPI.SimJoin(df1, df2, col1, col2, distanceMeasure, threshold, "<")
    }
    //val vernica = new VernicaJoinAthena(threshold, gcoreRunner.sparkSession)
    //val result = vernica.vernicaJoin(df1_1.map(x => (x.getAs(id1).toString, x.getAs(col1).toString)).rdd,
    //  df2_2.map(x => (x.getAs(id2).toString, x.getAs(col2).toString)).rdd)
    //val result : DataFrame = df1_1.SimJoin(df2_2, df1_1(col1), df2_2(col2), distanceMeasure, threshold, "<").dropDuplicates().cache()
    //val result = new MinHashSimJoin(df1_1, df2_2, col1, col2, threshold).sparkHash(10).dropDuplicates()
    if(type_join == "self"){
      result.join(df1, result.col(id1)===df1.col(id1) and result.col(id2)===df1.col(id2))
    }else
    result.join(df1, id1).join(df2,id2) //fazer o join com as duas condicoes ao mesmo tempo
  }

  
}

