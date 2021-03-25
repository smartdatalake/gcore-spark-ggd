package SimSQL.execution.joins

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression, Literal}
import org.apache.spark.sql.catalyst.plans.JoinType
import org.apache.spark.sql.execution.{BinaryExecNode, SparkPlan}

case class EditSimilarityJoinExec(leftKeys: Expression,
                                  rightKeys: Expression,
                                  joinType: JoinType,
                                  left: SparkPlan,
                                  right: SparkPlan,
                                  operator: Literal,
                                  threshold: Literal)
  extends BinaryExecNode {
  override protected def doExecute(): RDD[InternalRow] = {
    left.execute()
  }

  override def output: Seq[Attribute] = left.output ++ right.output
}