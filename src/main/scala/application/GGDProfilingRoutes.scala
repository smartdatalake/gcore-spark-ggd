package application

import java.util

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route}
import algebra.expressions.Label
import api.GGDMinerRunner
import application.UtilsApi.getDataType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import common.RandomNameGenerator
import ggd._
import ggdBase.{GGD,GraphPattern,Constraint}
import ggdSearch.GGDLatticeNode
import minerDataStructures.answergraph.{AGVertex, AnswerGraph}
import minerDataStructures.nngraph.NNGraph
import minerDataStructures.{DecisionBoundaries, GGDMinerConfiguration, LabelCodes, Tuple}
import minerUtils.ExperimentsStatistics
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.write
import schema.PathPropertyGraph


import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.io.Source
case class LabelCodeTmp(label: String, code: Integer)
case class DecisionBoundariesTmp(dataType: String, minThreshold: Double, minDifference: Double)
case class GGDTmpConfig(freqThreshold: Integer, confidence: Double, shortDistance: Integer, diversityThreshold: Double, kedge: Integer, sample: Boolean, sampleRate: Double,
                        preprocess: String, maxHops: Integer, kgraph: Integer, schemaSim: Double, minCoverage: Integer, minDiversity: Integer, maxMappings: Integer, maxCombination: Integer, minThresholdPerDataType: List[DecisionBoundariesTmp])
case class Graph_N(nodes: List[Map[String, String]], links: List[Map[String, String]])
case class Graph_NAny(nodes: List[Map[String, Any]], links: List[Map[String, Any]])
case class minerConfiguration(name: String, config: GGDMinerConfiguration)
case class propertyInput(graph:String, label:String, property: String)
case class numberLabels(vertices: List[LabelMap], edges: List[LabelMap])
case class LabelMap(name: String, number: Long)
case class IndexedAttr(datatype: String, label: String, attribute: String)
case class UniqueValuesClass(name: String, value: Long, fill:String)
case class HistValues(name: String, amnt: Long)
case class ArgsSimIndex(label: String, attribute: String)
case class Clusters(cluster: Integer, values:Set[String])
case class SimJoinRequest(label1: String, label2: String, attribute1: String, attribute2: String, threshold: Double)
case class JoinResult(id1: String, attr1: String, id2: String, attr2: String)
case class JoinResult_Count(attr1: String, attr2: String, count: String)
case class HistInput(label: String, attribute: String, bucket:Integer)
case class Subgraph(i: Int, graph: Graph_N, constraint: List[ggd.Constraint])
case class GraphGenDep_Vis(index: Int, sourceGP: Graph_N, targetGP: Graph_N, sourceCons: List[ggd.Constraint], targetCons: List[ggd.Constraint])
case class ParamsIndex(index: Integer, limit: Double)
case class PropertiesRequest(variable: String, id: String, label:String)
case class GGDStats(id: Integer, nsource: Integer, ntarget: Integer, confidence: Double, coverage: Double)
case class paramComparison(label: String, attribute: String)
case class ParamsIndexGGD(index: Integer, limit: Double, typ: String)


class GGDProfilingRoutes(erCommands: ERCommand, gcoreApi: GCoreQueryAPI, server: serverVals, databaseFolder : String) extends Directives with JsonSupport {
  implicit val system = server.system
  implicit val materializer = server.materializer
  implicit val executionContext = server.executionContext
  implicit val formats = DefaultFormats
  val gcorunner = erCommands.getGcore()
  var graphName = "dummy_graph"
  var mapGGDLattice = Map[Integer, GGDLatticeNode[String, String]]()
  var mapGGD = Map[Integer, GGD[String, String]]()
  var currentAG_show: AnswerGraph[String, String] = null
  var current_GGD: GGD[String, String] = null
  import gcorunner.sparkSession.implicits._


  val minerRunner: GGDMinerRunner[String, String] = new GGDMinerRunner[String, String]()
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

