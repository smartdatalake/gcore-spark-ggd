package ggd

import java.io.File
import java.nio.file.Paths

import SimSQL.SimilarityAPI
import algebra.expressions.Label
import org.apache.spark.sql.DataFrame
import schema.{PathPropertyGraph, SchemaMap, Table}
import spark._
import ggd._
import ggd.utils._
import org.apache.spark.sql.functions.col
import schema.EntitySchema.LabelRestrictionMap

import scala.collection.mutable.ArrayBuffer


class ERCommand(gcoreRunner: GcoreRunner){
  var ggds = new GGDSet()
  val ggdValV2 : ggdValidationV2 = new ggdValidationV2(gcoreRunner)
  var resultInfo: ArrayBuffer[generatedInfo] = null
  val loadP = new loadJDBC(gcoreRunner)

  def getGcore(): GcoreRunner = {
    return gcoreRunner
  }

  def readGGDs(path: String): Unit = {
    println("Read GGDs!!")
    ggds.loadGGDs(path)
  }

  def setGGDs(set: Seq[GraphGenDep]): Unit = {
    ggds.AllGGDs = set
  }

  def runSelectQuery(query: String): DataFrame = {
    val df = gcoreRunner.compiler.compilePropertyGraph(query).asInstanceOf[DataFrame]
    return df
  }

  def runSelectQueryJDBC(query: String): DataFrame = {
    val df = gcoreRunner.compiler.compilerJDBC(query, loadP.con).asInstanceOf[DataFrame]
    df
  }

  def runConstructQuery(query: String): PathPropertyGraph = {
    val graph = gcoreRunner.compiler.compilePropertyGraph(query).asInstanceOf[PathPropertyGraph]
    return graph
  }

  def runConstructQueryJDBC(query: String) : PathPropertyGraph = {
    val graph = gcoreRunner.compiler.compilerJDBC(query, loadP.con).asInstanceOf[PathPropertyGraph]
    return graph
  }

  def openConnectionJDBC(url: String, user: String, pwd: String): Unit = {
    loadP.openConnection(url, user, pwd)
  }

  def closeConnectionJDBC(): Unit = {
    loadP.closeConnection()
  }

  def loadGraphJDBC(configPath: String): Unit = {
    loadP.loadSparkJDBC(configPath)
  }

  def loadDatabase(folder: String): Unit = {
    var directory = new Directory
    var load = directory.loadDatabase(folder, gcoreRunner.sparkSession, gcoreRunner.catalog)
    gcoreRunner.catalog.databaseDirectory = folder
    if (load)
      println("Current Database: "+folder)
    else
      println("The database entered was not loaded correctly")
  }

  def loadDatabaseJDBC(folder: String): Unit = {
    if(loadP.con == null){
      println("No connection available for JDBC")
      return
    }
    var directory = new Directory
    var load = directory.loadDatabaseJDBC(folder, gcoreRunner.sparkSession, gcoreRunner.catalog, loadP)
    gcoreRunner.catalog.databaseDirectory = folder
    if (load)
      println("Current Database: "+folder)
    else
      println("The database entered was not loaded correctly")
  }

  def graphSchemaList(names : List[String]): List[String] = {
    names.map(n => gcoreRunner.catalog.graph(n).schemaString)
  }

  def graphSchema(name: String) : String = {
    gcoreRunner.catalog.graph(name).schemaString
  }

  def graphSchemaJson(name: String) : GraphJsonConfig = {
    val save = new SaveGraph()
    val jsonConfig = save.getJsonSchema(gcoreRunner.catalog.graph(name))
    jsonConfig
  }

  def availableGraphs() : List[String] = {
    gcoreRunner.catalog.allGraphsKeys.toList
  }

