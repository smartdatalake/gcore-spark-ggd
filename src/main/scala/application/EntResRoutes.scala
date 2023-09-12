package application

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route}
import ggd._
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization
import spray.json.DefaultJsonProtocol

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration.Duration
final case class Graph(nodes: String, links: String)
final case class GraphMap(similarity: String, graph: Graph, visid: String)
final case class GraphMapNextLevel(graph: Graph, visid: String)
final case class SaveInfo(saveType: String, path: String, graphPart: String, info: String, graphName: String)
case class ParamNeighbors(nodeLabel: String, id: String, edgeLabel: String, graphName: String ,limit: Int)
case class ParamNodes(label: String, graphName: String, limit: Int)
case class enResInfo(graphName: String, resultInfo: ArrayBuffer[generatedInfoGraph])
case class ggdsVisualizationData(ggd: GGD, option: String)
case class statusMessage(status: String)
case class Query(query: String, limit: Int)
case class QuerySample(label: String, limit: Int, graph: String)
case class TargetGraphInfo(graphName: String, unionGraphs: List[String])

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val graphFormat = jsonFormat2(Graph)
  implicit val graphMapFormat = jsonFormat3(GraphMap)
  implicit val graphMapNextLevelFormat = jsonFormat2(GraphMapNextLevel)
  // implicit val saveInfoFormat = jsonFormat5(SaveInfo)
}

class EntResRoutes(erCommands: ERCommand, gcoreApi: GCoreQueryAPI, server: serverVals, config: ConfigurationRunner) extends Directives with JsonSupport {
  implicit val system = server.system
  implicit val materializer = server.materializer
  implicit val executionContext = server.executionContext
  implicit val formats = DefaultFormats
  //creates a GCoreAPI object to support not only the er commands functions
  if(config.query == "jdbc"){
    erCommands.openConnectionJDBC(config.jdbcurl, config.username, config.pwd)
  }

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
    /*val df = if(config.query == "spark"){
      gcoreApi.runSelectQuery(query)
    }else erCommands.runSelectQueryJDBC(query)*/
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

  def saveInfo(info: String): Future[String] = Future {
    val saveConfigJson = parse(info)
    var msg = "Save was not succesfull"
    val save_info : SaveInfo = saveConfigJson.camelizeKeys.extract[SaveInfo]
    try{
      erCommands.savePartOfGraph(save_info.saveType, save_info.path, save_info.graphPart, save_info.info, save_info.graphName, config.query)
      msg =  "Save was successfull"
    }catch{
      case e: Exception => msg =  "Save was not succesful"
    }
    msg
  }


  def constructQuery(query: String) : Future[Graph] = Future {
    //val graph = erCommands.runConstructQuery(query)
    /*for raw integration rest api
    val graph = if(config.query == "spark"){
      gcoreApi.runConstructQuery(query)
    }else erCommands.runConstructQueryJDBC(query)*/
    val graph = gcoreApi.runConstructQuery(query)
    val net = UtilsApi.toJsonGraph(graph)
    Graph(net.nodes, net.links)
  }

  def constructQueryLimit(query: String) : Future[Graph] = Future {
    //val graph = erCommands.runConstructQuery(query)
    val requestJson = parse(query)
    val query_request : Query = requestJson.camelizeKeys.extract[Query]
    //for raw integration with rest api
    /*val graph = if(config.query == "spark"){
      gcoreApi.runConstructQuery(query_request.query, query_request.limit)
    }else erCommands.runConstructQueryJDBC(query_request.query)*/
    println(query_request.query)
    val graph = gcoreApi.runConstructQuery(query_request.query, query_request.limit)
    val net = UtilsApi.toJsonGraph(graph)
    Graph(net.nodes, net.links)
  }

  def constructQuerySampleLimit(query: String) : Future[Graph] = Future {
    //val graph = erCommands.runConstructQuery(query)
    val requestJson = parse(query)
    val query_request : QuerySample = requestJson.camelizeKeys.extract[QuerySample]
    //for raw integration with rest api
    /*val graph = if(config.query == "spark"){
      gcoreApi.runConstructQuery(query_request.query, query_request.limit)
    }else erCommands.runConstructQueryJDBC(query_request.query)*/
    println(query_request.label)
    val graph = gcoreApi.constructQueryLabel(query_request.label, query_request.limit, query_request.graph)
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
    println(str)
    var msg = "GGDs were not sent correctly -default value-"
    try{
      val seqGGDs = erCommands.ggds.parseGGDJsonApi(str)
      //println("ggds size" + seqGGDs.size)
      erCommands.setGGDs(seqGGDs)
      //println(erCommands.ggds.AllGGDs.size)
      msg = "GGDs set!!"
    }catch{
      case (e : org.json4s.MappingException) => {
        try{
          println("Exception!!!")
          val seqGGDsAsString = erCommands.ggds.parseGGDJsonStringToGGD(str)
          erCommands.setGGDs(seqGGDsAsString)
          msg = "GGDs set!!"
        }catch {
          case (e: Exception) => {
            msg = "GGDs were not sent correctly!"
          }
        }
      }
      case (e: Exception) =>{
        e.printStackTrace()
        msg = "GGDs were not sent correctly"
      }
    }
    msg
  }

