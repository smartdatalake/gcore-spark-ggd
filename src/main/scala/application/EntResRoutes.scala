package application

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.{as, complete, concat, entity, get, path, pathEnd, pathPrefix, post}
import akka.http.scaladsl.server.PathMatchers.Segment
import akka.http.scaladsl.server.{Directives, Route}
import ggd.{ERCommand, generatedInfo}
import org.apache.spark.sql.DataFrame
import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization
import org.json4s.jackson.JsonMethods.parse
import schema.EntitySchema.LabelRestrictionMap
import schema.Table
import spark.SparkGraph
import spray.json.DefaultJsonProtocol
import spray.json.DefaultJsonProtocol.{jsonFormat, jsonFormat2, jsonFormat3, _}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration.Duration
final case class Graph(nodes: String, links: String)
case class ParamNeighbors(nodeLabel: String, id: String, edgeLabel: String, graphName: String ,limit: Int)
case class ParamNodes(label: String, graphName: String, limit: Int)
case class enResInfo(graphName: String, resultInfo: ArrayBuffer[generatedInfo])
case class statusMessage(status: String)
case class Query(query: String, limit: Int)

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val graphFormat = jsonFormat2(Graph)
}

class EntResRoutes(erCommands: ERCommand, gcoreApi: GCoreQueryAPI, server: serverVals) extends Directives with JsonSupport {
  implicit val system = server.system
  implicit val materializer = server.materializer
  implicit val executionContext = server.executionContext
  implicit val formats = DefaultFormats
  //creates a GCoreAPI object to support not only the er commands functions

  def getGraph(name: String) : Future[String]  = Future {
    erCommands.graphSchema(name)
  }

  def getGraphSchema(name: String) : Future[Graph] = Future{
    //erCommands.graphSchemaJson(name)
    val net = UtilsApi.toJsonSchema(gcoreApi.getGraph(name))
    Graph(net.nodes, net.links)
  }

  def getAvailabeGraph(): Future[List[String]] = Future {
    erCommands.availableGraphs()
  }

  def selectQuery(query: String) : Future[String] = Future {
    //val df = erCommands.runSelectQuery(query)
    val df = gcoreApi.runSelectQuery(query)
    println("JSON SELECT QUERY")
    val maps =df
      .collect
      .map(
        row => df
          .columns
          .foldLeft(Map.empty[String, Any])
          (
            (acc, item) => acc + (item -> row.getAs[Any](item))
          )
      )
    val json = Serialization.write(maps)
    json
    //println(json)
  }

  def selectQueryLimit(query: String) : Future[String] = Future {
    //val df = erCommands.runSelectQuery(query)
    println(query)
    val requestJson = parse(query)
    val query_request : Query = requestJson.camelizeKeys.extract[Query]
    val df = gcoreApi.runSelectQuery(query_request.query, query_request.limit)
    println("JSON SELECT QUERY")
    val maps =df
      .collect
      .map(
        row => df
          .columns
          .foldLeft(Map.empty[String, Any])
          (
            (acc, item) => acc + (item -> row.getAs[Any](item))
          )
      )
    val json = Serialization.write(maps)
    json
    //println(json)
  }

  def constructQuery(query: String) : Future[Graph] = Future {
    //val graph = erCommands.runConstructQuery(query)
    val graph = gcoreApi.runConstructQuery(query)
    val net = UtilsApi.toJsonGraph(graph)
    Graph(net.nodes, net.links)
  }

  def constructQueryLimit(query: String) : Future[Graph] = Future {
    //val graph = erCommands.runConstructQuery(query)
    val requestJson = parse(query)
    val query_request : Query = requestJson.camelizeKeys.extract[Query]
    val graph = gcoreApi.runConstructQuery(query_request.query, query_request.limit)
    val net = UtilsApi.toJsonGraph(graph)
    Graph(net.nodes, net.links)
  }

  def constructQueryString(query: String) : Future[String] = Future {
    //val graph = erCommands.runConstructQuery(query)
    val graph = gcoreApi.runConstructQuery(query)
    val net = UtilsApi.toJsonGraph(graph)
    val manualJsonString = "{\"links\":" + net.links + ", \"nodes\":" + net.nodes + "}"
    manualJsonString
    //Graph(net.nodes, net.links)
  }

  def getNeighborsGraph(str:String) : Future[Graph] = Future {
    implicit val formats = DefaultFormats
    val json = parse(str)
    val param : ParamNeighbors = json.camelizeKeys.extract[ParamNeighbors]
    val graph = gcoreApi.getConstructNeighbors(param.nodeLabel, param.id, param.graphName, param.edgeLabel)
    val net = UtilsApi.toJsonGraph(graph)
    Graph(net.nodes, net.links)
  }

