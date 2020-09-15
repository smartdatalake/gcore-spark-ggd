package ggd.utils

//import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.util.Sorting

class Result{
  var truePos : Int = 0 //o que tem nas duas listas
  var trueNeg : Int = 0 //o resto do numero de entities -> nao usado
  var falsePos : Int = 0 //o que tem apenas na lista de resultados
  var falseNeg : Int = 0 //o que tem apenas na lista de ground truth
}

class GroundTruthSource {

  //val loadDataFn: String => DataFrame
  var read = ArrayBuffer[(String,String)]()
  var groundTruth: Array[(String,String)] = Array.empty
  var res = new Result()

  def readGroundTruth(path: String, sep: String):  Unit = {
    for(line <- Source.fromFile(path).getLines()) {
      val groundTruthPair = line.split(sep)
      val pair: (String,String) = (groundTruthPair.apply(0), groundTruthPair.apply(1))
      read += pair
    }
    groundTruth = read.toArray
  }

  def compareGroundTruth(results: Array[(String,String)]) : Result = {
    results.sortBy(x => (x._1, x._2))
    groundTruth.sortBy(x => (x._1, x._2))
    res.truePos = groundTruth.intersect(results).size
    res.falsePos = results.diff(groundTruth).size
    res.falseNeg = groundTruth.diff(results).size
    res.trueNeg = 0 //not used yet
    //verificar com a lista de groundtruth e criar result
    return res
  }

  def getResultsFromTable(df: DataFrame, spark: SparkSession) : Array[(String, String)] = {
    import spark.implicits._
    val array = df.select("fromId", "toId").map(r => (r.getAs[Int]("fromId").toString, r.getAs[Int]("toId").toString))
    array.collect()
  }

  def prettyPrintResults(): Unit = {
    println("True positive = " + res.truePos)
    println("False positive = " + res.falsePos)
    println("False negative = " + res.falseNeg)
    println("True negative = " + res.trueNeg)
  }


}