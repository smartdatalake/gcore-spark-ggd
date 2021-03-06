/*
 * gcore-spark is the reference implementation of the G-CORE graph query
 * language by the Linked Data Benchmark Council (LDBC) - ldbcouncil.org
 *
 * The copyrights of the source code in this file belong to:
 * - Universidad de Talca (2018)
 *
 * This software is released in open source under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package spark

import java.io.{BufferedOutputStream, File, FileOutputStream}
import java.util

import org.apache.spark.sql.DataFrame
import org.json4s.NoTypeHints
import org.json4s.jackson.Serialization
import schema.PathPropertyGraph
import org.json4s.jackson.Serialization.{read, write}


case class SaveGraph() {

  def saveJsonGraph(graph: PathPropertyGraph, graph_root_dir: String, overwrite: Boolean = false): Unit =
  {

    var graphName= graph.graphName
    var graphDirectory = graph_root_dir+"/"+graphName
    var vertex_labels :List[String] = List[String]()
    graph.vertexSchema.labels.foreach(label =>  {vertex_labels = (label.value) :: vertex_labels})
    var edge_labels :List[String] = List[String]()
    graph.edgeSchema.labels.foreach(label =>   {edge_labels = (label.value) :: edge_labels})
    var path_labels :List[String] = List[String]()
    graph.pathSchema.labels.foreach(label =>   {path_labels = (label.value) :: path_labels})
    var edge_restrictions :List[ConnectionRestriction] = List[ConnectionRestriction]()
    graph.edgeRestrictions.map.foreach(rest => {edge_restrictions = new ConnectionRestriction(rest._1.value,rest._2._1.value,rest._2._2.value ):: edge_restrictions})
    var path_restrictions :List[ConnectionRestriction] = List[ConnectionRestriction]()
    graph.storedPathRestrictions.map.foreach(rest =>  {path_restrictions = new ConnectionRestriction(rest._1.value,rest._2._1.value,rest._2._2.value) :: path_restrictions})

    var json = new GraphJsonConfig(graphName,graphDirectory,vertex_labels,edge_labels,path_labels, edge_restrictions,path_restrictions)
    implicit val formats = Serialization.formats(NoTypeHints)



    val directory = new File(graphDirectory)
    if (!directory.exists) {
      directory.mkdir
    }

    val outputStream = new BufferedOutputStream(new FileOutputStream(graphDirectory+File.separator+"config.json"))
    write(json,outputStream)
    outputStream.close()

    if(overwrite){
      graph.vertexData foreach(x => {
        x.data.asInstanceOf[DataFrame].repartition(1).write.mode("overwrite").json(graphDirectory +File.separator+x.name.value)
      })
      graph.edgeData foreach(x => {x.data.asInstanceOf[DataFrame].repartition(1).write.mode("overwrite").json(graphDirectory +File.separator+ x.name.value)})
      graph.pathData foreach(x => {x.data.asInstanceOf[DataFrame].repartition(1).write.mode("overwrite").json(graphDirectory +File.separator+ x.name.value)})
    }else{
      graph.vertexData foreach(x => {
        x.data.asInstanceOf[DataFrame].repartition(1).write.json(graphDirectory +File.separator+x.name.value)
      })
      graph.edgeData foreach(x => {x.data.asInstanceOf[DataFrame].repartition(1).write.json(graphDirectory +File.separator+ x.name.value)})
      graph.pathData foreach(x => {x.data.asInstanceOf[DataFrame].repartition(1).write.json(graphDirectory +File.separator+ x.name.value)})
    }

  }

  def saveConfigFileOnly(graph: PathPropertyGraph, graph_root_dir: String): Unit = {
    var graphName= graph.graphName
    var graphDirectory = graph_root_dir+"/"+graphName
    var vertex_labels :List[String] = List[String]()
    graph.vertexSchema.labels.foreach(label =>  {vertex_labels = (label.value) :: vertex_labels})
    var edge_labels :List[String] = List[String]()
    graph.edgeSchema.labels.foreach(label =>   {edge_labels = (label.value) :: edge_labels})
    var path_labels :List[String] = List[String]()
    graph.pathSchema.labels.foreach(label =>   {path_labels = (label.value) :: path_labels})
    var edge_restrictions :List[ConnectionRestriction] = List[ConnectionRestriction]()
    graph.edgeRestrictions.map.foreach(rest => {edge_restrictions = new ConnectionRestriction(rest._1.value,rest._2._1.value,rest._2._2.value ):: edge_restrictions})
    var path_restrictions :List[ConnectionRestriction] = List[ConnectionRestriction]()
    graph.storedPathRestrictions.map.foreach(rest =>  {path_restrictions = new ConnectionRestriction(rest._1.value,rest._2._1.value,rest._2._2.value) :: path_restrictions})

    var json = new GraphJsonConfig(graphName,graphDirectory,vertex_labels,edge_labels,path_labels, edge_restrictions,path_restrictions)
    implicit val formats = Serialization.formats(NoTypeHints)



    val directory = new File(graphDirectory)
    if (!directory.exists) {
      directory.mkdir
    }

    val outputStream = new BufferedOutputStream(new FileOutputStream(graphDirectory+File.separator+"config.json"))
    write(json,outputStream)
    outputStream.close()
  }



  def getJsonSchema(graph: PathPropertyGraph) : GraphJsonConfig = {
    var graphName= graph.graphName
    var graphDirectory = "/"+graphName
    var vertex_labels :List[String] = List[String]()
    graph.vertexSchema.labels.foreach(label =>  {vertex_labels = (label.value) :: vertex_labels})
    var edge_labels :List[String] = List[String]()
    graph.edgeSchema.labels.foreach(label =>   {edge_labels = (label.value) :: edge_labels})
    var path_labels :List[String] = List[String]()
    graph.pathSchema.labels.foreach(label =>   {path_labels = (label.value) :: path_labels})
    var edge_restrictions :List[ConnectionRestriction] = List[ConnectionRestriction]()
    graph.edgeRestrictions.map.foreach(rest => {edge_restrictions = new ConnectionRestriction(rest._1.value,rest._2._1.value,rest._2._2.value ):: edge_restrictions})
    var path_restrictions :List[ConnectionRestriction] = List[ConnectionRestriction]()
    graph.storedPathRestrictions.map.foreach(rest =>  {path_restrictions = new ConnectionRestriction(rest._1.value,rest._2._1.value,rest._2._2.value) :: path_restrictions})

    var json = new GraphJsonConfig(graphName,graphDirectory,vertex_labels,edge_labels,path_labels, edge_restrictions,path_restrictions)
    implicit val formats = Serialization.formats(NoTypeHints)
    json
    // write(json)
  }

  def saveParquetGraph(graph: PathPropertyGraph, graph_root_dir: String): Unit =
  {

    var graphName= graph.graphName
    var graphDirectory = graph_root_dir+"/"+graphName
    var vertex_labels :List[String] = List[String]()
    graph.vertexSchema.labels.foreach(label =>  {vertex_labels = (label.value) :: vertex_labels})
    var edge_labels :List[String] = List[String]()
    graph.edgeSchema.labels.foreach(label =>   {edge_labels = (label.value) :: edge_labels})
    var path_labels :List[String] = List[String]()
    graph.pathSchema.labels.foreach(label =>   {path_labels = (label.value) :: path_labels})
    var edge_restrictions :List[ConnectionRestriction] = List[ConnectionRestriction]()
    graph.edgeRestrictions.map.foreach(rest => {edge_restrictions = new ConnectionRestriction(rest._1.value,rest._2._1.value,rest._2._2.value ):: edge_restrictions})
    var path_restrictions :List[ConnectionRestriction] = List[ConnectionRestriction]()
    graph.storedPathRestrictions.map.foreach(rest =>  {path_restrictions = new ConnectionRestriction(rest._1.value,rest._2._1.value,rest._2._2.value) :: path_restrictions})

    var json = new GraphJsonConfig(graphName,graphDirectory,vertex_labels,edge_labels,path_labels, edge_restrictions,path_restrictions)
    implicit val formats = Serialization.formats(NoTypeHints)



    val directory = new File(graphDirectory)
    if (!directory.exists) {
      directory.mkdir
    }

    val outputStream = new BufferedOutputStream(new FileOutputStream(graphDirectory+File.separator+"config.json"))
    write(json,outputStream)
    outputStream.close()

    graph.vertexData foreach(x => {
      x.data.asInstanceOf[DataFrame].repartition(1).write.parquet(graphDirectory +File.separator+x.name.value)
    })
    graph.edgeData foreach(x => {x.data.asInstanceOf[DataFrame].repartition(1).write.parquet(graphDirectory +File.separator+ x.name.value)})
    graph.pathData foreach(x => {x.data.asInstanceOf[DataFrame].repartition(1).write.parquet(graphDirectory +File.separator+ x.name.value)})


  }

}
