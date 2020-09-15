package parser.trees

import algebra.expressions.{AlgebraExpression, Reference}
import algebra.operators.{BindingSet, GraphUnion, Relation, RelationLike, Select, SelectClause}
import algebra.types.{Graph, NamedGraph}

import scala.collection.mutable

object GraphTreeBuilder {

  def extractGraph(from: SpoofaxBaseTreeNode): GraphUnion = {
    //just one UNION graph for now
    val graph : Graph = NamedGraph(from.children.head.asInstanceOf[SpoofaxLeaf[String]].value)
    println("extract Graph - graphUnion")
    return GraphUnion(Seq(graph))

  }

}
