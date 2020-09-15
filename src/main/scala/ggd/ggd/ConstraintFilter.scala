package ggd.ggd

import org.apache.spark.api.java.function.FilterFunction
import org.apache.spark.sql.Row

case class ConstraintFilter(sourceCons: List[Constraint]) extends FilterFunction[Row] {

  override def call(row1: Row): Boolean = {
    for(cons <- sourceCons){
      var x = checkConstraint(row1, cons)
      if(!x) return false
    }
    return true
  }

  def checkConstraint(row1: Row, cons : Constraint) : Boolean = {
    var distResult: Double = 0
    //get attribute values from each df
    val dist: distanceFunctions = new distanceFunctions;
    val firstValue: String = row1.getAs(cons.var1+"$"+cons.attr1).toString()//row1.getAs[String](cons.var1+"$"+cons.attr1)
    var secondValue: String = ""
    if(cons.var2 == "") secondValue = cons.attr2
    else secondValue = row1.getAs(cons.var2+"$"+cons.attr2).toString//row1.getAs[String](cons.var2+"$"+cons.attr2)
    cons.distance match {
      case "edit" => distResult = dist.editDistance(firstValue,secondValue) //value.variable.attr1
      case "euclidean" => distResult = dist.EuclideanDistance(dist.getArray(firstValue),dist.getArray(secondValue))
      case "diff" => distResult = dist.Diff(dist.getArray(firstValue),dist.getArray(secondValue))
      case "equal" =>  if(row1.getAs(cons.var1+"$id").toString == row1.getAs(cons.var2+"$id").toString) return true else return false
      //if(row1.getAs[String](cons.var1+"$id") == row1.getAs[String](cons.var2+"$id")) return true else return false
      case "different" => if(row1.getAs(cons.var1+"$id").toString == row1.getAs(cons.var2+"$id").toString) return false else return true
      // if(row1.getAs[String](cons.var1+"$id") == row1.getAs[String](cons.var2+"$id")) return false else return true
    }
    cons.operator match{
      case "<=" => return(distResult <= cons.threshold)
      case "=" => return(distResult == cons.threshold)
      case ">=" => return(distResult >= cons.threshold)
      case "<" => return(distResult < cons.threshold)
      case ">" => return(distResult > cons.threshold)
      case "!=" => return(distResult != cons.threshold)
    } //returns true if satisfiable/ false if not
  }

}

case class TargetConstraintFilter(targetCons: List[Constraint], sourceGP: List[GraphPattern]) extends FilterFunction[Row] {

  override def call(row: Row) : Boolean = {
    for(cons <- targetCons){
      var x = checkConstraintTarget(cons, row, sourceGP)
      if(!x) return false
    }
    return true
  }

  def checkConstraintTarget(cons: Constraint, row1: Row, sourceGP: List[GraphPattern]): Boolean = {
    var distResult: Double = 0
    val dist: distanceFunctions = new distanceFunctions;
    var firstValue,secondValue : String = ""
    val sourceVars = sourceGP.map(_.edges.map(_.variable)) ++ sourceGP.map(_.vertices.map(_.variable)) //transfrom to flat map
    val variablesInSourceGP = sourceVars.flatten
    //var variableInTargetGP = ggd.targetGP.map(_.edges.map(_.variable)) ++ ggd.sourceGP.map(_.vertices.map(_.variable))
    if(variablesInSourceGP.contains(cons.var1)){
      firstValue = row1.getAs[String]("s."+cons.var1+"$"+cons.attr1)
    }else{
      firstValue = row1.getAs[String]("t."+cons.var1+"$"+cons.attr1)
    }
    if(cons.var2 == "") secondValue = cons.attr2
    else if(variablesInSourceGP.contains(cons.var2)){
      secondValue = row1.getAs[String]("s."+cons.var2+"$"+cons.attr2)
    }else{
      secondValue = row1.getAs[String]("t."+cons.var2+"$"+cons.attr2)
    }
    cons.distance match {
      case "edit" => distResult = dist.editDistance(firstValue,secondValue) //value.variable.attr1
      case "euclidean" => distResult = dist.EuclideanDistance(dist.getArray(firstValue), dist.getArray(secondValue))
      case "diff" => distResult = dist.Diff(dist.getArray(firstValue), dist.getArray(secondValue))
      case "equal" => if(row1.getAs[String](cons.var1+"$id") == row1.getAs[String](cons.var2+"$id")) return true else return false
    }
    cons.operator match{
      case "<=" => return(distResult <= cons.threshold)
      case "=" => return(distResult == cons.threshold)
      case ">=" => return(distResult >= cons.threshold)
      case "<" => return(distResult < cons.threshold)
      case ">" => return(distResult > cons.threshold)
      case "!=" => return(distResult != cons.threshold)
    } //returns true if satisfiable/ false if not
  }


}
