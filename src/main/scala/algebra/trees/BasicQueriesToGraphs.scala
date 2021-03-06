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

import algebra.operators._
import common.exceptions.UnsupportedOperation
import common.trees.TopDownRewriter

object BasicQueriesToGraphs extends TopDownRewriter[AlgebraTreeNode] {

  override val rule: BasicQueriesToGraphs.RewriteFuncType = {
    case q: Query =>
      GraphBuild(
        matchClause = q.getMatchClause,
        groupConstructs = q.getConstructClause.children,
        matchWhere = q.matchClause.whereClause)
    case q: SelectQuery =>
      TableBuild(
        matchClause = q.getMatchClause,
        selectClause = q.selectClause,
        matchWhere = q.matchClause.whereClause
      )
    case q: UnionQuery =>
      UnionBuild(
        matchClause = q.query.getMatchClause,
        groupConstructs = q.query.getConstructClause.children,
        matchWhere = q.query.matchClause.whereClause,
        graphUnion = q.unionGraph
      )
  }
}
