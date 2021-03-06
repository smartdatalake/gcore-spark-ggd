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

package schema

import algebra.expressions.Label
import org.apache.spark.sql.DataFrame

/** Data stored in a graph. */
trait GraphData {

  /** Backend-specific storage type for graph data. */
  type StorageType

  def vertexData: Seq[Table[StorageType]]

  def edgeData: Seq[Table[StorageType]]

  def pathData: Seq[Table[StorageType]]



  /**
    * A mapping from table name to table data. Given that each [[Label]] is a table, we express the
    * name through that particular [[Label]].
    */
  def tableMap: Map[Label, Table[StorageType]] =
    (vertexData map {table => table.name -> table}).toMap ++
      (edgeData map {table => table.name -> table}).toMap ++
      (pathData map {table => table.name -> table}).toMap


}

/** A physical table backing the data. */
case class Table[StorageType](name: Label, data: StorageType) {

  var graphName: String = "-"

  override def toString: String = s"Table $name: $data"
}
