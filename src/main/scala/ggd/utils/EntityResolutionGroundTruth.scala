package ggd.utils

//import org.apache.spark.sql.DataFrame
import java.io.{FileWriter, PrintWriter}

import org.apache.spark.sql.{DataFrame, SparkSession}
import ggd.utils.Result

import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.util.Sorting

class EntityResolutionGroundTruth {

  var read = ArrayBuffer[(String,String)]()
  var groundTruth: Array[(String,String)] = Array.empty
  //var res = new Result()

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
    //res.truePos = groundTruth.intersect(results).size
    //res.falsePos = results.diff(groundTruth).size
    //res.falseNeg = groundTruth.diff(results).size
    //res.trueNeg = 0 //not used yet
    //verificar com a lista de groundtruth e criar result
    Result(groundTruth.intersect(results).size, 0, results.diff(groundTruth).size, groundTruth.diff(results).size)
  }

  def getResultsFromTable(df: DataFrame, spark: SparkSession) : Array[(String, String)] = {
    import spark.implicits._
    val array = df.select("fromId", "toId").map(r => (r.getAs[Int]("fromId").toString, r.getAs[Int]("toId").toString))
    array.collect()
  }

  /*def saveGroundTruthComparison(resultSet: Array[Result], path: String): Unit = {
    if(path == ""){
      println("Path is not specified")
      return
    }
    val resultAll = resultSet.zipWithIndex
    val csvheader = List("result-id", "true-positive", "false-positive", "true-negative", "false-negative")
    try {
      val filewriter = new FileWriter(path)
      println("Path!!!" + path)
      filewriter.write(csvheader.mkString(",") + "\n")
      filewriter.write(resultAll.map(x => {
         x._2.toString + "," + x._1.truePos + "," + x._1.falsePos +","+x._1.trueNeg + "," + x._1.falseNeg
      }).mkString("\n"))
    } catch {
      case e: Exception => println("Couldn't write the file to this path")
    }
  }*/

  def prettyPrintResults(res: Result): Unit = {
    println("True positive = " + res.truePos)
    println("False positive = " + res.falsePos)
    println("False negative = " + res.falseNeg)
    println("True negative = " + res.trueNeg)
  }

}