  def dropGGDs(info: List[generatedInfoGraph]): Unit = {
    val graphs = info.map(_.graphName).distinct
    graphs.foreach(graph => {
      val sparkGraph = gcoreRunner.catalog.graph(graph)
      val infotoDrop = info.filter(_.graphName == graph)
      val tablesToDrop = infotoDrop.map(_.name)
      val newEdges = sparkGraph.edgeData.asInstanceOf[Seq[Table[DataFrame]]].filter(x => !tablesToDrop.contains(x.name.value))
      val newVertices = sparkGraph.vertexData.asInstanceOf[Seq[Table[DataFrame]]].filter(x => !tablesToDrop.contains(x.name.value))
      val newEdgeRestriction = sparkGraph.edgeRestrictions.map.filterKeys(x => !tablesToDrop.contains(x.value))
      val schema = new SchemaMap[Label, (Label,Label)](newEdgeRestriction)
      val newGraph = new SparkGraph {
        override var graphName: String = graph

        override def edgeRestrictions: LabelRestrictionMap = schema

        override def storedPathRestrictions: LabelRestrictionMap = SchemaMap.empty

        override def vertexData: Seq[Table[DataFrame]] = newVertices

        override def edgeData: Seq[Table[DataFrame]] = newEdges

        override def pathData: Seq[Table[DataFrame]] = Seq.empty
      }
      gcoreRunner.catalog.unregisterGraph(graph) //remove old graph
      gcoreRunner.catalog.registerGraph(newGraph) //insert the new graph with deleted edges/vertices
    })
    val newGGDs = ggds.AllGGDs.zipWithIndex.filterNot(x => info.map(_.ggd).contains(x._2))
    ggds.AllGGDs = newGGDs.map(_._1)
  }

  def createGraph(graphname: String, uniongraphs: List[String]) : Unit = {
    val sparkgraphs = uniongraphs.map(x => gcoreRunner.catalog.graph(x))
    val edgeRes = sparkgraphs.map(_.edgeRestrictions).reduce((a,b) => a.union(b))
    val targetGraph = new SparkGraph {
      override var graphName: String = graphname

      override def edgeRestrictions: LabelRestrictionMap = edgeRes

      override def storedPathRestrictions: LabelRestrictionMap = SchemaMap.empty

      override def vertexData: Seq[Table[DataFrame]] = sparkgraphs.map(_.vertexData.asInstanceOf[Seq[Table[DataFrame]]]).reduce((a,b) => a.union(b))

      override def edgeData: Seq[Table[DataFrame]] = sparkgraphs.map(_.edgeData.asInstanceOf[Seq[Table[DataFrame]]]).reduce((a,b) => a.union(b))

      override def pathData: Seq[Table[DataFrame]] = Seq.empty
    }
    gcoreRunner.catalog.registerGraph(targetGraph)
  }

  def Validation() : Seq[GraphGenDep] = {
    val violatedGGDs = new ArrayBuffer[GraphGenDep]()
    for(ggd <- ggds.AllGGDs){
      val violated = ggdValV2.ValidationV3(ggd)
      if(violated.data.isEmpty) violatedGGDs += violated.ggd
    }
    violatedGGDs
  }

  def ValidationReturn() : DataFrame = {
    val violatedGGDs = new ArrayBuffer[GraphGenDep]()
    val ggd = ggds.AllGGDs.head
    val violated = ggdValV2.ValidationV3(ggd)
    return violated.data
  }

  def ValidationJDBC(): Seq[GraphGenDep] = {
    val violatedGGDs = new ArrayBuffer[GraphGenDep]()
    for(ggd <- ggds.AllGGDs){
      val violated = ggdValV2.ValidationProteus(ggd, loadP.con)
      if(violated.data.isEmpty) violatedGGDs += violated.ggd
    }
    violatedGGDs
  }

  def runSimJoin(label1: String, label2: String, attr1: String, attr2:String, threshold: Double, graphname: String): DataFrame = {
    val graph = this.getGcore().catalog.graph(graphname)
    val df1 = if(graph.vertexSchema.labels.contains(Label(label1))){
      graph.vertexData.filter(_.name.value.equalsIgnoreCase(label1)).head.data.asInstanceOf[DataFrame].select(col("id").as("id1"), col(attr1))
    }else{
      graph.edgeData.filter(_.name.value.equalsIgnoreCase(label1)).head.data.asInstanceOf[DataFrame].select(col("id").as("id1"), col(attr1))
    }
    val df2 = if(graph.vertexSchema.labels.contains(Label(label2))){
      graph.vertexData.filter(_.name.value.equalsIgnoreCase(label2)).head.data.asInstanceOf[DataFrame].select(col("id").as("id2"), col(attr2))
    }else{
      graph.edgeData.filter(_.name.value.equalsIgnoreCase(label2)).head.data.asInstanceOf[DataFrame].select(col("id").as("id2"), col(attr2))
    }
    val simjoiAPI = new SimilarityAPI(gcoreRunner)
    var result: DataFrame = gcoreRunner.sparkSession.emptyDataFrame
    result = simjoiAPI.SimJoin(df1, df2, attr1, attr2, "editsimilarity", threshold, "<")
    result.show(20)
    result
  }

