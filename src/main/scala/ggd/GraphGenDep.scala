package ggd

import java.io.{FileWriter, Writer}
import java.nio.file.Path

import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization.write

import scala.collection.mutable.ArrayBuffer
import scala.io.Source

class GraphGenDep {
  var name: String = "-"
  var sourceGP : List[GraphPattern] = List.empty[GraphPattern]
  var targetGP: List[GraphPattern] = List.empty[GraphPattern]
  var sourceCons: List[Constraint] = List.empty[Constraint]
  var targetCons: List[Constraint] = List.empty[Constraint]
  implicit val format = DefaultFormats


  def this(file: Path){
    this()
    loadGGDOneFile(file)
  }

  def this(source: List[GraphPattern], target: List[GraphPattern], sourcon: List[Constraint], targetcon : List[Constraint]){
    this()
    sourceGP = source
    targetGP = target
    sourceCons = sourcon
    targetCons = targetcon
  }

  def loadGGD(ggdFile: Path): Unit ={ //to load ggds separated in several files
    val sourceFile = Source.fromFile(ggdFile+"source.json").mkString
    val targetFile = Source.fromFile(ggdFile+"target.json").mkString
    val constraintFile = Source.fromFile(ggdFile+"constraint.json").mkString
    val constraintFileTarget = Source.fromFile(ggdFile+"constraintTarget.json").mkString
    //print(sourceFile)
    name = ggdFile.getFileName.toString
    sourceGP = loadGraphJSON(sourceFile)
    targetGP = loadGraphJSON(targetFile)
    sourceCons = loadConstJSON(constraintFile)
    targetCons = loadConstJSON(constraintFileTarget)
  }

  def loadGGDOneFile(ggdFile: Path): Unit ={
    val sourceFile = Source.fromFile(ggdFile+".json").mkString
    name = ggdFile.getFileName.toString
    loadGraphGenDepJSON(sourceFile)
  }

  def loadGraphGenDepJSON(str: String): Unit = {
    val json = parse(str)
    val ggd: GGD = json.camelizeKeys.extract[GGD] //case class for GGDs -> TODO:change to just one class
    sourceGP = ggd.sourceGP
    sourceCons = ggd.sourceCons
    targetGP = ggd.targetGP
    targetCons = ggd.targetCons
  }

  def loadGraphJSON(str: String) : List[GraphPattern] = {
    val json = parse(str)
    val x:List[GraphPattern] = json.camelizeKeys.extract[List[GraphPattern]]
    return x
  }

  def saveJSON(path: String, name: String): Unit = {
    val sgp: Writer = new FileWriter(path+name+"source.json")
    sgp.write(write(sourceGP))
    sgp.close()
    val tgp: Writer = new FileWriter(path+name+"target.json")
    tgp.write(write(targetGP))
    tgp.close()
    val cgp: Writer = new FileWriter(path+name+"constraint.json")
    cgp.write(write(sourceCons))
    cgp.close()
    val tc: Writer = new FileWriter(path+name+"constraintTarget.json")
    if(write(targetCons).isEmpty) tc.write("[]")
    else tc.write(write(targetCons))
  }

  def saveJSONOneFile(path: String, name: String) : Unit = {
    val ggd = new GGD(sourceGP, sourceCons, targetGP, targetCons)
    val sgp: Writer = new FileWriter(path+name+".json")
    sgp.write(write(ggd))
    sgp.close()
  }

  def loadConstJSON(str: String) : List[Constraint] = {
    val json = parse(str)
    val x:List[Constraint] = json.camelizeKeys.extract[List[Constraint]]
    return x
  }

  def prettyPrint() : Unit = {
    val ggd = new GGD(sourceGP, sourceCons, targetGP, targetCons)
    val ggjson = write(ggd)
    println(ggjson)
  }

  def jsonString() : String = {
    val ggd = new GGD(sourceGP, sourceCons, targetGP, targetCons)
    write(ggd)
  }

  def GGD() : GGD = {
    new GGD(sourceGP, sourceCons, targetGP, targetCons)
  }

}

//GGD case class for ggd json reading
case class GGD(sourceGP: List[GraphPattern],
               sourceCons: List[Constraint],
               targetGP: List[GraphPattern],
               targetCons: List[Constraint])

