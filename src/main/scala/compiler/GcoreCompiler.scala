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

package compiler

import java.sql.Connection

import algebra.AlgebraRewriter
import algebra.operators.{Create, Drop, Query, SelectQuery, UnionQuery, View}
import algebra.trees.{AlgebraContext, AlgebraTreeNode}
import org.apache.spark.sql.{DataFrame, Row}
import parser.SpoofaxParser
import parser.trees.ParseContext
import schema.PathPropertyGraph
import spark.sql.{SqlJDBCRunner, SqlPartialRunner, SqlRunner}

/** Defines the compilation pipeline of a G-CORE query. */
case class GcoreCompiler(context: CompileContext) extends Compiler {

  val parser: ParseStage = SpoofaxParser(ParseContext(context.catalog))
  val rewriter: RewriteStage = AlgebraRewriter(AlgebraContext(context.catalog))
  val target: RunTargetCodeStage = SqlRunner(context)
  val sqlRunner : SqlRunner = SqlRunner(context)

  override def compile(query: String): Unit = {
    var parsed: AlgebraTreeNode  = parser(query)

    parsed match {
    case create : Create =>
      var qCreate = parsed.asInstanceOf[Create]
      if (qCreate.exist)
        println("The graph "+ qCreate.getGraphName+ " already exists")
      else
      {
        var rewrited: AlgebraTreeNode  = rewriter.rewrite(parsed)
        target(rewrited)
      }
    case view : View =>
      var qCreate = parsed.asInstanceOf[View]
      if (qCreate.exist)
        println("The graph "+ qCreate.getGraphName+ " already exists")
      else
      {
        var rewrited: AlgebraTreeNode  = rewriter.rewrite(parsed)
        target(rewrited)
      }
    case query: Query =>
      var rewrited: AlgebraTreeNode  = rewriter.rewrite(parsed)
      target(rewrited)
    case drop: Drop =>
      if(!drop.exist)
        println("The graph "+ drop.getGraphName+ " not exists")
      else
      {
        var rewrited: AlgebraTreeNode  = rewriter.rewrite(parsed)
        target(rewrited)
      }
    case select: SelectQuery =>
       // println("Select Query! (rewriter)")
      //  var rewrited: AlgebraTreeNode = rewriter.rewriteSelect(parsed) //rewrite select queries in SQL
      var rewrited: AlgebraTreeNode = rewriter.rewrite(parsed)
      println(rewrited.name)
      target(rewrited)
    case union: UnionQuery =>
      var rewrited: AlgebraTreeNode = rewriter.rewrite(parsed)
      target(rewrited)
    }
  }

  override def compilePropertyGraph(query: String): Any = {
    var parsed: AlgebraTreeNode  = parser(query)

    parsed match {
      case create: Create =>
        var qCreate = parsed.asInstanceOf[Create]
        if (qCreate.exist) {
        println("The graph " + qCreate.getGraphName + " already exists")
        return null
        }else
        {
          var rewrited: AlgebraTreeNode  = rewriter(parsed)
          return target(rewrited)
        }
      case view : View =>
        var qCreate = parsed.asInstanceOf[View]
        if (qCreate.exist) {
          println("The graph " + qCreate.getGraphName + " already exists")
          return null
        }else
        {
          var rewrited: AlgebraTreeNode  = rewriter(parsed)
          return target(rewrited)
        }
      case query: Query =>
        var rewrited: AlgebraTreeNode  = rewriter(parsed)
        return target(rewrited)
      case drop: Drop =>
        if(!drop.exist) {
          println("The graph " + drop.getGraphName + " not exists")
          return null
        }else
        {
          var rewrited: AlgebraTreeNode  = rewriter(parsed)
          return target(rewrited)
        }
      case select: SelectQuery =>
        //println("Select Query! (rewriter)")
        //  var rewrited: AlgebraTreeNode = rewriter.rewriteSelect(parsed) //rewrite select queries in SQL
        var rewrited: AlgebraTreeNode = rewriter.rewrite(parsed)
        return target(rewrited).vertexData.apply(0).data //return the dataframe
      case union: UnionQuery =>
        var rewrited: AlgebraTreeNode = rewriter.rewrite(parsed)
        return target(rewrited)
    }
  }

  override def compilePropertyGraphWithLimit(query: String, limit: Int = -1): Any = {
    var parsed: AlgebraTreeNode  = parser(query)

    parsed match {
      case create: Create =>
        var qCreate = parsed.asInstanceOf[Create]
        if (qCreate.exist) {
          println("The graph " + qCreate.getGraphName + " already exists")
          return null
        }else
        {
          var rewrited: AlgebraTreeNode  = rewriter(parsed)
          return target(rewrited)
        }
      case view : View =>
        var qCreate = parsed.asInstanceOf[View]
        if (qCreate.exist) {
          println("The graph " + qCreate.getGraphName + " already exists")
          return null
        }else
        {
          var rewrited: AlgebraTreeNode  = rewriter(parsed)
          return target(rewrited)
        }
      case query: Query =>
        var rewrited: AlgebraTreeNode  = rewriter(parsed)
        return sqlRunner.runWithLimit(rewrited, limit)
      case drop: Drop =>
        if(!drop.exist) {
          println("The graph " + drop.getGraphName + " not exists")
          return null
        }else
        {
          var rewrited: AlgebraTreeNode  = rewriter(parsed)
          return target(rewrited)
        }
      case select: SelectQuery =>
        //println("Select Query! (rewriter)")
        //  var rewrited: AlgebraTreeNode = rewriter.rewriteSelect(parsed) //rewrite select queries in SQL
        var rewrited: AlgebraTreeNode = rewriter.rewrite(parsed)
        return target(rewrited).vertexData.apply(0).data //return the dataframe
      case union: UnionQuery =>
        var rewrited: AlgebraTreeNode = rewriter.rewrite(parsed)
        return target(rewrited)
    }
  }

  override def compilePartialQuery(query: String, data: DataFrame): Any = {
    var parsed: AlgebraTreeNode  = parser(query)
    val targetPartial: RunTargetCodeStage = SqlPartialRunner(context, data)

    parsed match {
      case query: Query =>
        var rewrited: AlgebraTreeNode  = rewriter(parsed)
        println("Rewrote query before running")
        return targetPartial(rewrited)
      case _ => println("Not available at the moment")
    }
  }

  override def compilerProteus(query: String, con: Connection): Any = { //java sql jdbc connection
    var parsed: AlgebraTreeNode  = parser(query)
    val targetJDBC: RunTargetCodeStage = SqlJDBCRunner(context, con)
    parsed match {
      case query: Query => {
        var rewrited: AlgebraTreeNode = rewriter(parsed)
        println("Rewrote before running Proteus")
        return targetJDBC(rewrited)
      }
      case select: SelectQuery =>{
        //println("Select Query! (rewriter)")
        //  var rewrited: AlgebraTreeNode = rewriter.rewriteSelect(parsed) //rewrite select queries in SQL
        var rewrited: AlgebraTreeNode = rewriter(parsed)
        return targetJDBC(rewrited).vertexData.apply(0).data //return the dataframe
      }
    }
    //compile the queries and run on Proteus
    return null
  }


}
