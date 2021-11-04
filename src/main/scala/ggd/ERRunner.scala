package ggd

import java.io.File
import java.nio.file.Paths

import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.DataFrame
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import schema.{PathPropertyGraph, SchemaException}
import spark.examples.{CompanyGraph, DummyGraph, PeopleGraph, SocialGraph}
import spark.{Directory, GraphSource, SaveGraph, SparkCatalog}

import scala.io.Source

/***
 * @param query - spark or jdbc (for Proteus connection)
 * @param jdbcurl - jdbc url
 * @param username - jdbc username
 * @param pwd - jdbc password
 * @param rawoath - authentication token for RAW Labs Connection (used only in case the query mode is jdbc, otherwise can be empty)
 * @param rawurl - url to connect to RAW
 * @param rawsave - save local for raw : (s3, hdfs, file)
 * @param rawpath - path for saving files from RAW using jdbc
 ***/
case class ConfigurationRunner(query: String, jdbcurl: String, username: String, pwd: String, rawoath: String, rawurl: String, rawsave: String, rawpath: String)


//Main for command-line application in the same format as the G-Core original application
object ERRunner{

  def main(args: Array[String]): Unit = {

    var log = false;
    activeLog(log)
    var active = true
    val gcoreRunner: GcoreRunner = Runner.newRunner
    val ERCommand = new ERCommand(gcoreRunner)
    implicit val formats = DefaultFormats
    var configurationFile = ConfigurationRunner("spark", "", "", "", "", "", "", "")

    gcoreRunner.catalog.registerGraph(DummyGraph(gcoreRunner.sparkSession))
    gcoreRunner.catalog.registerGraph(PeopleGraph(gcoreRunner.sparkSession))
    gcoreRunner.catalog.registerGraph(SocialGraph(gcoreRunner.sparkSession))
    gcoreRunner.catalog.registerGraph(CompanyGraph(gcoreRunner.sparkSession))
    gcoreRunner.catalog.setDefaultGraph("social_graph")


    if(args.size > 0){
      println(args(0))
      val configFile = Source.fromFile(args(0)).mkString
      val json = parse(configFile)
      configurationFile = json.camelizeKeys.extract[ConfigurationRunner]
    }

    if(configurationFile.query == "jdbc"){
      try{
        ERCommand.openConnectionJDBC(configurationFile.jdbcurl, configurationFile.username, configurationFile.pwd)
      }catch{
        case (e: Exception) => {
          e.printStackTrace()
          println("JDBC Connection not valid, check your configuration file and host - application will run using SparSQL")
          configurationFile = ConfigurationRunner("spark", "", "", "", "", "", "", "")
        }
      }
      try{
        ERCommand.loadP.con.getClientInfo
      }catch{
        case (e: Exception) =>  {
          println("JDBC Connection not valid, check your configuration file and host - application will run using SparSQL")
          configurationFile = ConfigurationRunner("spark", "", "", "", "", "", "", "")
        }
      }
    }

    options

    while(active)
    {
      var option = scala.io.StdIn.readLine("g-core=>: ")
      option.split(" ")(0) match {
        case "\\a" =>
          setDefaultGraph(gcoreRunner, option, configurationFile)
        case "\\f" =>
          loadGraphData(gcoreRunner, option, ERCommand, configurationFile)
        case "\\j" =>
          loadGraph(gcoreRunner, option, ERCommand, configurationFile)
        case "\\q" =>
          active = false
        case "\\c" =>
          saveGraph(gcoreRunner, option, true, configurationFile)
        case "\\h" =>
          options
        case "\\l" =>
          println(gcoreRunner.catalog.toString)
        case "\\d" =>
          println(gcoreRunner.catalog.graph(option.split(" ")(1)).toString)
        case "\\v" =>
          log= !log
          activeLog(log)
        case "\\g" =>
          println("Load ggds")
          loadGGDs(gcoreRunner, ERCommand, option, configurationFile)
        case "\\e" =>
          println("Run ER components")
          runGraphGeneration(gcoreRunner, ERCommand, configurationFile)
        case _ =>
          if(option.length >0 && option.substring(option.length-1).trim == ";") {

            var query = option.replace(";", "").trim
            try {
              println(query)
              gcoreRunner.compiler.compile(
                query)
            }
            catch {
              case parseException: parser.exceptions.QueryParseException => println(" Query type unsupported for the moment")
              case defaultgNotAvalilable: algebra.exceptions.DefaultGraphNotAvailableException => println(" No default graph available")
              case analysisException: org.apache.spark.sql.AnalysisException => println("Error: " + analysisException.getMessage())
              case unsupportedOperation: common.exceptions.UnsupportedOperation => println("Error: " + unsupportedOperation.getMessage)
              case matchError: scala.MatchError => println("Error: " + matchError.getMessage())
              case disjunctLabels: algebra.exceptions.DisjunctLabelsException => println("Error: " + disjunctLabels.getMessage)
              case schemaExeption: SchemaException => println("Error: " + schemaExeption.getMessage)
              case _: Throwable => println("Unexpected exception")
            }
          }
          else
            println("Invalid Option")
      }
    }
  }

