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

package parser.trees

import algebra.expressions._
import algebra.operators._
import algebra.trees.AlgebraTreeNode
import algebra.types._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FunSuite, Inside, Matchers}

@RunWith(classOf[JUnitRunner])
class ConstructTreeBuilderTest extends FunSuite
  with Matchers with Inside with MinimalSpoofaxParser {

  /********************************* Construct mix ************************************************/
 /* test("(u) => CondConstructClause(ConstructPattern([VertexConstruct(u)]), True)") {
    val algebraTree = extractConstructClause("CONSTRUCT (u) MATCH (u)")
    inside(algebraTree) {
      case ConstructClause(_,_,_) =>

        constructPattern should matchPattern {
          case ConstructPattern(Seq(_: ConnectionConstruct)) =>
        }
    }
  }

  test("(u), (v) => " +
    "CondConstructs(" +
    "CondConstructClause(ConstructPattern(VertexConstruct(u)), True)," +
    "CondConstructClause(ConstructPattern(VertexConstruct(v)), True)" +
    ")") {

    val algebraTree = extractConstructClause("CONSTRUCT (u), (v) MATCH (u), (v)")
    inside(algebraTree) {
      case ConstructClause(

      _, _,_) =>

        assert(condConstructs.size == 2)
        condConstructs.foreach(
          basicConstructClause =>
            basicConstructClause should matchPattern {
              case CondConstructClause(ConstructPattern(Seq(_: ConnectionConstruct))) =>
            }
        )
    }
  }

  /********************************* Construct pattern ********************************************/
  test("(u) becomes a VertexConstruct") {
    val algebraTree = extractConstructPattern("CONSTRUCT (u) MATCH (u)")
    algebraTree should matchPattern {
      case ConstructPattern(Seq(
        VertexConstruct(Reference("u"), None, None, ObjectConstructPattern.empty))) =>
    }
  }

  test("(u)->(v) becomes an EdgeConstruct") {
    val algebraTree = extractConstructPattern("CONSTRUCT (u)-[e]->(v) MATCH (u)-[e]->(v)")
    algebraTree should matchPattern {
      case ConstructPattern(Seq(
        EdgeConstruct(
          Reference("e"),
          OutConn,
          VertexConstruct(Reference("u"), _, _, _), VertexConstruct(Reference("v"), _, _, _),
          None, None, ObjectConstructPattern.empty))) =>
    }
  }

  test("(u)->(v)->(w) becomes two EdgeConstructs") {
    val algebraTree =
      extractConstructPattern("CONSTRUCT (u)-[e1]->(v)-[e2]->(w) MATCH (u)-[e1]->(v)-[e2]->(w)")
    inside(algebraTree) {
      case ConstructPattern(connections) =>
        assert(connections.size == 2)
        assert(
          connections.map(_.getRef).toSet ==
          Set(Reference("e1"), Reference("e2"))
        )
    }
  }

  test("CopyPattern for VertexConstruct") {
    val algebraTree = extractConstructPattern("CONSTRUCT (u=v) MATCH (u)")
    algebraTree should matchPattern {
      case ConstructPattern(Seq(
        VertexConstruct(_, Some(Reference("v")), _, _))) =>
    }
  }

  test("CopyPattern for EdgeConstruct") {
    val algebraTree = extractConstructPattern("CONSTRUCT (u)-[e=f]->(v) MATCH (u)-[e]->(v)")
    algebraTree should matchPattern {
      case ConstructPattern(Seq(
        EdgeConstruct(_, _, _, _, Some(Reference("f")), _, _))) =>
    }
  }

  test("CopyPattern for StoredPathConstruct") {
    // We need to provide a label for the path, otherwise Spoofax emits an amb (ambiguous) term.
    // TODO: Investigate why the amb term is emitted and how to fix the issue.
    val algebraTree =
      extractConstructPattern("CONSTRUCT (u)-/@p=q :Label/->(v) MATCH (u)-/@p/->(v)")
    algebraTree should matchPattern {
      case ConstructPattern(Seq(
        StoredPathConstruct(_, _, _, _, Some(Reference("q")), _))) =>
    }
  }

  test("GroupDeclaration for VertexConstruct") {
    val algebraTree = extractConstructPattern("CONSTRUCT (u GROUP u.prop) MATCH (u)")
    algebraTree should matchPattern {
      case ConstructPattern(Seq(
        VertexConstruct(
          _, _,
          Some(GroupDeclaration(Seq(PropertyRef(Reference("u"), PropertyKey("prop"))))),
          _))) =>
    }
  }

  test("GroupDeclaration for EdgeConstruct") {
    val algebraTree =
      extractConstructPattern("CONSTRUCT (u)-[e GROUP e.prop]->(v) MATCH (u)-[e]->(v)")
    algebraTree should matchPattern {
      case ConstructPattern(Seq(
      EdgeConstruct(
        _, _, _, _, _,
        Some(GroupDeclaration(Seq(PropertyRef(Reference("e"), PropertyKey("prop"))))),
        _))) =>
    }
  }

  /****************************** Graph union *****************************************************/
  test("NamedGraphs are passed as a GraphUnion") {
    val algebraTree = extractConstructClause("CONSTRUCT social_graph, city_graph MATCH (u)")
    inside(algebraTree) {
      case ConstructClause(_, _, _) =>
        assert(graphs.size == 2)
        assert(graphs.toSet == Set(NamedGraph("social_graph"), NamedGraph("city_graph")))
    }
  }

  /******************************* Set clause *****************************************************/
  test("SET property := value clauses are passed as a SetClause") {
    val algebraTree =
      extractConstructClause("CONSTRUCT (n) SET n.prop1 := value1 SET n.prop2 := value2 MATCH (n)")
    inside(algebraTree) {
      case ConstructClause(_, _,_) =>
        assert(propSets.size == 2)
        assert(propSets.toSet ==
          Set(
            PropertySet(
              Reference("n"),
              PropAssignment(PropertyKey("prop1"), Reference("value1"))),
            PropertySet(
              Reference("n"),
              PropAssignment(PropertyKey("prop2"), Reference("value2")))
          ))
    }
  }

  /****************************** Remove clause ***************************************************/
  test("REMOVE n.property clause is passed as a RemoveClause") {
    val algebraTree =
      extractConstructClause("CONSTRUCT (n) REMOVE n.prop1 REMOVE n.prop2 MATCH (n)")
    inside(algebraTree) {
      case ConstructClause(_, _,_) =>
        assert(labelRemoves.isEmpty)
        assert(propRemoves.size == 2)
        assert(propRemoves.toSet ==
          Set(
            PropertyRemove(PropertyRef(Reference("n"), PropertyKey("prop1"))),
            PropertyRemove(PropertyRef(Reference("n"), PropertyKey("prop2")))
          ))
    }
  }

  test("REMOVE n:Foo:Bar REMOVE m:Baz clause is passed as a RemoveClause") {
    val algebraTree =
      extractConstructClause("CONSTRUCT (n) REMOVE n:Foo:Bar REMOVE m:Baz MATCH (n)")
    inside(algebraTree) {
      case ConstructClause(_, _,_) =>
        assert(propRemoves.isEmpty)

        labelRemoves.foreach {
          case LabelRemove(Reference("n"), labelAssignments) =>
            assert(labelAssignments.labels.size == 2)
            assert(labelAssignments.labels.toSet == Set(Label("Foo"), Label("Bar")))
          case LabelRemove(Reference("m"), labelAssignments) =>
            assert(labelAssignments.labels.size == 1)
            assert(labelAssignments.labels.toSet == Set(Label("Baz")))
          case _ => fail("Only references n and m should be present in the RemoveClause")
        }
    }
  }

  test("REMOVE n.prop REMOVE n:Label is passed as a RemoveClause") {
    val algebraTree =
      extractConstructClause("CONSTRUCT (n) REMOVE n.prop REMOVE n:Label MATCH (n)")
    inside(algebraTree) {
      case ConstructClause(_, _,_) =>
        assert(propRemoves.size == 1)
        assert(labelRemoves.size == 1)

        propRemoves.head should matchPattern {
          case PropertyRemove(PropertyRef(Reference("n"), PropertyKey("prop"))) =>
        }

        labelRemoves.head should matchPattern {
          case LabelRemove(Reference("n"), LabelAssignments(Seq(Label("Label")))) =>
        }
    }
  }

  private def extractConstructClause(query: String): AlgebraTreeNode = {
    parse(query).asInstanceOf[Query].getConstructClause
  }

  private def extractConstructPattern(query: String): AlgebraTreeNode = {
    val construct = parse(query).asInstanceOf[Query].getConstructClause
    val condConstructClause = construct.asInstanceOf[ConstructClause].condConstructs
    assert(condConstructClause.condConstructs.size == 1)
    val basicConstructClause = condConstructClause.condConstructs.head
    val constructPattern = basicConstructClause.constructPattern
    constructPattern
  }*/
}
