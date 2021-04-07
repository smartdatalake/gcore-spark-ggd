package simsql

import SimSQL.logicalPlan.{ResolveSimJoin, SimJoinOptimizer}
import SimSQL.parser.SimCatalystSqlParser
import SimSQL.physicalPlan.SimJoinSelection
import algebra.AlgebraRewriter
import algebra.expressions.Label
import algebra.trees.{AlgebraContext, AlgebraTreeNode}
import compiler.{CompileContext, Compiler, GcoreCompiler, ParseStage, RewriteStage, RunTargetCodeStage}
import ggd.GcoreRunner
import org.apache.spark.sql.{DataFrame, SparkSession, SparkSessionExtensions}
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import parser.SpoofaxParser
import parser.trees.ParseContext
import schema.PathPropertyGraph
import spark.SparkCatalog
import spark.examples.SocialGraph
import spark.sql.SqlRunner
import schema.Table

@RunWith(classOf[JUnitRunner])
class SimilarityQueryTests extends FunSuite{

  type ExtensionsBuilder = SparkSessionExtensions => Unit

  def create(builder: ExtensionsBuilder): ExtensionsBuilder = builder

  val extension = create { extensions =>
    extensions.injectParser((_, _) => SimCatalystSqlParser)
    extensions.injectOptimizerRule(sessionExtensions => SimJoinOptimizer)
    extensions.injectResolutionRule(sessionExtensions => ResolveSimJoin)
    extensions.injectPlannerStrategy(sessionExtension => SimJoinSelection)
  }

  val sparkSession: SparkSession = SparkSession
    .builder()
    .appName("G-CORE Runner with Extensions")
    .master("local[*]")
    .withExtensions(extension)
    .getOrCreate()
  import sparkSession.implicits._
  val catalog: SparkCatalog = SparkCatalog(sparkSession)
  catalog.registerGraph(SocialGraph(sparkSession))
  catalog.setDefaultGraph("social_graph")
  val context = CompileContext(catalog, sparkSession)
  val parser: ParseStage = SpoofaxParser(ParseContext(context.catalog))
  val rewriter: RewriteStage = AlgebraRewriter(AlgebraContext(context.catalog))
  val target: RunTargetCodeStage = SqlRunner(context)

  test("Query with Jaccard Similarity") {
    val query = "SELECT id1,id2 FROM table1 SIMILARITY JOIN table2 USING JACCARDSIMILARITY(table1.set, table2.set) < 0.8"
    val table1 = Seq(
      {0,"7 3 2 4 1"}
      {1,"4 1 9 7 5"}
    ).toDF("id1", "set").createOrReplaceTempView("table1")
    val table2 = Seq(
      {0,"7 3 2 4 1"}
      {1,"4 1 9 7 5"}
    ).toDF("id2", "set").createOrReplaceTempView("table2")

    val expected = Seq(
      (0,0),
      (1,1,)
    ).toDF("id1", "id2")

    val resultJaccard: DataFrame = sparkSession.sql(query)
    assert(resultJaccard.except(expected).count == 0)
  }

  test("Query with Edit Similarity"){
    val query = "SELECT id1,id2 FROM table1 SIMILARITY JOIN table2 USING EDITSIMILARITY(table1.set, table2.set) < 3"
    val table1 = Seq(
      {0,"73241"}
    {1,"41975"}
    ).toDF("id1", "set").createOrReplaceTempView("table1")
    val table2 = Seq(
      {0,"73241"}
    {1,"41975"}
    ).toDF("id2", "set").createOrReplaceTempView("table2")

    val expected = Seq(
      (0, 0),
      (1,1,)
    ).toDF("id1", "id2")


    val resultEdit: DataFrame = sparkSession.sql(query)
    assert(resultEdit.except(expectedPerson).count == 0)

  }

}