  def loadGraph(configPath: String) : Unit = {
    var sparkCatalog : SparkCatalog = SparkCatalog(gcoreRunner.sparkSession)
    val graphSource = new GraphSource(gcoreRunner.sparkSession) {
      override val loadDataFn: String => DataFrame = _ => gcoreRunner.sparkSession.emptyDataFrame
    }
    val cat: SparkCatalog = new SparkCatalog(gcoreRunner.sparkSession)
    cat.registerGraph(graphSource,Paths.get(configPath+File.separator+"config.json"))
    val graphName = configPath.split(File.separator).apply(configPath.split(File.separator).size-1)
    println("Graph name:" + graphName)
    gcoreRunner.catalog.registerGraph(cat.graph(graphName))
  }

  def loadGraphParquet(configPath: String): Unit = {
    val graph = ParquetGraphSource(gcoreRunner.sparkSession).loadGraph(Paths.get(configPath+File.separator+"config.json"))
    gcoreRunner.catalog.registerGraph(graph)
  }

  def graphGeneration() : PathPropertyGraph = {
    var generatedGraphGGD: PathPropertyGraph = PathPropertyGraph.empty
    var changesInGraph: Boolean = true
    //gcoreRunner.sparkSession.conf.set("spark.sql.crossJoin.enabled", true)
    gcoreRunner.catalog.setDefaultGraph(ggds.AllGGDs.head.targetGP.head.name)
    while(changesInGraph){
      changesInGraph = false
      for(ggd <- ggds.AllGGDs) {
        val violated: Violated = ggdValV2.ValidationV3(ggd)
        if (!violated.data.isEmpty) {
          changesInGraph = true
          println("Violated GGD - graph generation to validate it")
          generatedGraphGGD = ggdValV2.graphGenerationV3(violated)
          println(generatedGraphGGD.schemaString)
        }//else changesInGraph = false
      }
    }
    resultInfo = ggdValV2.genInfo
    gcoreRunner.catalog.registerGraph(generatedGraphGGD)
    return generatedGraphGGD
  }

  //inputs path to save data on jdbc
  def graphGenerationJDBC(path: String, raw_uri: String, raw_token: String, raw_save: String): PathPropertyGraph = {
    var generatedGraphGGD: PathPropertyGraph = PathPropertyGraph.empty
    var changesInGraph: Boolean = true
    //gcoreRunner.sparkSession.conf.set("spark.sql.crossJoin.enabled", true)
    gcoreRunner.catalog.setDefaultGraph(ggds.AllGGDs.head.targetGP.head.name)
    while(changesInGraph){
      changesInGraph = false
      for(ggd <- ggds.AllGGDs) {
        val violated = ggdValV2.ValidationProteus(ggd, loadP.con)
        if (!violated.data.isEmpty) {
          changesInGraph = true
          println("Violated GGD - graph generation to validate it")
          generatedGraphGGD = ggdValV2.graphGenerationProteus(violated, loadP.con, path, raw_uri, raw_token, raw_save)
          println(generatedGraphGGD.schemaString)
        }//else changesInGraph = false
      }
    }
    resultInfo = ggdValV2.genInfo
    gcoreRunner.catalog.registerGraph(generatedGraphGGD)
    generatedGraphGGD
  }

  def savePartOfGraph(saveType: String, path: String, graphPart: String, info: String, graphName: String, queryMode: String): Unit ={
    graphPart match {
      case "vertex" => {
        //save vertex
        val vertexData = gcoreRunner.catalog.graph(graphName).vertexData.asInstanceOf[Seq[Table[DataFrame]]]
          .filter(_.name.value == info).head.data
        saveFile(path, info, vertexData, saveType)
      }
      case "edge" => {
        val edgeData = gcoreRunner.catalog.graph(graphName).edgeData.asInstanceOf[Seq[Table[DataFrame]]]
          .filter(_.name.value == info).head.data
        saveFile(path, info, edgeData, saveType)
      }
      case "query" => {
        val queryData = if(queryMode == "jdbc"){
          runSelectQueryJDBC(info)
        }else runSelectQuery(info)
        saveFile(path, info, queryData, saveType)
      }
      case "config" => {
        //save config json as json file
        val save = new SaveGraph()
        save.saveConfigFileOnly(gcoreRunner.catalog.graph(graphName), path)
      }
      case _ => println("Mode of saving not supported, choose one of the three options: vertex, edge, query or config")
    }
  }

  def saveFile(path: String, labelName: String, data: DataFrame, saveType: String): Unit = {
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
      .saveAsTextFile(pathSave)
    //save as a correct json file
  }


}