import java.io.File
import java.nio.file.Paths

import SimSQL.SimilarityAPI
import ggd.utils.{EntityResolutionGroundTruth, GGDtoGCoreParser, Result}
import ggd.{ERCommand, GGDAllConfig, GraphPattern, Runner}
import org.apache.log4j.{Level, Logger}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import schema.PathPropertyGraph
import spark.SaveGraph
import org.apache.spark.sql.functions.col

import scala.collection.mutable.ArrayBuffer
import scala.io.Source

object sHINERrunner {
//sHINNER Command-line for testing and running automatically


  /***
   * @param query - spark or jdbc (for Proteus connection)
   * @param database - path to the folder which contains the graphs/contains the config files for loading the graph datasets
   * @param ggds - path to the GGDs (configuration file)
   * @param jdbcurl - jdbc url
   * @param username - jdbc username
   * @param pwd - jdbc password
   * @param verification - boolean value, true if there is a second input for matched ids and false if no verification is needed
   * @param savegraph - path for saving the resulting graph from graph generation
   * @param verificationpath - if verification is true then set a verification path to save the results, results will be saved according to the order it was declared in the ground truth file
   */
  case class Configuration(query: String, database: String, ggds: String, jdbcurl: String, username: String, pwd: String, verification: Boolean, savegraph: String,
                           verificationpath : String)

  /***
   * @param groundtruth - path to groundtruth csv in which the format is id1,id2 meaning id1 matches id2
   * @param sep - separator in the csv file
   * @param pattern - Graph Pattern that contains the matched entities, for example (Google)-[sameAs]->(Amazon)
   * @param id1 - name of the column to be used as id (same as groundtruth)
   * @param id2 - name of the column to be used as id (same as grountruth)
   *
   */
  case class GroundTruthComparison(groundtruth: String, sep: String, pattern: GraphPattern, id1: String, id2: String)


  def main(args: Array[String]): Unit = {

    Logger.getLogger("org").setLevel(Level.OFF)
    Logger.getLogger("akka").setLevel(Level.OFF)
    implicit val formats = DefaultFormats

    val gcoreRunner = Runner.newRunner
    val ercommand = new ERCommand(gcoreRunner)

    import gcoreRunner.sparkSession.implicits._

    if(args.size == 0){
      println("Error: No configuration file available!")
      gcoreRunner.sparkSession.stop()
      //return
    }

    //read configuration file
    val configFile = Source.fromFile(args(0)).mkString
    val json = parse(configFile)
    val configuration = json.camelizeKeys.extract[Configuration]
    var resultingGraph: PathPropertyGraph = null
    var startTime: Long = 0
    var endTime: Long = 0


    if(configuration.query.equals("spark")){
      ercommand.loadDatabase(configuration.database)
      println("Run Entity Resolution using the setted GGDs")
      ercommand.readGGDs(configuration.ggds)
      println("GGDs in path" + configuration.ggds + " were set!")
      println("Start running Validation and Graph Generation")
      startTime = System.nanoTime()
      resultingGraph = ercommand.graphGeneration()
      gcoreRunner.catalog.registerGraph(resultingGraph)
      endTime = System.nanoTime()
      println("Time in nanoseconds:" + (endTime - startTime).toString)
    }else if(configuration.query.equals("jdbc")){
      ercommand.openConnectionJDBC(configuration.jdbcurl, configuration.username, configuration.pwd)
      ercommand.loadDatabaseJDBC(configuration.database)
      println("Run Entity Resolution using the setted GGDs")
      ercommand.readGGDs(configuration.ggds)
      println("GGDs in path" + configuration.ggds + " were set!")
      println("Start running Validation and Graph Generation")
      startTime = System.nanoTime()
      resultingGraph = ercommand.graphGenerationJDBC()
      endTime = System.nanoTime()
      println("Time in nanoseconds:" + (endTime - startTime).toString)
    }

    //save resulting graph from the graph generation process
    if(configuration.savegraph != ""){
        if (!gcoreRunner.catalog.hasGraph(resultingGraph.graphName)) {
          println("Error: Graph not available")
        } else {
          try {
            val s = new SaveGraph
            s.saveJsonGraph(resultingGraph, configuration.savegraph)
            //s.saveJsonGraph(gcoreRunner.catalog.graph(resultingGraph.graphName), configuration.savegraph)
            println("The graph "+ resultingGraph.graphName + " was saved on the specified path")
          } catch {
            case e: Exception => {
              println("Not possible to save " + resultingGraph.graphName + " on specified path")
              e.printStackTrace()
            }
          }
        }
    }

    //set up an api for verifying the ground truth for entity resolution
    if(configuration.verification == true){
      val resultSet : ArrayBuffer[(String,Result)] = new ArrayBuffer[(String,Result)]()
      val configFile = Source.fromFile(args(1)).mkString
      val json = parse(configFile)
      val groundTruthComparison = json.extract[List[GroundTruthComparison]]
      val erEvaluation = new EntityResolutionGroundTruth()
      for(i <- 0 until groundTruthComparison.size){
        val g = groundTruthComparison.apply(i)
        erEvaluation.readGroundTruth(g.groundtruth, g.sep)
        var patternsResults = gcoreRunner.sparkSession.emptyDataFrame
        if(configuration.query.equals("spark")){
          patternsResults = ercommand.runSelectQuery(GGDtoGCoreParser.parseGCore(List(g.pattern)) + " ON " + g.pattern.name)
        }else if(configuration.query.equals("jdbc")){
          patternsResults = ercommand.runSelectQueryJDBC(GGDtoGCoreParser.parseGCore(List(g.pattern)) + " ON " + g.pattern.name)
        }
        val arrayIds = patternsResults.select(col(g.id1).alias("id1"), col(g.id2).alias("id2")).collect().map(x => {
          (x.getAs("id1").toString, x.getAs("id2").toString)
        })
        val result = erEvaluation.compareGroundTruth(arrayIds)
        val pair = (g.groundtruth, result)
        resultSet += pair
      }
      //save Results to configured Path
      saveVerificationResults(resultSet, configuration.verificationpath)
    }

    def saveVerificationResults(resultSet: ArrayBuffer[(String, Result)],path: String) : Unit = {
      try{
        val results = resultSet.map(x => {
          (x._1.toString , x._2.truePos , x._2.falsePos ,x._2.trueNeg , x._2.falseNeg)
        }).toDF("groundtruth", "true-pos", "false-pos", "true-neg", "false-neg")
        results
          .repartition(1)
          .write
          .option("header", true)
          .csv(path)
      } catch {
        case e: Exception => {
          println("Not possible to save to ground truth")
          e.printStackTrace()
        }
      }
    }

  }



}