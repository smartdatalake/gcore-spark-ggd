package SimSQL.logicalPlan

import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression, PredicateHelper}
import org.apache.spark.sql.catalyst.plans.logical.{BinaryNode, LogicalPlan}

case class SimilarityJoin(
                 left: LogicalPlan,
                 right: LogicalPlan,
                 joinType: SimilarityJoinType,
                 condition: Option[Expression])
  extends BinaryNode with Serializable {

  override def output: Seq[Attribute] = {
    joinType match {
      case Similarity =>{
        //require(condition.get.isInstanceOf[simExpression])
        //println("AQUI join type")
        left.output ++ right.output
      }
      case _ =>
        left.output ++ right.output
    }
  }

  override protected def validConstraints: Set[Expression] = {
    joinType match {
      case Similarity =>{
        // println("SIMILARITY AQUI")
        left.constraints.union(right.constraints)
          .union(Set(condition.get))
        //.union(splitConjunctivePredicates(condition.get).toSet)
      }

    }
  }

}