  def getNeighborsTable(str: String): Future[String] = Future {
    implicit val formats = DefaultFormats
    println(str)
    val jsonOr = parse(str)
    val param : ParamNeighbors = jsonOr.camelizeKeys.extract[ParamNeighbors]
    val df = gcoreApi.getSelectNeighbors(param.nodeLabel, param.id, param.graphName, param.edgeLabel, param.limit)
    val maps =df
      .collect
      .map(
        row => df
          .columns
          .foldLeft(Map.empty[String, Any])
          (
            (acc, item) => acc + (item -> row.getAs[Any](item))
          )
      )
    val json = Serialization.write(maps)
    json
  }

  def getNodesGraph(param: ParamNodes): Future[String] = Future {
    val df = gcoreApi.getNodesOfLabel(param.label, param.graphName, param.limit)
    val maps =df
      .collect
      .map(
        row => df
          .columns
          .foldLeft(Map.empty[String, Any])
          (
            (acc, item) => acc + (item -> row.getAs[Any](item))
          )
      )
    val json = Serialization.write(maps)
    json
  }

  def setGGDs(str: String) : Future[String] = Future {
    var msg = "GGDs were not sent correctly -default value-"
    try{
      val seqGGDs = erCommands.ggds.parseGGDJsonApi(str)
      //println("ggds size" + seqGGDs.size)
      erCommands.setGGDs(seqGGDs)
      //println(erCommands.ggds.AllGGDs.size)
      msg = "GGDs set!!"
    }catch{
      case (e: Exception) =>
          msg = "GGDs were not sent correctly"
    }
    msg
  }

  def getGGDs(): Future[String] = Future {
    //val ggdsSequence = erCommands.ggds.AllGGDs
    val ggds = erCommands.ggds.toJsonString()
    if(ggds == "[]") {
      Serialization.write(new statusMessage("No ggds were uploaded in server!"))
    }else ggds
    //return marshalling format for json
  }

  def runER(): Future[String] = Future {
    println("RUN ER")
      val resultGraph = erCommands.graphGeneration()
      println("Finished running ER!" + resultGraph.graphName)
      if (resultGraph.graphName== "PathPropertyGraph.empty") resultGraph.graphName = erCommands.ggds.AllGGDs.head.targetGP.head.name
      val json = Serialization.write(new enResInfo(resultGraph.graphName, erCommands.resultInfo))
      json
    }

  // domain model

  val timeoutResponse = HttpResponse(
    StatusCodes.EnhanceYourCalm,
    entity = "Unable to serve response within time limit, ER should take longer than this...")

  val duration = Duration(60000000, "millis")

  val route : Route =
    concat(
      pathPrefix("graphDB") {
        concat(
          pathEnd {
            val graphs: Future[List[String]] = getAvailabeGraph()
            complete(getAvailabeGraph())
            //complete("/graphDB")
          },
          path(Segment) { name =>
            withRequestTimeout(duration) { //set longer timeout for this connection
              withRequestTimeoutResponse(request => timeoutResponse) {
                //val schemaString: Future[String] = getGraph(name)
                //complete(schemaString)
                val schemaGraph: Future[Graph] = getGraphSchema(name)
                complete(schemaGraph)
              }
            }
          }
        )
      },
      pathPrefix("ggds"){
        concat(
          post{
            path("setGGDs") {
              entity(as[String]) { seqGGDs => {
                 val msg = setGGDs(seqGGDs)
                  complete(msg)
                }
              }
            }
          },
          get{
            path("getGGDs"){
              val ggds: Future[String] = getGGDs()
              complete(ggds)
            }
          },
          get{
            path("runER"){
              withRequestTimeout(duration) { //set longer timeout for this connection
                withRequestTimeoutResponse(request => timeoutResponse) {
                  val resultGraphName: Future[String] = runER()
                  complete(resultGraphName)
                }
              }
            }
          }
        )
      },
      pathPrefix("gcore"){
        concat(
        post {
          path("select") {
            withRequestTimeout(duration){
              withRequestTimeoutResponse(request => timeoutResponse){
                entity(as[String]) { query => {
                  //val df: Future[String] = selectQuery(query)
                  val df: Future[String] = selectQueryLimit(query)
                  complete(df)
                }
                }
              }
            }
          }
        },
        post{
          path("select-neighbor") {
            entity(as[String]) {
              param => {
                val df: Future[String] = getNeighborsTable(param)
                complete(df)
              }
            }
          }
        },
        post{
          path("construct") {
            withRequestTimeout(duration){
              withRequestTimeoutResponse(request => timeoutResponse){
                entity(as[String]) { query => {
                  //val graph: Future[Graph] = constructQuery(query)
                  val graph: Future[Graph] = constructQueryLimit(query)
                  complete(graph)
                  //  val graph: Future[String] = constructQueryString(query)
                  //  complete(graph)
                }
              }
            }
            }
          }
        },
         post{
            path("construct-neighbor"){
              entity(as[String]) {
                param => {
                  val graph: Future[Graph] = getNeighborsGraph(param)
                  complete(graph)
                }
              }
            }
          }
        )
      }
    )


}
