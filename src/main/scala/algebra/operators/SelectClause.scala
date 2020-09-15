package algebra.operators

import common.compiler.Context

abstract class SelectClauseLike extends GcoreOperator {
  override def checkWithContext(context: Context): Unit = {}
}

case class SelectClause(map: Map[String,String]) extends SelectClauseLike{

  val varReferences = map

}
