package SimSQL.logicalPlan

import org.apache.spark.sql.catalyst.expressions.PredicateHelper
import org.apache.spark.sql.catalyst.plans.logical.{Filter, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.Rule

object SimJoinOptimizer extends Rule[LogicalPlan] with PredicateHelper {
  //println("SIMILARITY JOIN RESOLVE")

  /*private def split(condition: Seq[Expression], left: LogicalPlan, right: LogicalPlan) = {
    val (pushDownCandidates, nonDeterministic) = condition.partition(_.deterministic)
    val (leftEvaluateCondition, rest) =
      pushDownCandidates.partition(_.references.subsetOf(left.outputSet))
    val (rightEvaluateCondition, commonCondition) =
      rest.partition(expr => expr.references.subsetOf(right.outputSet))

    (leftEvaluateCondition, rightEvaluateCondition, commonCondition ++ nonDeterministic)
  }*/

  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    // push the where condition down into join filter
    //case f @ Filter(filterCondition, Join(left, right, joinType, joinCondition)) =>
    case f @ Filter(filterCondition, SimilarityJoin(left, right, jointype, joinCondition)) =>
      //val (leftFilterConditions, rightFilterConditions, commonFilterCondition) =
      //  split(splitConjunctivePredicates(filterCondition), left, right)
      jointype match {
        case Similarity => {
          println("Similarity join resolve sim join")
          f
        }
      }

    // push down the join filter into sub query scanning if applicable
    case j @ SimilarityJoin(left, right, jointype, joinCondition) =>
      //val (leftJoinConditions, rightJoinConditions, commonJoinCondition) =
      //  split(joinCondition.map(splitConjunctivePredicates).getOrElse(Nil), left, right)
      jointype match {
        case Similarity => {
          println("Similarity join in resolve join")
          j
        }
      }
  }

}