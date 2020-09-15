package ggd

import compiler.{CompileContext, Compiler, GcoreCompiler}
import ggd.{GGDSet, GraphGenDep, ViolatedV2, ggdValidationV2}
import org.apache.jena.rdf.model.{Model, ModelFactory, RDFList, RDFNode, RDFReader, StmtIterator}
import org.apache.spark.sql.catalyst.parser.SimCatalystSqlParser
import org.apache.spark.sql.{DataFrame, SparkSession, SparkSessionExtensions}
import org.apache.spark.{SparkConf, SparkContext}
import org.w3c.dom.traversal.NodeIterator
import schema.{Catalog, PathPropertyGraph}
import spark.{Directory, GraphSource, SaveGraph, SparkCatalog, SparkGraph}
import utils.{GroundTruthSource, Result}

import scala.reflect.io.Path


object Runner {

  def newRunner: GcoreRunner = {
    val sparkSession: SparkSession = SparkSession
      .builder()
      .appName("G-CORE Runner")
      .master("local[*]")
      .getOrCreate()
    val catalog: SparkCatalog = SparkCatalog(sparkSession)
    val compiler: Compiler = GcoreCompiler(CompileContext(catalog, sparkSession))

    GcoreRunner(sparkSession, compiler, catalog)
  }

}

case class GcoreRunner(sparkSession: SparkSession, compiler: Compiler, catalog: Catalog)