//transform to case class
case class GraphPattern(
                         name: String ,
                         vertices: List[VerticesPattern] ,
                         edges: List[EdgesPattern]

                       ){

  def parseToGCoreConstruct() : gcoreConstructQuery = {
    if(edges.isEmpty) return gcoreConstructQuery("(" + vertices.head.variable + ":" + vertices.head.label + ")", name)
    else return gcoreConstructQuery(parsePattern(), name)
  }

  def parseToGCoreSelect() : gcoreQuery = {
    if(edges.isEmpty){
      if(vertices.size ==1){
        val v = vertices.head
        return gcoreQuery(List[String](v.variable), "(" + v.variable + ":" + v.label + ") ON " + name)
      }else{
        val allVertices : List[String] = vertices.map(v => v.variable + ":" + v.label)
        return gcoreQuery(vertices.map(_.variable), "(" + allVertices.mkString(") ON " + name + " ,(") + ") ON " + name)
      }

    }else {
      val gp: String = parsePattern()
      val variables: List[String] = vertices.map(_.variable) ++ edges.map(_.variable)
      return gcoreQuery(variables, gp + " ON " + name)
    }
  }

  def parseToGCoreConstructCommon(common : List[String]): gcoreQuery = {
    if(edges.isEmpty){
      if(vertices.size ==1){
        val v = vertices.head
        if(common.contains(v.variable)){
          gcoreQuery(List[String](v.variable), "(" + v.variable + ")")
        }else return gcoreQuery(List[String](v.variable), "(" + v.variable + ":" + v.label + ")")
      }else{
        val allVertices : List[String] = vertices.map(v =>
          if(common.contains(v.variable)){
            v.variable
          }else v.variable + ":" + v.label)
        return gcoreQuery(vertices.map(_.variable), "(" + allVertices.mkString("),(") + ")")
      }

    }else {
      val gp: ArrayBuffer[String] = parsePatternConstruct(common)
      val variables: List[String] = vertices.map(_.variable) ++ edges.map(_.variable)
      return gcoreQuery(variables, gp.mkString(","))
    }
  }

  def parsePattern(): String = {
    val allVertices: ArrayBuffer[String] = new ArrayBuffer[String]()
    var patternEdges : String = ""
    if(edges.size == 1){
      val e = edges.head
      val FromLabel: String = variableLabel(e.fromVariable)
      val ToLabel: String = variableLabel(e.toVariable)
      e.fromLabel = FromLabel
      e.toLabel = ToLabel
      patternEdges = "(" + e.fromVariable + ":" + FromLabel + ")-[" + e.variable + ":" + e.label + "]->(" + e.toVariable + ":" + e.toLabel + ")";
    //}else if (edges.size == 2){
      if(!allVertices.contains(e.fromVariable)) allVertices += e.fromVariable
      if(!allVertices.contains(e.toVariable)) allVertices += e.toVariable
    }else{
      for(e <- edges){
        val FromLabel: String = variableLabel(e.fromVariable)
        val ToLabel: String = variableLabel(e.toVariable)
        e.fromLabel = FromLabel
        e.toLabel = ToLabel
        if(patternEdges == "")
        patternEdges = "(" + e.fromVariable + ":" + FromLabel + ")-[" + e.variable + ":" + e.label + "]->(" + e.toVariable + ":" + e.toLabel + ")"
        else{
          patternEdges += ", (" + e.fromVariable + ":" + FromLabel + ")-[" + e.variable + ":" + e.label + "]->(" + e.toVariable + ":" + e.toLabel + ")"
        }
        if(!allVertices.contains(e.fromVariable)) allVertices += e.fromVariable
        if(!allVertices.contains(e.toVariable)) allVertices += e.toVariable
      }
    }
    val verticesVariables = vertices.map(x=> x.variable)
    val alone = verticesVariables.diff(allVertices)
    if(alone.isEmpty) return patternEdges
    else{
      for(v <- alone){
        patternEdges += (", (" + v + ":" + variableLabel(v) + ")")
      }
    }
    return patternEdges
  }

  def parsePatternConstruct(common: List[String]): ArrayBuffer[String] = {
    var patternEdges : ArrayBuffer[String] = new ArrayBuffer[String]()
   for(e <- edges) {
     var from, edge, to : String = ""
     val e = edges.head
     val FromLabel: String = variableLabel(e.fromVariable)
     val ToLabel: String = variableLabel(e.toVariable)
     e.fromLabel = FromLabel
     e.toLabel = ToLabel
     if(common.contains(e.fromVariable)){
       from = e.fromVariable
     }else from = e.fromVariable + ":" + FromLabel
     if(common.contains(e.variable)){
       edge = e.variable
     }else edge = e.variable + ":" + e.label
     if(common.contains(e.toVariable)){
       to = e.toVariable
     }else e.toVariable + ":" + ToLabel
     patternEdges += "(" + from + ")-[" + edge +"]->(" + to + ")";
   }
    return patternEdges
  }

  def variableLabel(str: String): String = {
    for(v <- vertices){
      if(v.variable == str){
        return v.label
      }
    }
    return "not found"
  }


}

case class VerticesPattern(
                            label: String ,
                            variable: String
                          )


case class EdgesPattern(
                         label: String ,
                         variable: String ,
                         fromVariable: String ,
                         toVariable: String
                       ){
  var fromLabel: String = ""
  var toLabel: String = ""
}

case class Constraint (
                        distance: String,
                        var1 : String,
                        var2 : String,
                        attr1: String,
                        attr2: String,
                        threshold: Double,
                        operator:String
                      )


case class gcoreQuery(
                     variables : List[String],
                     pattern : String
                     )

case class gcoreConstructQuery(
                              pattern: String,
                              graphName: String
                              )
