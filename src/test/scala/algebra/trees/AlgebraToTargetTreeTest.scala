/*
 * gcore-spark is the reference implementation of the G-CORE graph query
 * language by the Linked Data Benchmark Council (LDBC) - ldbcouncil.org
 *
 * The copyrights of the source code in this file belong to:
 * - CWI (www.cwi.nl), 2017-2018
 *
 * This software is released in open source under the Apache License, 
 * Version 2.0 (the "License"); you may not use this file except in 
 * compliance with the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package algebra.trees

import algebra.expressions.{Label, ObjectConstructPattern, Reference, True}
import algebra.operators._
import algebra.target_api
import algebra.target_api.TargetPlanner
import algebra.types.{AllPaths, DefaultGraph, Graph, OutConn}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FunSuite, Matchers}
import schema.Catalog

@RunWith(classOf[JUnitRunner])
class AlgebraToTargetTreeTest extends FunSuite with Matchers with MockFactory {

  val mockedTargetPlanner: TargetPlanner = stub[TargetPlanner]
  val catalog: Catalog = Catalog.empty
  val rewriter: AlgebraToTargetTree = AlgebraToTargetTree(catalog, mockedTargetPlanner)

  val vertexV: VertexRelation = VertexRelation(Reference("v"), Relation(Label("vlabel")), True)
  val vertexW: VertexRelation = VertexRelation(Reference("w"), Relation(Label("wlabel")), True)
  val graph: Graph = DefaultGraph
  val matchContext: SimpleMatchRelationContext = SimpleMatchRelationContext(graph)

  test("planVertexScan is called for SimpleMatchRelation(VertexRelation)") {
    val simpleMatchRelation = SimpleMatchRelation(vertexV, matchContext)
    rewriter rewriteTree simpleMatchRelation
    (mockedTargetPlanner.planVertexScan _).verify(vertexV, graph, catalog).once
  }

  test("planEdgeScan is called for SimpleMatchRelation(EdgeRelation)") {
    val edgeRel: EdgeRelation =
      EdgeRelation(Reference("e"), Relation(Label("elabel")), True, vertexV, vertexW)
    val simpleMatchRelation = SimpleMatchRelation(edgeRel, matchContext)
    rewriter rewriteTree simpleMatchRelation
    (mockedTargetPlanner.planEdgeScan _).verify(edgeRel, graph, catalog).once
  }

  test("planPathScan is called for SimpleMatchRelation(StoredPathRelation)") {
    val pathRel: StoredPathRelation =
      StoredPathRelation(
        Reference("p"), isReachableTest = true, Relation(Label("plabel")),
        expr = True, vertexV, vertexW, costVarDef = None, quantifier = AllPaths)
    val simpleMatchRelation = SimpleMatchRelation(pathRel, matchContext)
    rewriter rewriteTree simpleMatchRelation
    (mockedTargetPlanner.planPathScan _).verify(pathRel, graph, catalog).once
  }

  test("planUnionAll is called for UnionAll") {
    val unionAll = UnionAll(RelationLike.empty, RelationLike.empty)
    rewriter rewriteTree unionAll
    (mockedTargetPlanner.planUnionAll _).verify(unionAll).once
  }

  test("planJoin is called for InnerJoin") {
    val join = InnerJoin(RelationLike.empty, RelationLike.empty)
    rewriter rewriteTree join
    (mockedTargetPlanner.planJoin _).verify(join).once
  }

  test("planJoin is called for LeftOuterJoin") {
    val join = LeftOuterJoin(RelationLike.empty, RelationLike.empty)
    rewriter rewriteTree join
    (mockedTargetPlanner.planJoin _).verify(join).once
  }

  test("planJoin is called for CrossJoin") {
    val join = CrossJoin(RelationLike.empty, RelationLike.empty)
    rewriter rewriteTree join
    (mockedTargetPlanner.planJoin _).verify(join).once
  }

  test("planSelect is called for Select") {
    val select = Select(RelationLike.empty, expr = True)
    rewriter rewriteTree select
    (mockedTargetPlanner.planSelect _).verify(select).once
  }

  test("planProject is called for Project") {
    val project = Project(RelationLike.empty, attributes = Set.empty)
    rewriter rewriteTree project
    (mockedTargetPlanner.planProject _).verify(project).once
  }

  test("planGroupBy is called for GroupBy") {
    val groupBy =
      GroupBy(
        Reference("foo"),
        RelationLike.empty,
        groupingAttributes = Seq.empty,
        aggregateFunctions = Seq.empty,
        having = None)
    rewriter rewriteTree groupBy
    (mockedTargetPlanner.planGroupBy _).verify(groupBy).once
  }

  test("planAddColumn is called for AddColumn") {
    val addColumn = AddColumn(reference = Reference("v"), RelationLike.empty)
    rewriter rewriteTree addColumn
    (mockedTargetPlanner.planAddColumn _).verify(addColumn).once
  }

  test("createTableView is called for a TableView") {
    val tableView = new TableView(viewName = "foo", bindingSet = BindingSet.empty) {}
    rewriter rewriteTree tableView
    (mockedTargetPlanner.createTableView _).verify("foo").once
  }
}
