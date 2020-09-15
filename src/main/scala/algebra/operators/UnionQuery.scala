package algebra.operators

import algebra.trees.AlgebraTreeNode
import common.compiler.Context

case class UnionQuery(query: Query, unionGraph: GraphUnion) extends GcoreOperator {

  children = List(query, unionGraph)

  def getQuery: AlgebraTreeNode = children.head
  def getUnion: AlgebraTreeNode = children.last

  override def checkWithContext(context: Context): Unit = {
    query.checkWithContext(context)
    unionGraph.checkWithContext(context)
  }

}

case class UnionBuild(matchClause: AlgebraTreeNode,
                      groupConstructs: Seq[AlgebraTreeNode] ,
                      matchWhere: AlgebraTreeNode,
                      graphUnion: AlgebraTreeNode) extends GcoreOperator {

  val graphBuildQuery = GraphBuild(matchClause, groupConstructs, matchWhere)
  children = Seq(graphBuildQuery, graphUnion)

  override def checkWithContext(context: Context): Unit = {}
}