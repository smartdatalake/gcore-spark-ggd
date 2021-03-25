package SimSQL.physicalPlan

import SimSQL.logicalPlan._
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions.{Expression, Literal, PredicateHelper}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan

object ExtractSimilarityJoinKeys extends Logging with PredicateHelper {
  type ReturnType = (String, Expression, Expression, SimilarityJoinType, LogicalPlan, LogicalPlan, Literal, Literal)

  def unapply(plan: LogicalPlan): Option[ReturnType] = {
    logInfo(s"ExtractSimilarityJoinKeys")
    plan match {
      case SimilarityJoin(left, right, Similarity, condition) => {
        //        val children = condition.get.children
        //        val k = children.last.asInstanceOf[Literal]
        var left_keys: Expression = null
        var right_keys: Expression = null
        var measure = "JaccardSimilarity"
        var op = Literal("=>")
        var threshold = Literal(1)
        if (condition.isEmpty) {
          logError("Condition Can Not Be Empty!")
          return None
        }
        condition.get match {
          case JaccardSimilarity(left, right, operator, delta) => {
            println("Jaccard Similarity")
            measure = "JaccardSimilarity"
            left_keys = condition.get.children(0)
            right_keys = condition.get.children(1)
            op = operator
            threshold = delta
          }
          case SelfJaccardSimilarity(left, operator, delta) => {
            println("Jaccard Similarity")
            measure = "SelfJaccardSimilarity"
            left_keys = condition.get.children(0)
            right_keys = condition.get.children(1)
            op = operator
            threshold = delta
          }
          case EditSimilarity(left, right, operator, delta) => {
            println("Edit Similarity")
            measure = "EditSimilarity"
            left_keys = condition.get.children(0)
            right_keys = condition.get.children(1)
            op = operator
            threshold = delta
          }
        }
        Some((measure, left_keys, right_keys, Similarity, left, right, op, threshold))
      }
      case _ => None
    }
  }
}
