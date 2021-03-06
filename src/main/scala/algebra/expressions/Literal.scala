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

package algebra.expressions

import algebra.types.{GcoreDataType, GcoreBoolean, GcoreInteger, GcoreString, GcoreNull, GcoreDate,GcoreTimestamp}

/** An actual value, such as strings, integers, booleans True and False, dates, etc. */
abstract class Literal[T](value: T, gcoreType: GcoreDataType) extends AlgebraExpression {
  override def toString: String = s"$name [$value, $gcoreType]"
}

case class IntLiteral(value: Int) extends Literal[Int](value, GcoreInteger)
case class StringLiteral(value: String) extends Literal[String](value, GcoreString)

abstract class BooleanLiteral(value: Boolean) extends Literal[Boolean](value, GcoreBoolean)
case object True extends BooleanLiteral(value = true)
case object False extends BooleanLiteral(value = false)


abstract class NullLiteral(value: Object) extends Literal[Object](value, GcoreNull)
case object Null extends NullLiteral(value = null)

case class DateLiteral(value: String) extends Literal[String](value, GcoreDate)


case class TimeStampLiteral(value: String) extends Literal[String](value, GcoreTimestamp)