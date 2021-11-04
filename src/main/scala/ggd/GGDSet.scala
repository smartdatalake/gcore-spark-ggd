package ggd

import java.io.{FileWriter, Writer}
import java.nio.file.{Path, Paths}

import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization.write

import scala.collection.mutable.ArrayBuffer
import scala.io.Source

class GGDSet {

  //set of ggds
  var AllGGDs : Seq[GraphGenDep] = Seq.empty

  //load all ggds
  def loadGGDs(path: String) : Unit = {
    var ggds = new ArrayBuffer[GraphGenDep]()
    var ggdAllConfigFile : GGDAllConfig = parseJsonGGDAll(path)
    for(ggdFile <- ggdAllConfigFile.ggdsFiles){
      val ggdRead: GraphGenDep = new GraphGenDep(ggdFile)
      ggds += ggdRead
      //print(ggds.size)
      //AllGGDs :+ ggdRead
      //print(AllGGDs.size)
    }
    println("Number of GGDs:"  + ggds.size)
    AllGGDs = ggds.toSeq
  }

  def parseJsonGGDAll(path: String) : GGDAllConfig = {
    //parse json file
    val loadedFile = Source.fromFile(path).mkString
    val json = parse(loadedFile)
    implicit val formats = DefaultFormats
    json.camelizeKeys.extract[GGDAllConfig]
  }

  def parseGGDJsonApi(list: String): Seq[GraphGenDep] = {
    implicit val format = DefaultFormats
    val json = parse(list)
    val ggd: List[GGD] = json.camelizeKeys.extract[List[GGD]]
    println("parse GGDJson Api::" + ggd.size)
    val seq = ggd.map(g => new GraphGenDep(g.sourceGP, g.targetGP, g.sourceCons, g.targetCons)).toSeq
    seq
  }

  def parseGGDJsonStringToGGD(list: String): Seq[GraphGenDep] = {
    implicit val format = DefaultFormats
    val json = parse(list)
    val ggd: List[GGDString] = json.camelizeKeys.extract[List[GGDString]]
    println("parse GGDJson Api::" + ggd.size)
    val seq = ggd.map(g => new GraphGenDep(g.sourceGP, g.targetGP, g.sourceCons.map(_.toConstraintClass()), g.targetCons.map(_.toConstraintClass()))).toSeq
    seq
  }

  def toJsonString(): String = {
    implicit val format = DefaultFormats
    val listGGDs = AllGGDs.map(g => g.GGD())
    write(listGGDs)
  }

  //path and config file are both in the same directory
  def saveJsonConfig(path: String, names: Seq[String]): Unit = {
    val config = new GGDAllConfig(path, names)
    implicit val format = DefaultFormats
    val sgp: Writer = new FileWriter(path+"config.json")
    sgp.write(write(config))
    sgp.close()
  }

}

case class GGDAllConfig(path: String,
                        names: Seq[String]){

  assert(path.nonEmpty, "No path for ggd files")
  assert(names.nonEmpty, "No ggds names on this file")

  val ggdsFiles: Seq[Path] = names.map(Paths.get(path,_))
}
