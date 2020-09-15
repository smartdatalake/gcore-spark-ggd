package application

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.catalyst.expressions.Hex
import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization
import schema.PathPropertyGraph
import spark.GraphJsonConfig

import scala.collection.mutable.ArrayBuffer


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
        ) //+= ("label" -> label)
    })
    val edgeJson = edgesTables.map(etable =>{
      val label = etable.name.value
      val data = etable.data.asInstanceOf[DataFrame]
      //val maps =
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
        ) //+= ("label" -> label)
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


}

case class nodeSchema(id: Int, label: String, title: String, property: Seq[String])

case class edgeSchema(label: String, title: String, property: Seq[String], from: Int, to: Int)