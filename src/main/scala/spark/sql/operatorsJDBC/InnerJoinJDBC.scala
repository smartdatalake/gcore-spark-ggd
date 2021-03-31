package spark.sql.operatorsJDBC

import algebra.target_api.TargetTreeNode
import spark.sql.SqlQuery.commonColumnsForJoinJDBC

case class InnerJoinJDBC(lhs: TargetTreeNode, rhs: TargetTreeNode) extends JoinJDBC(lhs, rhs) {

  override def joinTypeSql: String = "INNER JOIN"

  override def joinCondition: String = commonColumnsForJoinJDBC(lhsSchema, rhsSchema,lhsAlias, rhsAlias)
}
