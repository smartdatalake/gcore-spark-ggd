package SimSQL

import ggd.Runner
import org.apache.log4j.{Level, Logger}

object SimExample {

  def main(args: Array[String]): Unit = {

    println("Log classes are turned off!")
    Logger.getLogger("org").setLevel(Level.OFF)
    Logger.getLogger("akka").setLevel(Level.OFF)

    val gcoreRunner = Runner.newRunner

    val table1 = gcoreRunner.sparkSession.read.option("header","true")
      .option("inferschema", true)
      .csv("/home/larissacsh/Documents/entityResolution/benchmarkDatasets/AmazonGoogle/GoogleProducts.csv")
    val table2 = gcoreRunner.sparkSession.read.option("header","true")
      .option("inferschema", true)
      .csv("/home/larissacsh/Documents/entityResolution/benchmarkDatasets/AmazonGoogle/Amazon.csv")

    val simjoinApi = new SimilarityAPI(gcoreRunner)

    println("Running Similarity Join using Jaccard Similarity")
    val queryJaccard = simjoinApi.SimJoin(table1, table2, "name", "title", "jaccardsimilarity", 0.8, "<")
    queryJaccard.show(30)

    println("Running Similarity Join using Edit Similarity")
    val queryEdit = simjoinApi.SimJoin(table1, table2, "name", "title", "editsimilarity", 3, "<")
    queryEdit.show(30)

    //Similarity Joins filter null values before executing the similarity join algorithm, it desconsiders these rows in the final result

    //val queryApi = gcoreRunner.sparkSession.sql("SELECT * FROM table1 SIMILARITY JOIN table2 USING JACCARDSIMILARITY(table1.description, table2.description) < 0.8")
    //val queryApi = gcoreRunner.sparkSession.sql("SELECT * FROM table11 SIMILARITY JOIN table22 USING EDITSIMILARITY(table11.name, table22.title) < 3")

  }


}
