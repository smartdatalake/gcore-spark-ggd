package application

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import compiler.{CompileContext, Compiler, GcoreCompiler}
import ggd.{ERCommand, GcoreRunner, Runner}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.SparkSession
import spark.examples.{CompanyGraph, DummyGraph, PeopleGraph, SocialGraph}
import spark.{Directory, SparkCatalog}

import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success}
// for JSON serialization/deserialization following dependency is required:
// "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.7"
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.io.StdIn

import scala.concurrent.Future

object WebServer {

  def loadDatabase(gcoreRunner: GcoreRunner, folder:String):Unit=
  {
    var directory = new Directory
    gcoreRunner.catalog.registerGraph(DummyGraph(gcoreRunner.sparkSession))
    gcoreRunner.catalog.registerGraph(PeopleGraph(gcoreRunner.sparkSession))
    gcoreRunner.catalog.registerGraph(SocialGraph(gcoreRunner.sparkSession))
    gcoreRunner.catalog.registerGraph(CompanyGraph(gcoreRunner.sparkSession))
    gcoreRunner.catalog.setDefaultGraph("social_graph")
    var load = directory.loadDatabase(folder, gcoreRunner.sparkSession, gcoreRunner.catalog)
    gcoreRunner.catalog.databaseDirectory = folder

    if (load)
      println("Current Database: "+folder)
    else
      println("The database entered was not loaded correctly")
  }

  Logger.getLogger("org").setLevel(Level.INFO)
  Logger.getLogger("akka").setLevel(Level.INFO)

  // needed to run the route
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  // needed for the future map/flatmap in the end and future in fetchItem and saveOrder
  implicit val executionContext = system.dispatcher
  val server = serverVals(system,materializer,executionContext)

  val gcoreRunner: GcoreRunner = Runner.newRunner
  gcoreRunner.catalog.databaseDirectory = "defaultDB"
  val erCommands: ERCommand = new ERCommand(gcoreRunner) //for GGDs ER (Validation/Repair methods)
  val gcoreApi: GCoreQueryAPI = new GCoreQueryAPI(gcoreRunner) //for gcore query methods
  val erRoutes: EntResRoutes = new EntResRoutes(erCommands,gcoreApi, server) //define future commands in erRoutes


  def main(args: Array[String]) {

    val port = args.apply(0)
    val databaseString = args.apply(1)
    loadDatabase(gcoreRunner, databaseString)
    val bindingFuture = Http().bindAndHandle(erRoutes.route, "0.0.0.0", port.toInt) //interface = localhost - ip address
    println(s"Server online at http://0.0.0.0:"+port+"/\nPress Ctrl+C to stop...")
  }
}

sealed case class serverVals(system: ActorSystem, materializer: ActorMaterializer, executionContext: ExecutionContextExecutor)