  def dropGGDs(str: String): Future[String] = Future {
    println("dropGGDs:" + str)
    var msg = "There was an error on removing the selected GGDs"
    try{
      val json = parse(str)
      val info: List[generatedInfoGraph] = json.camelizeKeys.extract[List[generatedInfoGraph]]
      erCommands.dropGGDs(info)
      msg = "The selected GGDs were deleted!"
    }catch{
      case (e: Exception) => msg
    }
    msg
  }

  def createUnionGraphs(str: String) = Future {
    println("unionGraphs:" + str)
    var msg = "Could not create the target graph"
    try{
      val json = parse(str)
      val resultingGraph: TargetGraphInfo = json.camelizeKeys.extract[TargetGraphInfo]
      if(resultingGraph.unionGraphs.distinct.length == 1){
        msg = "The resulting graph name is:" + resultingGraph.unionGraphs.head + ", no need for creating a new graph"
      }else{
        erCommands.createGraph(resultingGraph.graphName, resultingGraph.unionGraphs)
        msg = "The resulting graph name is: " + resultingGraph.graphName
      }
    }catch{
      case (e:Exception) => msg
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
    val gcore = erCommands.getGcore()
    /*if(erCommands.ggds.AllGGDs.size > 1 && erCommands.ggds.AllGGDs.head.sourceGP.head.name == "CordisGraph"){
      gcore.catalog.unregisterGraph("CordisGraph")
      erCommands.loadGraph("/media/larissacsh/Data/SDLdatasets/keywordsResults1/CordisGraph/")
      gcore.catalog.setDefaultGraph("CordisGraph")
      Thread.sleep(10000)
      val json = Serialization.write(new enResInfo("CordisGraph",
        ArrayBuffer[generatedInfo](generatedInfo("Cordis_sameAs", 54516),generatedInfo("CordishasDept",220936),
          generatedInfo("CordisonDept", 220936), generatedInfo("CordisDept", 220936))))
      json
    }else{*/
    /*//for raw integration with rest api
    val resultGraph = if(config.query == "spark"){
       erCommands.graphGeneration()
     }else erCommands.graphGenerationJDBC(config.raw_path, config.raw_url, config.raw_oath, config.raw_save)*/
    val resultGraph = erCommands.graphGeneration()
    println("Finished running ER!" + resultGraph.graphName)
    gcore.catalog.setDefaultGraph("DBLPCordis")
    if (resultGraph.graphName== "PathPropertyGraph.empty") resultGraph.graphName = erCommands.ggds.AllGGDs.head.targetGP.head.name
    val json = Serialization.write(new enResInfo(resultGraph.graphName, erCommands.resultInfo.zipWithIndex.map( x =>
      new generatedInfoGraph(x._1.name, x._1.number, x._1.ggd, resultGraph.graphName, x._2)
    )))
    println("response:" + json)
    json
    //}
  }

  def constructGGDSample(param: String) : Future[Graph] = Future {
    val json = parse(param)
    val inputParam: ggdsVisualizationData = json.camelizeKeys.extract[ggdsVisualizationData]
    val ggd = new GraphGenDep()
    ggd.sourceGP = inputParam.ggd.sourceGP
    ggd.targetGP = inputParam.ggd.targetGP
    ggd.sourceCons = inputParam.ggd.sourceCons
    ggd.targetCons = inputParam.ggd.targetCons
    ggd.prettyPrint()
    inputParam.option match {
      case "validated" => {
        try{
          val graphPattern: GraphPattern = ggd.unionSourceTarget()
          val constructQuery = graphPattern.parseToGCoreConstruct()
          val query = "CONSTRUCT " + constructQuery.patternConstruct + " MATCH " + constructQuery.pattern + " ON " + constructQuery.graphName
          println(query)
          val limit = 50
          val graph = gcoreApi.runConstructQuery(query,limit)
          val net = UtilsApi.toJsonGraph(graph)
          Graph(net.nodes, net.links)
        }catch {
          case e: Exception => {
            e.printStackTrace()
            Graph("","")
          }
        }
      }
      case _ => {
        Graph("", "")
      }
    }

  }

  // domain model

  val timeoutResponse = HttpResponse(
    StatusCodes.EnhanceYourCalm,
    entity = "Unable to serve response within time limit")

  val duration = Duration(60000000, "millis")

  val durationVis = Duration(1200000, "millis")

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
            withRequestTimeout(durationVis) { //set longer timeout for this connection
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
          post{
            path("dropGGDs"){
              entity(as[String]){ infoGGDs => {
                val msg = dropGGDs(infoGGDs)
                complete(msg)
              }
              }
            }
          },
          post{
            path("createGraph"){
              entity(as[String]){ graphnames => {
                val msg = createUnionGraphs(graphnames)
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
          },
          post{
            path("construct-sample") {
              withRequestTimeout(duration){
                withRequestTimeoutResponse(request => timeoutResponse){
                  entity(as[String]) { query => {
                    //val graph: Future[Graph] = constructQuery(query)
                    val graph: Future[Graph] = constructQuerySampleLimit(query)
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
            path("save-info"){
              entity(as[String]){
                param => {
                  val msgSave : Future[String] = saveInfo(param)
                  complete(msgSave)
                }
              }
            }
          }
        )
      },
      pathPrefix("ggdvis"){
        concat(
          post{
            path("construct-ggd"){
              entity(as[String]){
                param => {
                  val constructGraph : Future[Graph] = constructGGDSample(param)
                  complete(constructGraph)
                }
              }
            }
          }
        )
      }
    )


}