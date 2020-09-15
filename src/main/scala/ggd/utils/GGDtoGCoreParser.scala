package ggd.utils

import ggd.ggd.{EdgesPattern, GraphGenDep, GraphPattern, VerticesPattern, gcoreConstructQuery, gcoreQuery}
import org.apache.spark.sql.Row

import scala.collection.mutable.ArrayBuffer

/**
*This object contains utils functions for parsing the GraphPattern object
 * to a G-Core query
*
 */
object GGDtoGCoreParser {

  def parseGCore(gpList : List[GraphPattern]) : String = { //parse to a SELECT
    if(gpList.isEmpty) return ""
    val gcoreQuery: List[gcoreQuery]  = gpList.map( x => x.parseToGCoreSelect())
    var matchClause: String = gcoreQuery.map(_.pattern).mkString(",")
    var selectClause: String = gcoreQuery.map(_.variables).map(_.mkString(",")).mkString(",")
    //println("selectClause::" + selectClause)
    //println("matchClause::" + matchClause)
    val query: String = "SELECT " + selectClause + " MATCH " + matchClause
    //val query : String = "SELECT * MATCH" + matchClause
    query
  }

  def parseGCoreSelectMatch(gpList : List[GraphPattern]) : selectMatch = { //parse to a SELECT
    val gcoreQuery: List[gcoreQuery]  = gpList.map( x => x.parseToGCoreSelect())
    var matchClause: String = gcoreQuery.map(_.pattern).mkString(",")
    var selectClause: String = gcoreQuery.map(_.variables).map(_.mkString(",")).mkString(",")
    //println("selectClause::" + selectClause)
    //println("matchClause::" + matchClause)
    return selectMatch(selectClause, matchClause)
  }

  def parseGCoreConstructPattern(gpList : List[GraphPattern], common: List[String]) : selectMatch = { //parse to a SELECT
    val gcoreQuery: List[gcoreQuery] = gpList.map(x => x.parseToGCoreConstructCommon(common))
    var matchClause: String = gcoreQuery.map(_.pattern).mkString(",")
    var selectClause: String = gcoreQuery.map(_.variables).map(_.mkString(",")).mkString(",")
    //println("selectClause::" + selectClause)
    //println("matchClause::" + matchClause)
    return selectMatch(selectClause, matchClause)
  }

  def parseGCoreTarget(gp: GraphPattern, row: Row, columnNames: Array[String], sourcePatterns : List[GraphPattern]) : String = {
    //parse target gcore query according to source graph pattern
    val sourceVariables : List[String] = sourcePatterns.map( x => x.parseToGCoreSelect()).flatMap(_.variables)
    val targetVariables : List[String] = gp.vertices.map(_.variable) ++ gp.edges.map(_.variable)
    val commonVar: List[String] = commonVariables(sourceVariables, targetVariables)
    var whereClause: ArrayBuffer[String] = ArrayBuffer[String]()
    if(commonVar.isEmpty) return parseGCore(List(gp))
    else{
      //parse using WHERE for common variables
      val selectMatch = parseGCoreSelectMatch(List(gp))
      val commonCol = commonColumns(commonVar, columnNames)
      for(c <- commonCol){
        whereClause += c.replace("$", ".") + "= '" + row.getAs[String](c) + "'"
      }
      val query: String = "SELECT " + selectMatch.selectClause + " MATCH " + selectMatch.matchClause + " WHERE " + whereClause.mkString("AND ")
      return query
    }
  }

  def commonVariables(list1: List[String], list2: List[String]) : List[String] = { //n is small n^2 search ok
    var common: ArrayBuffer[String] = ArrayBuffer[String]()
    for(str1 <- list1){
      for(str2 <- list2){
        if(str1 == str2){
          common += str1
        }
      }
    }
    common.toList
  }

  def commonVariablesGP(source: List[GraphPattern], target: List[GraphPattern]) : List[String] = {
    val sourceVariables : List[String] = source.map( x => x.parseToGCoreSelect()).flatMap(_.variables)
    val targetVariables : List[String] = target.head.vertices.map(_.variable) ++ target.head.edges.map(_.variable)
    val commonVar: List[String] = commonVariables(sourceVariables, targetVariables)
    return commonVar
  }

