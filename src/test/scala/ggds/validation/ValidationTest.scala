package ggds.validation

import compiler.{CompileContext, Compiler, GcoreCompiler}
import ggd.{GGDSet, GcoreRunner}
import ggd.ggd.ggdValidation
import ggd.useCases.{dbPediaURL, dbPediaURLSame}
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.spark.sql.SparkSession
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import spark.{SparkCatalog, SparkGraph}
import spark.examples.SocialGraph

@RunWith(classOf[JUnitRunner])
class ValidationTest extends FunSuite {

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

  val gcoreRunner: GcoreRunner = newRunner //init gcore
  catalog.setDefaultGraph("dbpediaURL")
  gcoreRunner.catalog.databaseDirectory = "defaultDB"
  val model, model_2: Model = ModelFactory.createDefaultModel()
  model.read("/Users/larissashimomura/Documents/phd/entityResolutionExtraData/sample_sameAs.txt", "turtle") //read rdf
  //model.read("/home/larissacsh/Documents/entityResolution/entityResolutionExtraData/sample_sameAs.txt", "turtle")
  val dbpedia: SparkGraph = dbPediaURL(gcoreRunner.sparkSession, model)
  val dbpediaSame: SparkGraph = dbPediaURLSame(gcoreRunner.sparkSession, model).RDFtoPropertyGraph
  val ggds : GGDSet = new GGDSet() //instantiate Property graph
  ggds.loadGGDs("/Users/larissashimomura/Documents/phd/GGDjson/ggddbpediaurl/config.json")

   test("Violated GGDs"){
     gcoreRunner.catalog.registerGraph(dbpedia) //transform to gcore
     val ggdcheck : ggdValidation = new ggdValidation(gcoreRunner)
     val expectedNumberofViolated = dbpedia.vertexData.head.data.count() //all of them
     val violated = ggdcheck.validation(ggds.AllGGDs.head)
     assert(violated.data.size == expectedNumberofViolated)
   }

   test("Non violated GGDs"){
     gcoreRunner.catalog.registerGraph(dbpediaSame)
     val ggdcheck : ggdValidation = new ggdValidation(gcoreRunner)
     val violated = ggdcheck.validation(ggds.AllGGDs.head)
     assert(violated.data.size == 0)
   }


  }


