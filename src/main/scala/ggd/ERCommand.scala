package ggd

import java.io.File
import java.nio.file.Paths

import org.apache.spark.sql.DataFrame
import schema.PathPropertyGraph
import spark._
import ggd._
import ggd.utils._

import scala.collection.mutable.ArrayBuffer


class ERCommand(gcoreRunner: GcoreRunner){
  var ggds = new GGDSet()
  val ggdValV2 : ggdValidationV2 = new ggdValidationV2(gcoreRunner)
  var resultInfo: ArrayBuffer[generatedInfo] = null
  val loadP = new loadProteus(gcoreRunner)

  def getGcore(): GcoreRunner = {
    return gcoreRunner
  }

  def readGGDs(path: String): Unit = {
    println("Read GGDs!!")
    ggds.loadGGDs(path)
  } //- implement method to read form ggd json not from file

  def setGGDs(set: Seq[GraphGenDep]): Unit = {
    ggds.AllGGDs = set
  }

  def runSelectQuery(query: String): DataFrame = {
    val df = gcoreRunner.compiler.compilePropertyGraph(query).asInstanceOf[DataFrame]
    return df
  }

  def runSelectQueryProteus(query: String): DataFrame = {
    val df = gcoreRunner.compiler.compilerProteus(query, loadP.con).asInstanceOf[DataFrame]
    df
  }

  def runConstructQuery(query: String): PathPropertyGraph = {
    val graph = gcoreRunner.compiler.compilePropertyGraph(query).asInstanceOf[PathPropertyGraph]
    return graph
  }

  def runConstructQueryProteus(query: String) : PathPropertyGraph = {
    val graph = gcoreRunner.compiler.compilerProteus(query, loadP.con).asInstanceOf[PathPropertyGraph]
    return graph
  }

  def openConnectionProteus(): Unit = {
    loadP.openConnection()
  }

  def closeConnectionProteus(): Unit = {
    loadP.closeConnection()
  }

  def loadGraphProteus(configPath: String): Unit = {
    loadP.loadSparkJDBC(configPath)
  }

  def graphSchemaList(names : List[String]): List[String] = {
    names.map(n => gcoreRunner.catalog.graph(n).schemaString) //return list of schema strings
  }

  def graphSchema(name: String) : String = {
    gcoreRunner.catalog.graph(name).schemaString
  }

  def graphSchemaJson(name: String) : GraphJsonConfig = {
    val save = new SaveGraph()
    val jsonConfig = save.getJsonSchema(gcoreRunner.catalog.graph(name))
    jsonConfig
    //gcoreRunner.catalog.graph(name).schemaString
  }

  def availableGraphs() : List[String] = {
    gcoreRunner.catalog.allGraphsKeys.toList
  }

  def Validation() : Seq[GraphGenDep] = {
    val violatedGGDs = new ArrayBuffer[GraphGenDep]()
    for(ggd <- ggds.AllGGDs){
      val violated = ggdValV2.ValidationV3(ggd)
      if(violated.data.isEmpty) violatedGGDs += violated.ggd
    }
    violatedGGDs
  }

  def loadGraph(configPath: String) : Unit = {
    var sparkCatalog : SparkCatalog = SparkCatalog(gcoreRunner.sparkSession)
    val graphSource = new GraphSource(gcoreRunner.sparkSession) {
      override val loadDataFn: String => DataFrame = _ => gcoreRunner.sparkSession.emptyDataFrame
    }
    val cat: SparkCatalog = new SparkCatalog(gcoreRunner.sparkSession)
    cat.registerGraph(graphSource,Paths.get(configPath+File.separator+"config.json"))
    //graphName --> configPath
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
        //val violated: ViolatedV2 = ggdValV2.ValidationV2(ggd)
        val violated: ViolatedV2 = ggdValV2.ValidationV3(ggd)
        //println("Number of violated matches:" + violated.data.count())
        //val violatedTest = ViolatedV2(violated.data.limit(1), ggd)
        if (!violated.data.isEmpty) {
          changesInGraph = true
          println("Violated GGD - graph generation to validate it")
          generatedGraphGGD = ggdValV2.graphGenerationV3(violated)
          println(generatedGraphGGD.schemaString)
          //val saveGraph = SaveGraph()
          //saveGraph.saveJsonGraph(generatedGraphGGD, gcoreRunner.catalog.databaseDirectory)
        }//else changesInGraph = false
      }
    }
    resultInfo = ggdValV2.genInfo
    gcoreRunner.catalog.registerGraph(generatedGraphGGD)
    return generatedGraphGGD
  }


}