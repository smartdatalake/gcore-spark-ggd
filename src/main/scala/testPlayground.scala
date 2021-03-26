import java.io.File
import java.nio.file.Paths

import SimSQL.SimilarityAPI
import SimSQL.logicalPlan.{ResolveSimJoin, SimJoinOptimizer}
import ggd.Runner
//import Joins.VernicaJoinAthena
import compiler.{CompileContext, Compiler, GcoreCompiler}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.catalyst.parser.SimCatalystSqlParser
import org.apache.spark.sql.{DataFrame, SparkSession, SparkSessionExtensions}
import schema.EntitySchema.LabelRestrictionMap
import schema.{PathPropertyGraph, Table}
import spark.{GraphSource, SparkCatalog, SparkGraph}
import SimSQL.physicalPlan.SimJoinSelection
import org.apache.spark.sql.functions._
//import org.apache.spark.sql.catalyst.optimizer.SimJoinOptimizer

import scala.collection.mutable.ArrayBuffer

object testPlayground {

  /*type ExtensionsBuilder = SparkSessionExtensions => Unit

  def create(builder: ExtensionsBuilder): ExtensionsBuilder = builder

  val extension = create { extensions =>
    extensions.injectParser((_, _) => SimCatalystSqlParser)
    extensions.injectOptimizerRule(sessionExtensions => SimJoinOptimizer)
    extensions.injectResolutionRule(sessionExtensions => ResolveSimJoin)
    extensions.injectPlannerStrategy(sessionExtension => SimJoinSelection)
  }

  def newRunner: GcoreRunner = {
    val sparkSession: SparkSession = SparkSession
      .builder()
      .appName("G-CORE Runner")
      .master("local[8]")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("spark.sql.shuffle.partitions", "24")
      .config("spark.sql.cbo.enabled", true)
      .config("spark.local.dir", "/media/larissacsh/Data/SDLDatasets/")
      .withExtensions(extension)
      .getOrCreate()
    val catalog: SparkCatalog = SparkCatalog(sparkSession)
    val compiler: Compiler = GcoreCompiler(CompileContext(catalog, sparkSession))
    //sparkSession.experimental.extraStrategies
    //sparkSession.experimental.extraOptimizations
    GcoreRunner(sparkSession, compiler, catalog)
  }*/

  def sameRefsIds(frame1: DataFrame, frame2: DataFrame): Array[String] = {
    val cols1 = frame1.columns.map(_.split('$').apply(0)).distinct
    val cols2 = frame2.columns.map(_.split('$').apply(0)).distinct
    cols1.intersect(cols2).map(_ + "$id").filter(! _.contains("sid$id"))
  }

  def getTokens(str: String): Array[Double] = {
    val strArray = str.toLowerCase.split(" ")
    //strArray.map(r => index.get(r).get.toDouble)
    strArray.map(r => r.hashCode.toDouble)
  }

  def loadGraphJson(configPath: String, gcoreRunner: GcoreRunner) : Unit = {
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

  def unionGraph(graph1: SparkGraph, graph2: SparkGraph) : PathPropertyGraph = {
    val vData: Seq[Table[DataFrame]] = unionData(graph1.vertexData ++ graph2.vertexData)
    val eData: Seq[Table[DataFrame]] = unionData(graph1.edgeData ++ graph2.edgeData)
    val pData: Seq[Table[DataFrame]] = unionData(graph1.pathData ++ graph2.pathData)
    //gcoreRunner.catalog.unregisterGraph(catalogGraph)

    val resultGeneratedGraph = new SparkGraph {
      override var graphName: String =  "DBLPCordis"
      //seq of seqs
      override def vertexData: Seq[Table[DataFrame]] = vData

      override def edgeData: Seq[Table[DataFrame]] = eData
      override def pathData: Seq[Table[DataFrame]] = pData
      override def edgeRestrictions: LabelRestrictionMap = graph1.edgeRestrictions.union(graph2.edgeRestrictions)

      override def storedPathRestrictions: LabelRestrictionMap = graph1.storedPathRestrictions.union(graph2.storedPathRestrictions)

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


  case class Params(inputFile: String = null, threshold: Double = 0.1)

  def jaccard_sim_udf(s1: String, s2: String): Double = {
    val overlap = (s1.split(" ").intersect(s2.split(" "))).toSet.size
    val union = (s1.split(" ").union(s2.split(" "))).toSet.size
    overlap.toDouble/union.toDouble
  }

  def main(args: Array[String]): Unit = {

    Logger.getLogger("org").setLevel(Level.OFF)
    Logger.getLogger("akka").setLevel(Level.OFF)

    val gcoreRunner = Runner.newRunner

    val table1 = gcoreRunner.sparkSession.read.option("header","true")
      .option("inferschema", true)
      .csv("/benchmarkDatasets/AmazonGoogle/GoogleProducts.csv")
      //.filter(col("description").isNull)
    val table2 = gcoreRunner.sparkSession.read.option("header","true")
      .option("inferschema", true)
      .csv("/benchmarkDatasets/AmazonGoogle/Amazon.csv")
      //.filter(col("description").isNull)

    table1.show(10)
    table2.show(10)

    val simjoiApi = new SimilarityAPI(gcoreRunner)

    //table1.createOrReplaceTempView("table1")
    //table2.createOrReplaceTempView("table2")

    //Similarity Joins filter null values before executing the similarity join algorithm, it desconsiders these rows in the final result

    //val queryApi = gcoreRunner.sparkSession.sql("SELECT * FROM table1 SIMILARITY JOIN table2 USING EDITSIMILARITY(table1.name, table2.title) < 3")
    //val queryApi = gcoreRunner.sparkSession.sql("SELECT * FROM table1 SIMILARITY JOIN table2 USING JACCARDSIMILARITY(table1.description, table2.description) < 0.8")
    val queryApi = simjoiApi.SimJoin(table1, table2, "name", "title", "editsimilarity", 3, "<")

    queryApi.explain(true)

    println("size:" + queryApi.count())
    queryApi.show(30)



  }



}