  def commonColumns(variables: List[String], columns: Array[String]) : List[String] = { //first version
    var commonColumns : ArrayBuffer[String] = ArrayBuffer[String]()
    for(str1 <- variables){
      for(str2 <- columns){
        if(str2.startsWith(str1)) commonColumns += str2
      }
    }
    return commonColumns.toList
  }

  def createPattern(pattern: GraphPattern, commonVars: List[String]): GraphPattern = {
    val vertices: ArrayBuffer[VerticesPattern] = new ArrayBuffer[VerticesPattern]()
    val edges: ArrayBuffer[EdgesPattern] = new ArrayBuffer[EdgesPattern]()
    for(v <- pattern.vertices){
      if(commonVars.contains(v.variable)){
        vertices += v
      }
    }
    for(e <- pattern.edges){
      if(commonVars.contains(e.variable)){
        edges += e
      }
    }
    return GraphPattern(pattern.name, vertices.toList, edges.toList)
  }

  def partialPatternRow(patternOrig: GraphPattern, commonVar: List[String], row: Row): matchOptional ={
    val pattern: GraphPattern = createPattern(patternOrig, commonVar)
    val fullPattern: selectMatch = parseGCoreSelectMatch(List(patternOrig))
    val partialPattern: selectMatch = parseGCoreSelectMatch(List(pattern))
    var whereClause: ArrayBuffer[String] = new ArrayBuffer[String]()
    for(variable <- commonVar){
      whereClause += " " + variable + ".id=" + row.getAs[String](variable+"$id")
    }
    var whereClauseStr : String = ""
    if(!whereClause.isEmpty){
      whereClauseStr = whereClause.mkString(" AND ")
    }
    val matchclause: String = partialPattern.matchClause
    val optional: String = fullPattern.matchClause

    return matchOptional(matchclause, optional, whereClauseStr)
  }

  def partialPattern(patternOrig: GraphPattern, commonVar: List[String]): matchOptional ={
    val pattern: GraphPattern = createPattern(patternOrig, commonVar)
    val dif : GraphPattern = diff(patternOrig, pattern)
    val fullPattern: selectMatch = parseGCoreSelectMatch(List(patternOrig))
    val matchPattern: selectMatch = parseGCoreSelectMatch(List(pattern))
    val partialPattern: selectMatch = parseGCoreSelectMatch(List(dif))
    var whereClause: ArrayBuffer[String] = new ArrayBuffer[String]()
    var whereClauseStr : String = ""
    //val matchclause: String = partialPattern.matchClause
    //val optional: String = fullPattern.matchClause
    val matchclause = matchPattern.matchClause
    val optional = partialPattern.matchClause
    return matchOptional(matchclause, optional, whereClauseStr)
  }

  def isin(pattern: GraphPattern, pattern1: EdgesPattern): Boolean = {
    for(e <- pattern.edges){
      if(e.variable == pattern1.variable && e.fromVariable == pattern1.fromVariable && e.toVariable == pattern1.toVariable) return true
    }
    return false
  }

  def diff(orig: GraphPattern, commonVar: GraphPattern) : GraphPattern = {
    val k : ArrayBuffer[EdgesPattern] = new ArrayBuffer[EdgesPattern]()
    val usedVars: ArrayBuffer[String] = new ArrayBuffer[String]()
    val vertex: ArrayBuffer[VerticesPattern] = new ArrayBuffer[VerticesPattern]()
    for(e <- orig.edges){
      if(!isin(commonVar, e)){
        k += e
      }
    }
   for(diffEdges <- k){
      if(!usedVars.contains(diffEdges.fromVariable)) usedVars += diffEdges.fromVariable
      if(!usedVars.contains(diffEdges.toVariable)) usedVars += diffEdges.toVariable
    }
    val a = orig.vertices.map(_.variable).diff(commonVar.vertices.map(_.variable))
    val variables = a ++ usedVars
    for(vertices <- orig.vertices){
      if(variables.contains(vertices.variable)) vertex += vertices
    }
    return new GraphPattern(orig.name , vertex.toList, k.toList)
  }

}

case class selectMatch(selectClause: String, matchClause: String)

case class constructMatch(constructClause: String, matchClause: String )

case class matchOptional(matchClause: String, optionalClause: String, whereClause: String)