  def getAttributePairsFromMiner(): Future[String] = Future{
    val attrPairs = minerRunner.getAttributePairs
    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)
    val str =mapper.writeValueAsString(attrPairs)
    str
  }

  def getAttributeIndexesFromMiner(): Future[String] = Future{
    val attrIndexes = minerRunner.getSetToverify
    println(attrIndexes)
    val list = new ListBuffer[IndexedAttr]
    val it = attrIndexes.keySet().iterator()
    while(it.hasNext){
      val label = it.next();
      val attributes = attrIndexes.get(label)
      val it2 = attributes.iterator()
      while(it2.hasNext){
        val attr = it2.next()
        val datatype = "String"
        list.append(IndexedAttr(datatype,label,attr))
      }
    }
    val str = write(list)
    println(str)
    str
  }

  def getNodesFromJavaPattern(patternJava: GraphPattern[String, String]): List[Map[String, String]] = {
    val vertices = patternJava.getVertices
    val it = vertices.iterator()
    val list = new ArrayBuffer[Map[String, String]]()
    while(it.hasNext){
      val node = it.next()
      list.append(Map("id"-> node.nodeVariable, "label" -> node.nodeLabel))
    }
    list.toList
  }


  def getEdgesFromJavaPattern(patternJava: GraphPattern[String, String]): List[Map[String, String]] = {
    val edges = patternJava.getEdges
    val it = edges.iterator()
    val list = new ArrayBuffer[Map[String, String]]()
    while(it.hasNext){
      val edge = it.next()
      list.append(Map("source"-> edge.sourceVariable, "target" -> edge.targetVariable, "label" -> edge.label, "var" -> edge.variable))
    }
    list.toList
  }

  def getConstraintFromJava(contraintsJava: util.List[Constraint]) : List[ggd.Constraint] = {
    val iterator = contraintsJava.iterator()
    val list = new ArrayBuffer[ggd.Constraint]()
    while(iterator.hasNext){
      val constraint = iterator.next()
      list.append(new ggd.Constraint(constraint.getDistance, constraint.getVar1, constraint.getVar2, constraint.getAttr1, constraint.getAttr2, constraint.getThreshold, constraint.getOperator))
    }
    list.toList
  }

  def getGGDsFromMiner(): Future[String] = Future{
    println("GET GGDs")
    val ggds = minerRunner.sr.resultingGGDs
    val set = new mutable.HashSet[GraphGenDep_Vis]()
    val map = new mutable.HashMap[Integer, GGD[String, String]]()
    var index = 0
    val iterator = ggds.iterator()
    while(iterator.hasNext){
      val ggdJava = iterator.next().asInstanceOf[GGD[String, String]]
      //ggdJava.prettyPrint()
      val nodesSource = getNodesFromJavaPattern(ggdJava.getSourceGP.get(0).asInstanceOf[GraphPattern[String, String]])
      val edgesSource = getEdgesFromJavaPattern(ggdJava.getSourceGP.get(0).asInstanceOf[GraphPattern[String, String]])
      val nodesTarget = getNodesFromJavaPattern(ggdJava.getTargetGP.get(0).asInstanceOf[GraphPattern[String, String]])
      val edgesTarget = getEdgesFromJavaPattern(ggdJava.getTargetGP.get(0).asInstanceOf[GraphPattern[String, String]])
      val sourceCons = getConstraintFromJava(ggdJava.getSourceCons)
      val targetCons = getConstraintFromJava(ggdJava.getTargetCons)
      set.add(new GraphGenDep_Vis(index, Graph_N(nodesSource, edgesSource), Graph_N(nodesTarget, edgesTarget), sourceCons, targetCons))
      map.put(index, ggdJava)
      index = index + 1
    }
    mapGGD = map.toMap
    write(set)
  }



  def getGGDsStats(): Future[String] = Future{
    println("In GGDStats")
    //val answerList = new ArrayBuffer[GGDStats]()
    val statistics: ExperimentsStatistics[String, String] = new ExperimentsStatistics[String, String]
    val answerlist = mapGGD.map(e => {
      val id = e._1
      val nsource = e._2.numberMatches_source
      val ntarget = if(e._2.numberMatches_target == 0){
        e._2.numberMatches_source
      }else e._2.numberMatches_target
      //val ntarget = e._2.numberMatches_target
      val confidence = e._2.confidence
      val coverage: Double = try{
        statistics.coverageGGD(e._2)
      }catch{
        case (exception: Exception) => {
          0.0;
        };
      }
      GGDStats(id, nsource, ntarget, confidence, coverage)
    })
    write(answerlist)
  }


  def getFrequentSubgraphsFromMiner(): Future[String] = Future{
    println("Frequent Subgraphs Call")
    val candGraph = minerRunner.getCandidateGraph
    var index = 0;
    val it = candGraph.getNodes.iterator()
    val list = new ArrayBuffer[Subgraph]() //list of frequent subgraphs
    val map = new mutable.HashMap[GGDLatticeNode[String, String], Integer]()
    val nodeIds = new ArrayBuffer[Map[String, String]]()
    println(map.keySet.size)
    while(it.hasNext){
      val node = it.next()
      if(node.getConstraints.constraints.isEmpty) {
        val patternJava = node.pattern
        val constraintsJava = node.getConstraints.constraints
        val indexNode = index
        val nodesString = getNodesFromJavaPattern(patternJava)
        val linksString = getEdgesFromJavaPattern(patternJava)
        if(!nodesString.isEmpty) {
          val graph = Graph_N(nodesString, linksString)
          val constraint = getConstraintFromJava(constraintsJava)
          map.put(node, indexNode)
          list.append(Subgraph(indexNode, graph, constraint))
          nodeIds.append(Map("id" -> index.toString))
          index = index + 1
        }
      }
    }
    println("Patterns" + list.size)
    val candgraphMap = candGraph.asInstanceOf[NNGraph[GGDLatticeNode[String,String]]].map
    val candgraphAll = new ArrayBuffer[Map[String,String]]()
    val sourceNodes = candgraphMap.keySet().iterator()
    println("Number of source nodes" + candgraphMap.keySet().size())
    while(sourceNodes.hasNext) {
      val snode = sourceNodes.next()
      println("source:::")
      snode.pattern.prettyPrint()
      val neighborList = candgraphMap.get(snode).getAllNeighborsAsList
      if (map.contains(snode)) {
        val sourceIndex = map.get(snode).get
        println("Neighbor list size::" + neighborList.size())
        for (i <- 0 until neighborList.size()) {
          val neighbor = neighborList.get(i)
          if (map.contains(neighbor.x.asInstanceOf[GGDLatticeNode[String, String]])) {
            val targetindex = map.get(neighbor.x.asInstanceOf[GGDLatticeNode[String, String]]).get
            val similarity = neighbor.y
            candgraphAll.append(Map("source" -> sourceIndex.toString, "target" -> targetindex.toString, "similarity" -> similarity.toString))
          }
        }
      }
    }
    val candgraph_parsed = Graph_N(nodeIds.toList, candgraphAll.toList)
    mapGGDLattice = map.map(x => {
      x._2 -> x._1
    }).toMap
    write(Map("Subgraph" -> list, "CandidateGraph" -> candgraph_parsed))
  }

  def getNumericalValues(): Future[String] = Future {
    println("Calling numerical values")
    val graph = gcoreApi.getGraph(graphName)
    val nodesSchema = graph.vertexSchema
    val edgesSchema = graph.edgeSchema
    val edgeRest = graph.edgeRestrictions
    val list = new ArrayBuffer[ArgsSimIndex]()
    var i: Int = 0
    val labelsNode = nodesSchema.labels
    for(i <- labelsNode){
      for(p <- nodesSchema.properties(i)){
        if(!getDataType(nodesSchema, i,p).startsWith("String") && !getDataType(nodesSchema, i, p).startsWith("Boolean")){
          list.append(ArgsSimIndex(i.value, p.name))
        }
      }
    }
    for(i <- edgesSchema.labels){
      for(p <- edgesSchema.properties(i)){
        if(!getDataType(edgesSchema, i,p).startsWith("String") && !getDataType(edgesSchema, i, p).startsWith("Boolean")){
          list.append(ArgsSimIndex(i.value, p.name))
        }
      }
    }
    write(list)
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
      erCommands.savePartOfGraph(save_info.saveType, save_info.path, save_info.graphPart, save_info.info, save_info.graphName, "spark")
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

  def initializeConfigMinerFromJson(jsonstr: String): GGDMinerConfiguration = {
    println(jsonstr)
    val json = parse(jsonstr)
    println(json.values.toString)
    val tmpConfig = json.camelizeKeys.extract[GGDTmpConfig];
    val ggdminerConfig = new GGDMinerConfiguration();
    ggdminerConfig.freqThreshold = tmpConfig.freqThreshold
    ggdminerConfig.confidence = tmpConfig.confidence
    ggdminerConfig.diversityThreshold = tmpConfig.diversityThreshold
    ggdminerConfig.kedge = tmpConfig.kedge
    ggdminerConfig.kgraph = tmpConfig.kgraph
    ggdminerConfig.maxCombination = tmpConfig.maxCombination
    ggdminerConfig.maxHops = tmpConfig.maxHops
    ggdminerConfig.maxMappings = tmpConfig.maxMappings
    ggdminerConfig.minCoverage = tmpConfig.minCoverage.doubleValue()
    ggdminerConfig.minDiversity = tmpConfig.minDiversity.doubleValue()
    ggdminerConfig.preprocess = tmpConfig.preprocess
    ggdminerConfig.sample = tmpConfig.sample
    ggdminerConfig.sampleRate = tmpConfig.sampleRate
    ggdminerConfig.schemaSim = tmpConfig.schemaSim
    ggdminerConfig.shortDistance = tmpConfig.shortDistance
    val labelCodePath = databaseFolder + "/" + graphName + "/labelCodes.json";
    val configs = Source.fromFile(labelCodePath).mkString
    val jsonCodes = parse(configs)
    val tmpLabelCode = jsonCodes.camelizeKeys.extract[List[LabelCodeTmp]]
    val tmpListLabel = new util.LinkedList[LabelCodes]()
    tmpLabelCode.foreach(labelCode => {
      val x = new LabelCodes()
      x.label = labelCode.label
      x.code = labelCode.code
      tmpListLabel.add(x)
    })
    ggdminerConfig.labelCode = tmpListLabel
    val tmpListBoundary = new util.LinkedList[DecisionBoundaries]()
    tmpConfig.minThresholdPerDataType.foreach(b => {
      val x = new DecisionBoundaries()
      x.dataType = b.dataType
      x.minThreshold = b.minThreshold
      x.minDifference = b.minDifference
      tmpListBoundary.add(x)
    })
    ggdminerConfig.minThresholdPerDataType = tmpListBoundary
    ggdminerConfig
  }

  def getAnswerGraphInGraphN(ag: AnswerGraph[String, String], pattern: GraphPattern[String, String], params: ParamsIndex, commonVars: util.List[Tuple[String, String]]): Graph_N = {
    val nodesList = new mutable.HashSet[Map[String, String]]()
    val edgesList = new mutable.HashSet[Map[String, String]]()
    if (ag.edges.isEmpty) { //if edges is empty it means there are only one node
      val nodeIds = ag.nodes.keySet()
      val iterator = nodeIds.iterator()
      while (iterator.hasNext) {
        val variable = iterator.next()
        val label = if(!commonVars.contains(variable)){
          pattern.getLabelOfThisVariable(variable.split("_t").apply(0))
        }else pattern.getLabelOfThisVariable(variable)
        val vertices = ag.nodes.get(variable).asInstanceOf[AGVertex[String, String]].vertices.keySet()
        val totalOfThisNode = ag.nodes.get(variable).asInstanceOf[AGVertex[String, String]].vertices.size()
        val iteratorNodeIds = vertices.iterator()
        var sizeOfThisNode = 0
        while (iteratorNodeIds.hasNext && sizeOfThisNode < (totalOfThisNode * params.limit)) {
          val id = iteratorNodeIds.next()
          nodesList.add(Map("id" -> id, "label" -> label))
          sizeOfThisNode = sizeOfThisNode + 1
        }
      }
      val graph = Graph_N(nodesList.toList, List.empty)
      return graph
    } else {
      val edgesIds = ag.edges.keySet()
      val iterator = edgesIds.iterator()
      while (iterator.hasNext) {
        val edgeVar = iterator.next()
        val label = if(!commonVars.contains(edgeVar)){
          pattern.getLabelOfThisVariable(edgeVar.split("_t").apply(0))
        }else pattern.getLabelOfThisVariable(edgeVar)
        //val label = pattern.getLabelOfThisVariable(edgeVar)
        val edgesPattern = pattern.getEdgeFromVariable(edgeVar)
        val edges = ag.edges.get(edgeVar).edgeSrcTrg
        val totalOfThiEdges = edges.size()
        val iteratorEdgeIds = edges.keySet().iterator()
        var sizeOfThisEdge = 0
        while (iteratorEdgeIds.hasNext && sizeOfThisEdge < (totalOfThiEdges * params.limit)) {
          val id = iteratorEdgeIds.next()
          val tuple = edges.get(id)
          edgesList.add(Map("id" -> id, "label" -> label, "variable" -> edgeVar, "source" -> tuple.x, "target" -> tuple.y))
          nodesList.add(Map("id" -> tuple.x, "label" -> edgesPattern.sourceLabel))
          nodesList.add(Map("id" -> tuple.y, "label" -> edgesPattern.targetLabel))
          sizeOfThisEdge = sizeOfThisEdge + 1
        }
      }
      val graph = Graph_N(nodesList.toList, edgesList.toList)
      return graph
    }
  }


  def getAnswerGraphInGraphN(ag: AnswerGraph[String, String], pattern: GraphPattern[String, String], commonVars: util.List[Tuple[String, String]]): Graph_N = {
    val nodesList = new mutable.HashSet[Map[String, String]]()
    val edgesList = new mutable.HashSet[Map[String, String]]()
    if (ag.edges.isEmpty) { //if edges is empty it means there are only one node
      val nodeIds = ag.nodes.keySet()
      val iterator = nodeIds.iterator()
      while (iterator.hasNext) {
        val variable = iterator.next()
        val label = if(!commonVars.contains(variable)){
          pattern.getLabelOfThisVariable(variable.split("_t").apply(0))
        }else pattern.getLabelOfThisVariable(variable)
        val vertices = ag.nodes.get(variable).asInstanceOf[AGVertex[String, String]].vertices.keySet()
        val totalOfThisNode = ag.nodes.get(variable).asInstanceOf[AGVertex[String, String]].vertices.size()
        val iteratorNodeIds = vertices.iterator()
        var sizeOfThisNode = 0
        while (iteratorNodeIds.hasNext ) {
          val id = iteratorNodeIds.next()
          nodesList.add(Map("id" -> id, "label" -> label))
          sizeOfThisNode = sizeOfThisNode + 1
        }
      }
      val graph = Graph_N(nodesList.toList, List.empty)
      return graph
    } else {
      val edgesIds = ag.edges.keySet()
      val iterator = edgesIds.iterator()
      while (iterator.hasNext) {
        val edgeVar = iterator.next()
        val label = if(!commonVars.contains(edgeVar)){
          pattern.getLabelOfThisVariable(edgeVar.split("_t").apply(0))
        }else pattern.getLabelOfThisVariable(edgeVar)
        //val label = pattern.getLabelOfThisVariable(edgeVar)
        val edgesPattern = pattern.getEdgeFromVariable(edgeVar)
        val edges = ag.edges.get(edgeVar).edgeSrcTrg
        val totalOfThiEdges = edges.size()
        val iteratorEdgeIds = edges.keySet().iterator()
        var sizeOfThisEdge = 0
        while (iteratorEdgeIds.hasNext) {
          val id = iteratorEdgeIds.next()
          val tuple = edges.get(id)
          edgesList.add(Map("id" -> id, "label" -> label, "source" -> tuple.x, "target" -> tuple.y))
          nodesList.add(Map("id" -> tuple.x, "label" -> edgesPattern.sourceLabel))
          nodesList.add(Map("id" -> tuple.y, "label" -> edgesPattern.targetLabel))
          sizeOfThisEdge = sizeOfThisEdge + 1
        }
      }
      val graph = Graph_N(nodesList.toList, edgesList.toList)
      return graph
    }
  }

  def fullGraphPattern(ggd: GGD[String, String]) : GraphPattern[String, String] = {
    val source = ggd.getSourceGP.get(0)
    val target = ggd.getTargetGP.get(0)
    val verticesIt = target.getVertices.iterator()
    val edgesIt = target.getEdges.iterator()
    val newgp = new GraphPattern[String, String](source)
    while(edgesIt.hasNext){
      newgp.addEdge(edgesIt.next())
    }
    while(verticesIt.hasNext){
      newgp.addVertex(verticesIt.next())
    }
    return newgp
  }


  def validatedAnswerGraph(ggd: GGD[String, String]): AnswerGraph[String, String] = {
    val answergraphValidatedSource = ggd.getSourceAnswerGraph.filter_AGV3(ggd.getTargetAnswerGraph, ggd.getCommonVars)
    val targetAnswergraph = ggd.getTargetAnswerGraph
    val commonTargetSource = new util.HashMap[String, String]
    val itCommon = ggd.getCommonVars.iterator()
    while(itCommon.hasNext) { // commonSourceTarget.put(y.x, y.y);
      val y = itCommon.next()
      commonTargetSource.put(y.x, y.y)
    }
    val edgesit = targetAnswergraph.edges.keySet().iterator()
    val nodesit = targetAnswergraph.nodes.keySet().iterator()
    while(edgesit.hasNext){
      val edge = edgesit.next()
      if(!commonTargetSource.containsKey(edge)){
        answergraphValidatedSource.addEdgesOfVariables(edge, targetAnswergraph.getEdgeIds(edge))
      }
    }
    while(nodesit.hasNext){
      val node = nodesit.next()
      if(!commonTargetSource.containsKey(node)){
        answergraphValidatedSource.addEdgesOfVariables(node, targetAnswergraph.getEdgeIds(node))
      }
    }
    answergraphValidatedSource
  }


  def violatedAnswerGraph(ggd: GGD[String, String]): AnswerGraph[String, String] = {
    val ag = ggd.getSourceAnswerGraph.filter_violated(ggd.getTargetAnswerGraph, ggd.getCommonVars)
    ag
  }


  def toScalaPattern(gp: GraphPattern[String,String]) : ggd.GraphPattern = {
    val nodes = new ArrayBuffer[VerticesPattern]()
    val edges = new ArrayBuffer[EdgesPattern]()
    val vit = gp.getVertices.iterator()
    val eit = gp.getEdges.iterator()
    while(vit.hasNext){
      val n = vit.next()
      val variable = if((n.nodeVariable.endsWith("_t"))){
        "t" + n.nodeVariable.split("_t").apply(0)
      }else {
        "s" + n.nodeVariable
      }
      nodes.append(VerticesPattern(n.nodeLabel, variable))
    }
    while(eit.hasNext){
      val e = eit.next()
      val variable = if((e.variable.endsWith("_t"))){
        "t" + e.variable.split("_t").apply(0)
      }else {
        "s" + e.variable
      }
      val source = if((e.sourceVariable.endsWith("_t"))){
        "t" + e.sourceVariable.split("_t").apply(0)
      }else {
        "s" + e.sourceVariable
      }
      val target = if((e.targetVariable.endsWith("_t"))){
        "t" + e.targetVariable.split("_t").apply(0)
      }else {
        "s" + e.targetVariable
      }
      edges.append(EdgesPattern(e.label, variable, source, target))
    }
    new ggd.GraphPattern(graphName, nodes.toList, edges.toList)
  }


  def violatedQuery(ggd: GGD[String, String]) : PathPropertyGraph = {
    val source = toScalaPattern(ggd.getSourceGP.get(0))
    val target = toScalaPattern(ggd.getTargetGP.get(0))
    val sourceQ = gcoreApi.runSelectQuery("SELECT * MATCH " + source.parseToGCoreSelect().pattern + " ON " + graphName)
    val targetQ = gcoreApi.runSelectQuery("SELECT * MATCH " + target.parseToGCoreSelect().pattern + " ON " + graphName)
    val cols = sourceQ.columns.intersect(targetQ.columns)
    //val df = sourceQ.join(targetQ, cols, "left outer")
    val sourceAll = gcoreApi.runConstructQuery("CONSTRUCT " + source.parseToGCoreConstruct().patternConstruct + " MATCH " + source.parseToGCoreConstruct().pattern + " ON " + graphName)
    sourceAll
  }

  def getGGDAnswerGraph(config: String): Future[String] = Future { //constains random sampling
    val json = parse(config)
    val params = json.camelizeKeys.extract[ParamsIndexGGD]
    try {
      val ggdlattice = mapGGD.get(params.index).get
      current_GGD = ggdlattice
      val sourceAG = ggdlattice.getSourceAnswerGraph
      val targetAG = ggdlattice.getTargetAnswerGraph //get Target Answer Graph
      val sourcePattern = ggdlattice.getSourceGP.get(0).asInstanceOf[GraphPattern[String, String]]
      val targetPattern = ggdlattice.getTargetGP.get(0).asInstanceOf[GraphPattern[String, String]]
      if(params.typ.equalsIgnoreCase("validated")){
        val querygp = fullGraphPattern(ggdlattice)
        val scalaPattern = toScalaPattern(querygp)
        val constructQuerystr = scalaPattern.parseToGCoreConstruct()
        val query = "CONSTRUCT " + constructQuerystr.patternConstruct + " MATCH " + constructQuerystr.pattern + " ON " + graphName
        println(query)
        val graph = gcoreApi.runConstructQuery(query, 100)
        val net = UtilsApi.toJsonGraph(graph)
        write(Graph(net.nodes, net.links))
      }else{
        if(ggdlattice.confidence == 1){
          val newGraph = new Graph_N(List(Map("id"->"0", "label"->"No violations")), List.empty)
          write(newGraph)
        }else{
          val g = violatedQuery(ggdlattice)
          val net = UtilsApi.toJsonGraph(g)
          write(Graph(net.nodes, net.links))
        }
      }
    }catch {
      case e: Exception => {
        e.printStackTrace()
        write(Graph_N(List.empty, List.empty))
      }
    }
  }

  def getAnswerGraphPattern(config: String): Future[String] = Future {
    println("gert answer graph in pattern")
    val json = parse(config)
    val params = json.camelizeKeys.extract[ParamsIndex]
    try{
      val ggdlattice = mapGGDLattice.get(params.index)
      val pattern = ggdlattice.get.pattern.asInstanceOf[GraphPattern[String, String]]
      val ag = ggdlattice.get.query.getAnswergraph.asInstanceOf[AnswerGraph[String, String]]
      val nodesList = new mutable.HashSet[Map[String, String]]()
      val edgesList = new mutable.HashSet[Map[String, String]]()
      if(ag.edges.isEmpty){ //if edges is empty it means there are only one node
        val nodeIds = ag.nodes.keySet()
        val iterator = nodeIds.iterator()
        while(iterator.hasNext){
          val variable = iterator.next()
          val label = pattern.getLabelOfThisVariable(variable)
          val vertices = ag.nodes.get(variable).asInstanceOf[AGVertex[String, String]].vertices.keySet()
          val totalOfThisNode = ag.nodes.get(variable).asInstanceOf[AGVertex[String, String]].vertices.size()
          val iteratorNodeIds = vertices.iterator()
          var sizeOfThisNode = 0
          while(iteratorNodeIds.hasNext && sizeOfThisNode < (totalOfThisNode*params.limit)){
            val id = iteratorNodeIds.next()
            nodesList.add(Map("id"-> id, "label" -> label, "variable"-> variable))
            sizeOfThisNode= sizeOfThisNode + 1
          }
        }
        val graph = Graph_N(nodesList.toList, List.empty)
        write(graph)
      }else{
        val edgesIds = ag.edges.keySet()
        val iterator = edgesIds.iterator()
        while (iterator.hasNext){
          val edgeVar = iterator.next()
          val label = pattern.getLabelOfThisVariable(edgeVar)
          val edgesPattern = pattern.getEdgeFromVariable(edgeVar)
          val edges = ag.edges.get(edgeVar).edgeSrcTrg
          val totalOfThiEdges = edges.size()
          val iteratorEdgeIds = edges.keySet().iterator()
          var sizeOfThisEdge = 0
          while(iteratorEdgeIds.hasNext && sizeOfThisEdge < (totalOfThiEdges*params.limit)){
            val id = iteratorEdgeIds.next()
            val tuple = edges.get(id)
            edgesList.add(Map("id"-> id, "label"-> label, "variable"->edgeVar, "source"->tuple.x, "target"->tuple.y))
            nodesList.add(Map("id"->tuple.x, "variable"->edgesPattern.sourceVariable, "label"->edgesPattern.sourceLabel))
            nodesList.add(Map("id"->tuple.y, "variable"->edgesPattern.targetVariable, "label"->edgesPattern.targetLabel))
            sizeOfThisEdge = sizeOfThisEdge + 1
          }
        }
        val graph = Graph_N(nodesList.toList, edgesList.toList)
        write(graph)
      }
    }catch {
      case (e: Exception) => {
        println("could not get answer graph --> number:" + params.index)
        write(Graph_N(List.empty, List.empty))
      }
    }
  }


  def initializeGGDMiner(str: String) : Future[String] = Future {
    val configminer = initializeConfigMinerFromJson(str)
    configminer.printJsonFile("/media/larissacsh/Data/DiscoveryGraphs/minertest.json")
    val pathGraph = databaseFolder + "/" + graphName + "/configgraph.json";
    val rs = minerRunner.GGDMinerRunner_load(configminer, pathGraph)
    val ggds = minerRunner.runGGDMinerFull()
    println("Number of mined GGDs:" + ggds.size())
    //minerRunner.buildSimIndexes()
    if(rs == false){
      "Graph not set correctly"
    }else {
      "Graph set! Please continue your analysis from the other tabs"
    }
  }

  def getSimIndex(str: String) : Future[String] = Future{
    val json = parse(str)
    println(json.values.toString)
    val argsSimIndex = json.camelizeKeys.extract[ArgsSimIndex];
    println(argsSimIndex)
    val SimIndex = this.minerRunner.getClusters(argsSimIndex.label, argsSimIndex.attribute)
    println(SimIndex.keySet())
    val linkedList = new util.LinkedList[Clusters]()
    val iterator = SimIndex.keySet().iterator()
    while(iterator.hasNext) {
      val key = iterator.next()
      //SimIndex.get(key)
      val set = new mutable.HashSet[String]()
      if (SimIndex.get(key).size() > 1) {
        val iterator_set = SimIndex.get(key).iterator()
        while (iterator_set.hasNext) {
          set.add(iterator_set.next().toString)
        }
        linkedList.add(new Clusters(key, set.toSet))
        println("Cluster added!" + key)
      }
    }
    val strl = write(linkedList)
    println(strl)
    strl
  }

  def runSimJoin(simjoin: String) : Future[String] = Future{
    val json = parse(simjoin)
    val joinAttr = json.camelizeKeys.extract[SimJoinRequest]
    println(joinAttr)
    val df = erCommands.runSimJoin(joinAttr.label1, joinAttr.label2, joinAttr.attribute1, joinAttr.attribute2, joinAttr.threshold, graphName).groupBy(joinAttr.attribute1, joinAttr.attribute2).count()
    val results = df.collect().map(x => {
      JoinResult_Count(x.getAs(joinAttr.attribute1).toString, x.getAs(joinAttr.attribute2).toString, x.getAs("count").toString)
    })
    val str = write(results)
    println(str)
    str
  }

  def getUniqueValues(str: String) : Future[String] = Future {
    val json = parse(str)
    val input = json.camelizeKeys.extract[propertyInput]
    val graph = gcoreApi.getGraph(input.graph)
    println(input.property)
    println(input.label)
    val df  = if(graph.vertexSchema.labels.contains(Label(input.label))) {
      val table1 = graph.vertexData.filter(table => table.name.value.equals(input.label)).head
      table1.data.asInstanceOf[DataFrame].select(input.property)
    }else {
      val table1 = graph.edgeData.filter(table => table.name.value.equals(input.label)).head
      table1.data.asInstanceOf[DataFrame].select(input.property)
    }
    var numberOfUniqueValues = df.distinct().count()
    val totalNumberOfValues = df.count()
    val numberOfNulls = df.filter(col(input.property).isNull).count()
    val numberOfEmptyValues = df.filter(col(input.property) === "").count()
    val totalNumberOfNonEmptyNullValues = totalNumberOfValues - (numberOfNulls + numberOfEmptyValues)
    val numberOfDuplicates = //if(numberOfUniqueValues < totalNumberOfNonEmptyNullValues){
      df.groupBy(input.property).count().where(col("count") > 1).select(input.property).count()//.select("count").map(_.get(0).asInstanceOf[Long]).reduce((a,b) => (a+b))
    //}else 0
    val returnValues = Seq(
      UniqueValuesClass("null", numberOfNulls, "#0088FE"),
      UniqueValuesClass("empty", numberOfEmptyValues, "#00C49F"),
      UniqueValuesClass("duplicate-values", numberOfDuplicates, "#FFBB28"),
      UniqueValuesClass("unique-values", numberOfUniqueValues-numberOfDuplicates, "#FF8042")
    )
    val str_re = write(returnValues)
    println(str_re)
    str_re
  }

  def getMinMaxValues(str: String): Future[String] = Future {
    val json = parse(str)
    val input = json.camelizeKeys.extract[propertyInput]
    val graph = gcoreApi.getGraph(input.graph)
    val df  = if(graph.vertexSchema.labels.contains(Label(input.label))) {
      val table1 = graph.vertexData.filter(table => table.name.value.equals(input.label)).head
      table1.data.asInstanceOf[DataFrame].select(input.property)
    }else {
      val table1 = graph.edgeData.filter(table => table.name.value.equals(input.label)).head
      table1.data.asInstanceOf[DataFrame].select(input.property)
    }
    println("Type name"+df.schema(input.property).dataType.typeName)
    if(df.schema(input.property).dataType.typeName.startsWith("string")){
      val df_n = df.withColumn("len_col", length(col(input.property)))
      val min_value = df_n.agg(min("len_col")).head().get(0)
      val max_value = df_n.agg(max("len_col")).head().get(0)
      val avg_value = df_n.agg(avg("len_col")).head().get(0)
      val x = Map("min" -> min_value, "max" -> max_value, "avg" -> avg_value)
      val str_re = write(x)
      println(str_re)
      str_re
    }else{
      val min_value = df.agg(min(input.property)).head().get(0)
      val max_value = df.agg(max(input.property)).head().get(0)
      val avg_value = df.agg(avg(input.property)).head().get(0)
      val x = Map("min" -> min_value, "max" -> max_value, "avg" -> avg_value)
      val str_re = write(x)
      println(str_re)
      str_re
    }
  }


  def getHistData(str: String) : Future[String] = Future {
    val json = parse(str)
    println("Histogram data json" + str)
    val input = json.camelizeKeys.extract[HistInput]
    val graph = gcoreApi.getGraph(graphName)
    val df  = if(graph.vertexSchema.labels.contains(Label(input.label))) {
      graph.vertexData.filter(_.name.value.equals(input.label)).head.data.asInstanceOf[DataFrame].select(input.attribute)
    }else {
      graph.edgeData.filter(_.name.value.equals(input.label)).head.data.asInstanceOf[DataFrame].select(input.attribute)
    }
    val (ranges, counts) = df.rdd.map(x => {
      try{
        x.getDouble(0)
      }catch{
        case e: Exception => x.getLong(0)
      }
    }).histogram(input.bucket)
    println("Ranges::" + ranges.length)
    println("Counts::" + counts.length)
    val array = new ArrayBuffer[HistValues]()
    for(i <- 0 until counts.length){
      val min = ranges(i)
      val max = ranges(i+1)
      val str = min.toString+"-"+max.toString
      array.append(HistValues(str, counts(i)))
    }
    println("Hist data::" + array)
    write(array)
  }

  def getComparisonData(selected: String): Future[String] = Future{
    val json = parse(selected)
    val selectedData = json.camelizeKeys.extract[List[paramComparison]]
    println(selectedData)
    if(!selectedData.apply(0).label.equalsIgnoreCase(selectedData.apply(1).label)){
      println("Not equals")
      write(List.empty)
    }
    val label = selectedData.apply(0).label
    val graph = gcoreApi.getGraph(graphName)
    val attr1 = selectedData.apply(0).attribute
    val attr2 = selectedData.apply(1).attribute
    val df = if(graph.vertexSchema.labels.contains(Label(label))) {
      graph.vertexData.filter(_.name.value.equals(label)).head.data.asInstanceOf[DataFrame].select(col(attr1).as("attr1"),col(attr2).as("attr2"))
        .sample(0.3)
    }else {
      graph.edgeData.filter(_.name.value.equals(label)).head.data.asInstanceOf[DataFrame].select(col(attr1).as("attr1"),col(attr2).as("attr2"))
        .sample(0.3)
    }
    val x = df.map(r => {
      Map("attr1"->r.get(0).toString.toDouble, "attr2"->r.get(1).toString.toDouble)
    }).collect()
    val str = write(x)
    //println(str)
    str
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

  /*def getMatches(info: String) = Future {
    println(info)
    val json = parse(info)
    val params = json.camelizeKeys.extract[PropertiesRequest]
    val graph = erCommands.getGcore().catalog.graph(graphName)
    val nodesList = new mutable.HashSet[String]()
    if(graph.edgeSchema.labels.contains(Label(params.label))){
      val variablesSource = this.current_GGD.getSourceGP.get(0).getVariableOfThisLabel(params.label)
      val variablesTarget = this.current_GGD.getTargetGP.get(0).getVariableOfThisLabel(params.label)
      variablesSource.addAll(variablesTarget)
      val it = variablesSource.iterator()
      while(it.hasNext){
        val variables = it.next()
        if(currentAG_show.nodes.get(variables).nodeExists(params.id)){
          nodesList.add(params.id)
          currentAG_show.nodes.get(variables).vertices.get(params.id).
        }
      }
    }else if (graph.vertexSchema.labels.contains(Label(params.label))){
      val df = graph.vertexData.filter(_.name.value.equalsIgnoreCase(params.label)).head.data.asInstanceOf[DataFrame]
      val row = df.filter(col("id") === params.id).head().getValuesMap(df.columns)
      val str = write(row)
      println(str)
      write(row)
    }else{
      println("Label not found!")
      write(Graph_N(List.empty, List.empty))
    }
  }*/

  def getNodeLinkProperties(info: String) = Future {
    println(info)
    val json = parse(info)
    val params = json.camelizeKeys.extract[PropertiesRequest]
    val graph = erCommands.getGcore().catalog.graph(graphName)
    if(graph.edgeSchema.labels.contains(Label(params.label))){
      val df = graph.edgeData.filter(_.name.value.equalsIgnoreCase(params.label)).head.data.asInstanceOf[DataFrame]
      val row = df.filter(col("id") === params.id).head().getValuesMap(df.columns)
      val str = write(row)
      println(str)
      write(row)
    }else if (graph.vertexSchema.labels.contains(Label(params.label))){
      val df = graph.vertexData.filter(_.name.value.equalsIgnoreCase(params.label)).head.data.asInstanceOf[DataFrame]
      val row = df.filter(col("id") === params.id).head().getValuesMap(df.columns)
      val str = write(row)
      println(str)
      write(row)
    }else{
      println("Label not found!")
      write(Map.empty)
    }
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

  def getNumberLabels(name: String) : Future[String] = Future {
    System.out.println("getting number of labels graph::" + name)
    val graph : PathPropertyGraph = gcoreApi.getGraph(name)
    println("Count of first:" + graph.vertexData.head.data.asInstanceOf[DataFrame].count())
    val vertexCount = graph.vertexData.map(table => {
      new LabelMap(table.name.value, table.data.asInstanceOf[DataFrame].count())
    }).toList
    val edgeCount = graph.edgeData.map(table =>  {
      new LabelMap(table.name.value, table.data.asInstanceOf[DataFrame].count())
    }).toList
    println(vertexCount)
    println(edgeCount)
    val numberResults = numberLabels(vertexCount, edgeCount)
    write(numberResults)
  }

  def setGraph(name: String): Future[String] = Future {
    try{
      val graph = gcoreApi.getGraph(name)
      graphName = name
      erCommands.getGcore().catalog.setDefaultGraph(name)
      "Graph setted correctly";
    }catch {
      case (e: Exception) => {
        "Error on setting the graph data"
      }
    }
  }

  def runGGDMiner() = {
    minerRunner.runGGDMinerFull();
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
          val graphPattern = ggd.unionSourceTarget()
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

  val duration = Duration(60000000000L, "millis")

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
          }        )
      },pathPrefix("graph") {
        concat(
          post {
            path("setGraph") {
              entity(as[String]) { graphName =>
                val msg = setGraph(graphName)
                complete(msg)
              }
            }
          },
          get{
            path("attributePairs"){
              val attributePairs: Future[String] = getAttributePairsFromMiner()
              complete(attributePairs)
            }
          },
          get{
            path("numericalValues"){
              val attributePairs: Future[String] = getNumericalValues()
              complete(attributePairs)
            }
          },
          get{
            path("attributeIndexes"){
              val attributeIndexes: Future[String] = getAttributeIndexesFromMiner()
              complete(attributeIndexes)
            }
          },
          get{
            path("frequentSubgraphs"){
              val subgraphs: Future[String] = getFrequentSubgraphsFromMiner()
              complete(subgraphs)
            }
          },
          get{
            path("ggdsMiner"){
              val ggds: Future[String] = getGGDsFromMiner()
              complete(ggds)
            }
          },
          get{
            path("ggdstats"){
              val ggds: Future[String] = getGGDsStats()
              complete(ggds)
            }
          },
          post {
            withRequestTimeout(durationVis) {
              path("setMiner") {
                entity(as[String]) { configMiner =>
                  val msg = initializeGGDMiner(configMiner)
                  complete(msg)
                }
              }
            }
          },
          post {
            path("comparisondata") {
              entity(as[String]) { selected =>
                val msg = getComparisonData(selected)
                complete(msg)
              }
            }
          },
          post {
            path("nodelinkProperties") {
              entity(as[String]) { info =>
                val msg = getNodeLinkProperties(info)
                complete(msg)
              }
            }
          },
          /*post {
            path("getMatches") {
              entity(as[String]) { info =>
                val msg = getMatches(info)
                complete(msg)
              }
            }
          },*/
          post {
            path("getanswergraph") {
              entity(as[String]) { config =>
                val msg = getAnswerGraphPattern(config)
                complete(msg)
              }
            }
          },
          post {
            withRequestTimeout(duration) {
              path("getggdanswergraph") {
                entity(as[String]) { config =>
                  val msg = getGGDAnswerGraph(config)
                  complete(msg)
                }
              }
            }
          },
          post {
            path("simIndex") {
              entity(as[String]) { simindex =>
                val msg : Future[String] = getSimIndex(simindex)
                complete(msg)
              }
            }
          },
          post {
            withRequestTimeout(durationVis) {
              path("simJoin") {
                entity(as[String]) { simjoin =>
                  val msg: Future[String] = runSimJoin(simjoin)
                  complete(msg)
                }
              }
            }
          },
          post {
            path("uniqueValues") {
              entity(as[String]) { data =>
                val returnData = getUniqueValues(data)
                complete(returnData)
              }
            }
          },
          post {
            path("minMaxValues") {
              entity(as[String]) { data =>
                val returnData = getMinMaxValues(data)
                complete(returnData)
              }
            }
          },
          post {
            path("histogram") {
              entity(as[String]) { data =>
                val returnHistData = getHistData(data)
                complete(returnHistData)
              }
            }
          },
          pathPrefix("number") {
            concat(
              withRequestTimeout(durationVis) {
                path(Segment) { name =>
                  val numberLabel: Future[String] = getNumberLabels(name)
                  complete(numberLabel)
                }
              }
            )
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


