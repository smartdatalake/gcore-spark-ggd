package algebra.trees

import algebra.expressions.True
import algebra.operators.{BaseConstructTableView, BindingSet, BindingTableView, RelationLike, Select, SelectClause, TableBuild}
import algebra.trees.ConditionalToGroupConstruct.{BASE_CONSTRUCT_VIEW_PREFIX, VERTEX_CONSTRUCT_VIEW_PREFIX}
import algebra.types.ConstructPattern
import common.RandomNameGenerator.randomString
import common.trees.BottomUpRewriter

case class SelectClauseToSelectTable(context: AlgebraContext) extends BottomUpRewriter[AlgebraTreeNode] {

  assert(context.bindingContext.isDefined,
    "The bindingContext in AlgebraContext needs to be defined for this rewrite stage.")

  private val bindingContext: BindingContext = context.bindingContext.get
  private val bindingTableView: BindingTableView =
    BindingTableView(BindingSet(bindingContext.allRefs))

  private val selectClause: RewriteFuncType = {
    //case select: SelectClause =>{
      //val select: Select = Select(relation = bindingTableView, expr = null)
      //var tablebuild: TableBuild = TableBuild()

      //rewrite algebra tree to select table operator
      //CONSTRUCT one node with all information
      //val when: AlgebraExpression = constructPattern. // ex when where aqui

      //val baseConstructViewName: String = s"${BASE_CONSTRUCT_VIEW_PREFIX}_${randomString()}"
      //val vertexConstructViewName: String = s"${VERTEX_CONSTRUCT_VIEW_PREFIX}_${randomString()}"

      // Filter the binding table by the expression in WHEN.
      //val filteredBindingTable: RelationLike = Select(relation = bindingTableView, expr = True)
      //val baseConstructTableView: BaseConstructTableView =
      //  BaseConstructTableView(baseConstructViewName, filteredBindingTable.getBindingSet)

      //tableview and select
    //}

      null
  }

  override val rule: RewriteFuncType = selectClause

}
