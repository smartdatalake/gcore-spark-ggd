package application

import algebra.expressions
import algebra.expressions.Label
import ggd.discovery.clustering.DistanceMap
import ggd.{GGD, GraphPattern}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.catalyst.expressions.Hex
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization
import schema.EntitySchema.LabelRestrictionMap
import schema.{PathPropertyGraph, SchemaMap, Table}
import spark.{GraphJsonConfig, SparkGraph}

import scala.collection.mutable.ArrayBuffer
import scala.io.Source


object UtilsApi {

  case class GraphNetworkx(nodes: String, links: String)
  implicit val formats = DefaultFormats

  def toJsonGraph(propertyGraph: PathPropertyGraph): GraphNetworkx = {
    val graphName = propertyGraph.graphName
    val nodesTables = propertyGraph.vertexData
    val edgesTables = propertyGraph.edgeData
    val nodeJson = nodesTables.map(vtable =>{
      val label = vtable.name.value
      val data = vtable.data.asInstanceOf[DataFrame]
      //val maps =
      data
        .collect
        .map(
          row => data
            .columns
            .foldLeft(Map.empty[String, Any])
            (
              (acc, item) => acc + (item -> row.getAs[Any](item))
            )

        ).map(
        node => node ++ Map("label" -> label)
      )
    })
    val edgeJson = edgesTables.map(etable =>{
      val label = etable.name.value
      val data = etable.data.asInstanceOf[DataFrame]
      data
        .collect
        .map(
          row => data
            .columns
            .foldLeft(Map.empty[String, Any])
            (
              (acc, item) =>{
                if(item.equalsIgnoreCase("fromid")){
                  acc + ("from" -> row.getAs[Any](item))
                }else if(item.equalsIgnoreCase("toid")){
                  acc + ("to" -> row.getAs[Any](item))
                }else{
                  acc + (item -> row.getAs[Any](item))
                }
              }
            )
        ).map(
        node => node ++ Map("label" -> label)
      )
    })
    val flatNodes = nodeJson.flatten
    val flatEdges = edgeJson.flatten
    val nodes = Serialization.write(flatNodes)
    val edges = Serialization.write(flatEdges)
    return GraphNetworkx(nodes,edges)
  }

  def toJsonSchema(graph: PathPropertyGraph) : GraphNetworkx = {
    val nodesSchema = graph.vertexSchema
    val edgesSchema = graph.edgeSchema
    val edgeRest = graph.edgeRestrictions
    var i: Int = 0
    val nodes = nodesSchema.labels.map(label => {
      i = i+1
      new nodeSchema(i, label.value, nodesSchema.properties(label).map(p => p.name).mkString(" ; "), nodesSchema.properties(label).map(p => p.name))
    })
    val mapId = nodes.map(node => (node.label, node.id)).toMap
    val edges = edgesSchema.labels.map(label => {
      val rest = edgeRest.get(label).get
      new edgeSchema(label.value, edgesSchema.properties(label).map(p => p.name).mkString(" ; "), edgesSchema.properties(label).map(p => p.name), mapId.get(rest._1.value).get, mapId.get(rest._2.value).get)
    })
    val nodeJson = Serialization.write(nodes)
    val edgesJson = Serialization.write(edges)
    println(nodeJson)
    GraphNetworkx(nodeJson, edgesJson)
  }

  def toJsonGraphFromPattern(df: DataFrame, pattern: GraphPattern): GraphNetworkx = { //extract graph link using the graph pattern as reference
    val cols = df.columns
    val nodesJson = pattern.vertices.map(vertex => {
      val colsToSelect = cols.filter(_.startsWith(vertex.variable + '$')) ++ Seq("cluster", "level", "rowId")
      val data = df.select(colsToSelect.head, colsToSelect.tail:_*).withColumnRenamed(vertex.variable + "$id", "id")
      data
        .collect
        .map(
          row => data
            .columns
            .foldLeft(Map.empty[String, Any])
            (
              (acc, item) => acc + (item -> row.getAs[Any](item))
            )

        ).map(
        node => node ++ Map("label" -> vertex.label)
      ) //+= ("label" -> label)
    })
    val edgesJson = pattern.edges.map(edge => {
      val colsToSelect = cols.filter(_.startsWith(edge.variable + '$')) ++ Seq("cluster", "level", "rowId")
      val data = df.select(colsToSelect.head, colsToSelect.tail:_*).withColumnRenamed(edge.variable + "$id", "id")
      data
        .collect
        .map(
          row => data
            .columns
            .foldLeft(Map.empty[String, Any])
            (
              (acc, item) =>{
                if(item.equalsIgnoreCase(edge.fromVariable +"$fromid")){
                  acc + ("from" -> row.getAs[Any](item))
                }else if(item.equalsIgnoreCase(edge.toVariable + "$toid")){
                  acc + ("to" -> row.getAs[Any](item))
                }else{
                  acc + (item -> row.getAs[Any](item))
                }
              }
            )
        ).map(
        node => node ++ Map("label" -> edge.label)
      ) //+= ("label" -> label)
    })
    val flatNodes = nodesJson.flatten
    val flatEdges = edgesJson.flatten
    val nodes = Serialization.write(flatNodes)
    val edges = Serialization.write(flatEdges)
    return GraphNetworkx(nodes,edges)
  }


  def toJsonGraphFromPatternD(df: DataFrame, pattern: GraphPattern): GraphNetworkx = { //extract graph link using the graph pattern as reference
    val cols = df.columns
    val nodesTable = pattern.vertices.map(vertex => {
      val colsToSelect = cols.filter(_.startsWith(vertex.variable + '$')) ++ Seq("cluster", "level")
      val data = df.select(colsToSelect.head, colsToSelect.tail:_*)
      new Table(Label(vertex.label), data)
    }).toSeq
    val edgesTable = pattern.edges.map(edge => {
      val colsToSelect = cols.filter(_.startsWith(edge.variable + '$')) ++ Seq("cluster", "level")
      val data = df.select(colsToSelect.head, colsToSelect.tail:_*)
      new Table(Label(edge.label), data)
    }).toSeq
    val edgeMap = pattern.edges.map(edge => {
      (Label(edge.label) -> (Label(edge.fromLabel), Label(edge.toLabel)))
    }).toMap
    val patternGraph = new SparkGraph {
      override var graphName: String = "vis_result"

      override def vertexData: Seq[Table[DataFrame]] = nodesTable

      override def edgeData: Seq[Table[DataFrame]] = edgesTable

      override def pathData: Seq[Table[DataFrame]] = Seq.empty

      override def edgeRestrictions: LabelRestrictionMap = SchemaMap(edgeMap)

      override def storedPathRestrictions: LabelRestrictionMap = SchemaMap.empty
    }
    return toJsonGraph(patternGraph)
  }

 /* def toJsonSimMap(simap : Map[Long, Array[DistanceMap]]) : String = {
    Serialization.write(simap)
  }*/

  def toJsonCondensedSimMap(simap: Array[Array[Double]]): String = {
    Serialization.write(simap)
  }

  def getKeysfromJsonFile(filePath: String): List[ApiKeys] = {
    val apiFile = Source.fromFile(filePath).mkString
    val json = parse(apiFile)
    val apiKeysList: List[ApiKeys] = json.camelizeKeys.extract[List[ApiKeys]]
    apiKeysList
  }

}

case class ApiKeys(key: String)

case class nodeSchema(id: Int, label: String, title: String, property: Seq[String])

case class edgeSchema(label: String, title: String, property: Seq[String], from: Int, to: Int)