package ggd

import SimSQL.logicalPlan.{ResolveSimJoin, SimJoinOptimizer}
import SimSQL.parser.SimCatalystSqlParser
//import org.apache.spark.sql.parser.SimCatalystSqlParser
//import SimSQL.parser.SimCatalystSqlParser
import SimSQL.physicalPlan.SimJoinSelection
import compiler.{CompileContext, Compiler, GcoreCompiler}
import ggd.{GGDSet, GraphGenDep, ViolatedV2, ggdValidationV2}
import org.apache.jena.rdf.model.{Model, ModelFactory, RDFList, RDFNode, RDFReader, StmtIterator}
import org.apache.spark.sql.{DataFrame, SparkSession, SparkSessionExtensions}
import org.apache.spark.{SparkConf, SparkContext}
import org.w3c.dom.traversal.NodeIterator
import schema.{Catalog, PathPropertyGraph}
import spark.{Directory, GraphSource, SaveGraph, SparkCatalog, SparkGraph}
import utils.{GroundTruthSource, Result}

import scala.reflect.io.Path


object Runner {

  type ExtensionsBuilder = SparkSessionExtensions => Unit

  def create(builder: ExtensionsBuilder): ExtensionsBuilder = builder

  val extension = create { extensions =>
    extensions.injectParser((_, _) => SimCatalystSqlParser)
    extensions.injectOptimizerRule(sessionExtensions => SimJoinOptimizer)
    extensions.injectResolutionRule(sessionExtensions => ResolveSimJoin)
    extensions.injectPlannerStrategy(sessionExtension => SimJoinSelection)
  }

  def newRunner: GcoreRunner = {
    val sparkSession: SparkSession = SparkSession
      .builder()
      .appName("G-CORE Runner")
      .master("local[*]")
      .withExtensions(extension)
      .getOrCreate()
    val catalog: SparkCatalog = SparkCatalog(sparkSession)
    val compiler: Compiler = GcoreCompiler(CompileContext(catalog, sparkSession))

    GcoreRunner(sparkSession, compiler, catalog)
  }

}

case class GcoreRunner(sparkSession: SparkSession, compiler: Compiler, catalog: Catalog)