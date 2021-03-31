package spark.sql.operatorsJDBC

import algebra.target_api.TargetTreeNode

case class CrossJoinJDBC(lhs: TargetTreeNode, rhs: TargetTreeNode) extends JoinJDBC(lhs, rhs) {

  override def joinTypeSql: String = "CROSS JOIN"

  override def joinCondition: String = ""
}
