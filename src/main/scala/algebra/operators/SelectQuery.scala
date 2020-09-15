package algebra.operators

import algebra.trees.AlgebraTreeNode
import common.compiler.Context

/**
  * The root of the algebraic tree between the parsing and full conversion into a relational tree.
  */
//selectClause: SelectClause
case class SelectQuery(selectClause: SelectClause, matchClause: MatchClause) extends GcoreOperator {

  children = List(selectClause, matchClause)

  def getSelectClause: AlgebraTreeNode = children.head
  def getMatchClause: AlgebraTreeNode = children.last

  override def checkWithContext(context: Context): Unit = {
    selectClause.checkWithContext(context)
    matchClause.checkWithContext(context)
  }

}

case class TableBuild(matchClause: AlgebraTreeNode,
                      selectClause: AlgebraTreeNode ,
                      matchWhere: AlgebraTreeNode) extends GcoreOperator {


  children = Seq(matchClause,selectClause)

  override def checkWithContext(context: Context): Unit = {}
}