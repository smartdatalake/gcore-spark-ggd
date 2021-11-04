package SimSQL.physicalPlan

import SimSQL.execution.joins.JoinAlgorithms.joins.{EditDistanceSimilarityJoinDima, JaccardSimilarityJoinDima, VernicaJoin}
import SimSQL.logicalPlan.{Similarity, SimilarityJoin, SimilarityJoinType}
import SimSQL.physicalPlan.ExtractSimilarityJoinKeys
import org.apache.spark.sql.Strategy
import org.apache.spark.sql.catalyst.expressions.{Expression, PredicateHelper}
import org.apache.spark.sql.catalyst.plans.logical.{Join, LogicalPlan}
import org.apache.spark.sql.execution.SparkPlan

import scala.collection.Seq

object SimJoinSelection extends Strategy with PredicateHelper {
  //TODO plan later left and right maybe need to change this in SimJoin optimizer
  override def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
    case ExtractSimilarityJoinKeys(measure, left_keys, right_keys, Similarity, left, right, operator, threshold) => {
      //val a = ExtractSimilarityJoinKeys(a)
      measure match {
        case "JaccardSimilarity" => {
          logInfo(s"JaccardSimilarityJoin")
          //JaccardSimilarityJoinDima(left_keys, right_keys, Similarity, planLater(left), planLater(right), operator, threshold) :: Nil
          VernicaJoin(left_keys, right_keys, Similarity, planLater(left), planLater(right), operator, threshold) :: Nil
        }
        case "SelfJaccardSimilarity" => {
          logInfo(s"SelfJaccardSimilarityJoin")
          println("Self Jaccard Similarity Join")
          JaccardSimilarityJoinDima(left_keys, right_keys, Similarity, planLater(left), planLater(right), operator, threshold) :: Nil
        }
        case "EditSimilarity" => {
          logInfo(msg = s"Edit Distance Similarity")
          EditDistanceSimilarityJoinDima(left_keys, right_keys, Similarity, planLater(left), planLater(right), operator, threshold) :: Nil
        }
        case _ => {
          logError(s"No similarity join operator for the similarity function" + measure)
          Nil //no similarity join operator for this kind of similarity function
        }
      }
    }
    case _ => Nil
  }

}
