package spark.sql.operatorsJDBC

import algebra.expressions.AlgebraExpression
import algebra.target_api
import algebra.target_api.{BindingTableMetadata, TargetTreeNode}
import spark.sql.SqlQuery
import spark.sql.SqlQuery._
import spark.sql.operators.SqlBindingTableMetadata

/**
 * Projects all the columns from the [[relation]] filtered by the algebraic [[expr]]ession. The
 * [[relation]] is aliased before the expansion [[expr]]ession into a selection predicate, in case
 * the [[expr]]ession contains an EXISTS clause. For more details, see
 * [[expressionToSelectionPred]].
 */
case class SelectJDBC(relation: TargetTreeNode, expr: AlgebraExpression)
  extends target_api.Select(relation, expr) {

  override val bindingTable: BindingTableMetadata = {
    val relationBtable: SqlBindingTableMetadata =
      relation.bindingTable.asInstanceOf[SqlBindingTableMetadata]

    val fromAlias: String = tempViewAlias

    /*val selectQuery: String =
      s"""
      SELECT * FROM (${relationBtable.btable.resQuery})
      WHERE (${expressionToSelectionPredJDBC(expr, relationBtable.schemaMap, fromAlias)})"""
    /*with alias
          s"""
      SELECT * FROM (${relationBtable.btable.resQuery}) $fromAlias
      WHERE (${expressionToSelectionPredJDBC(expr, relationBtable.schemaMap, fromAlias)})"""
     */
      */

    def getSelectQuery(): String = {
      if(expressionToSelectionPredJDBC(expr, relationBtable.schemaMap, fromAlias) == "True"){
        return relationBtable.btable.resQuery
      }
      else{
        s"""
      SELECT * FROM (${relationBtable.btable.resQuery})
      WHERE (${expressionToSelectionPredJDBC(expr, relationBtable.schemaMap, fromAlias)})"""
      }
    }

    relationBtable.copy(btableOps = SqlQuery(resQuery = getSelectQuery()))
    //relationBtable.copy(btableOps = SqlQuery(resQuery = selectQuery))
  }
}