  def setDefaultGraph( gcoreRunner: GcoreRunner , graphp: String, config: ConfigurationRunner): String =
  {
    var r_graph = graphp.replace("\\a","").trim
    if (!gcoreRunner.catalog.hasGraph(r_graph))
    {
      println("Error: Graph not available")
    }
    else
    {
      gcoreRunner.catalog.setDefaultGraph(r_graph)
      println(r_graph +" "+ "set as default graph." )
    }
    return graphp
  }



  def saveGraph(gcoreRunner: GcoreRunner, graphp: String, overwrite: Boolean, config: ConfigurationRunner): String =
  {
    if(config.query == "jdbc"){
      println("Error: operation not supported on jdbc mode - new tables generated are automatically saved as a view on RAW")
      return graphp
    }
    var r_graph = graphp.replace("\\c","").trim
    var graph = ""
    var path = ""
    if(r_graph.split(",").size >1){
      graph = r_graph.split(",").apply(0)
      path = r_graph.split(",").apply(1)
    }else{
      graph = r_graph.split(",").apply(0)
      path = gcoreRunner.catalog.databaseDirectory
    }
    if (!gcoreRunner.catalog.hasGraph(graph)) {
      println("Error: Graph not available")
    } else{
      try{
        val s = new SaveGraph()
        s.saveJsonGraph(gcoreRunner.catalog.graph(graph), path, overwrite)
      }catch {
        case e: Exception => println("Not possible to save " + graph + " on specified path")
      }
    }
    return graphp
  }

  def loadGraphData(gcoreRunner: GcoreRunner , graphp: String, command: ERCommand, config: ConfigurationRunner) :Unit =
  {
    var folderPath = graphp.replace("\\f","").trim
    loadDatabase(gcoreRunner, folderPath, command, config)
  }

  def loadGraph(gcoreRunner: GcoreRunner, graphp: String,command:ERCommand, config: ConfigurationRunner) : Unit = {
    var configPath = graphp.replace("\\j","").trim
    if(config.query == "jdbc"){
      command.loadGraphJDBC(configPath)
    }else{
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
  }

  def loadGGDs(gcoreRunner: GcoreRunner, ercom: ERCommand, graphp: String, config: ConfigurationRunner) : Unit = {
    val configPath = graphp.replace("\\g","").trim
    ercom.readGGDs(configPath)
    println("GGDs from config.json loaded into module")
  }

  def runGraphGeneration(runner: GcoreRunner, command: ERCommand, config: ConfigurationRunner): Unit = {
    if(command.ggds.AllGGDs.isEmpty){
      println("There are no GGDs set")
      return
    }
    runner.catalog.setDefaultGraph(command.ggds.AllGGDs.head.targetGP.head.name)
    if(config.query == "spark"){
      val resultGraph:PathPropertyGraph = command.graphGeneration()
      runner.catalog.registerGraph(resultGraph)
      println("Graph Generation resulting graph name: " + resultGraph.graphName)
    }else if(config.query == "jdbc"){
      val resultGraph:PathPropertyGraph = command.graphGenerationJDBC(config.rawpath, config.rawurl, config.rawoath, config.rawsave)
      runner.catalog.registerGraph(resultGraph)
      println("Graph Generation resulting graph name: " + resultGraph.graphName)
    }

  }


  def options: Unit =
  {
    println(
      """
        |Options ER-RUNNER:
        |\h Help.
        |\c Save Graph   (\c graph name,path)
        |\a Set default graph (graph name)
        | ; Execute a query.     (ex. CONSTRUCT (x) MATCH (x);)
        |\l Graphs in database.
        |\d Graph information. (\d graph)
        |\g Load GGDs (\g path-to-config-file)
        |\e Run GGD Component
        |\f Load Database (\f path-to-folder)
        |\j Load graph (\j path-to-graph-folder)
        |\v Log.
        |\q Quit.
      """.stripMargin);
  }

  def loadDatabase(gcoreRunner: GcoreRunner, folder:String, command: ERCommand, config: ConfigurationRunner):Unit=
  {
    if(config.query == "spark"){
      var directory = new Directory
      var load = directory.loadDatabase(folder, gcoreRunner.sparkSession, gcoreRunner.catalog)
      gcoreRunner.catalog.databaseDirectory = folder

      if (load)
        println("Current Database: "+folder)
      else
        println("The database entered was not loaded correctly")
    }else{
      command.loadDatabaseJDBC(folder)
    }
  }

  def activeLog(log: Boolean) =
  {
    if(log)
    {
      Logger.getLogger("org").setLevel(Level.INFO)
      Logger.getLogger("akka").setLevel(Level.INFO)
      println("Log activated")
    }

    else
    {
      Logger.getLogger("org").setLevel(Level.OFF)
      Logger.getLogger("akka").setLevel(Level.OFF)
      println("Log deactivated")
    }

  }

}