package parser.trees

import algebra.expressions.{AlgebraExpression, Reference}
import algebra.operators.{BindingSet, Relation, RelationLike, Select, SelectClause}

import scala.collection.mutable

object SelectTreeBuilder {

  def extractSelectClause(from: SpoofaxBaseTreeNode): SelectClause = {
    //val rel: RelationLike = RelationLike()
    //val expr: AlgebraExpression =
    //VarRefsize
    var variables = mutable.Map[String,String]() //key is label reference, value is property

    val selects = from.children
    println("Print children")
    println(from.printTree())
    for(children: SpoofaxBaseTreeNode <- selects){ //for expasvar
      val x: (String,String) = getLabelAttr(children)
      variables.+=(x)
    }

    //Select(relation: RelationLike,
      //                  expr: AlgebraExpression,
      //                  bindingSet: Option[BindingSet] = None)
      //  extends UnaryOperator(relation, bindingSet)

    //return Select(rel, expr)
    //println("select clause extract")
    return SelectClause(variables.toMap)
  }

  def getLabelAttr(node: SpoofaxBaseTreeNode) : (String, String) = {
      //val pair: (String, String)
    for(child <- node.children){
      if(child.name == "PropRef"){
        val label: String = child.children(0).children(0).asInstanceOf[SpoofaxLeaf[String]].value
        //println(label)
        val ref: String = child.children(1).asInstanceOf[SpoofaxLeaf[String]].value
       // println("label:" + label + " ref" + ref)
        return (label, ref)
      }else if(child.name == "VarRef"){
        val label: String = child.children(0).asInstanceOf[SpoofaxLeaf[String]].value
       // println("Label:" + label)
        return (label, "")
      }else if(child.name == "Mul"){
        //println("Parameter for all Select!!!")
        return ("*", "")
      }
    }
    return null //tem que ser um dos dois
  }

 /* def extractSelect(from: SpoofaxBaseTreeNode) : Select = {

    var ref: Set[Reference] = Set[Reference]()
    for(child <- from.children){
      for(ch <- child.children){
        val label: String = child.children(0).asInstanceOf[SpoofaxLeaf[String]].value
        val reference = Reference(label)
        ref+= reference
      }
    }

    val bind: BindingSet = BindingSet(ref)
    val rel: RelationLike = new RelationLike(bind) {
      override def name: String = "selectclause"
    }
    println("is empty"+ rel.getBindingSet.refSet.isEmpty)
    val expr: AlgebraExpression = null//extractExpression(from.children.last)

    //Select(relation: RelationLike,expr: AlgebraExpression,bindingSet: Option[BindingSet] = None)

    return Select(rel, expr)
  }*/


}

