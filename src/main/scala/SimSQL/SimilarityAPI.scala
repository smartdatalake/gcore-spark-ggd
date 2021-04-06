package SimSQL

import ggd.GcoreRunner
import ggd.utils.Result
import org.apache.spark.sql.DataFrame

import scala.collection.mutable.ArrayBuffer
import scala.io.Source

case class SimilarityAPI(gcoreRunner: GcoreRunner) {

  def SimJoin(left: DataFrame, right: DataFrame, leftKey: String, rightKey: String,
              function: String, threshold: Double, operator: String): DataFrame = {
    //def SimJoin(left: DataFrame, right: DataFrame, leftKey: Column, rightKey: Column,
    //            function: String, threshold: Double, operator: String): DataFrame = {
    left.createOrReplaceTempView("left")
    right.createOrReplaceTempView("right")
    function.toLowerCase match {
      case "jaccardsimilarity" =>{
        //println(" JACCARD SIM AST")
        //val condition = JaccardSimilarity(leftKey.expr, rightKey.expr, Literal(operator), Literal(threshold))
        //SimilarityJoin(left.logicalPlan, right.logicalPlan, joinType = Similarity, Some(condition))
        gcoreRunner.sparkSession.sql("SELECT * FROM left SIMILARITY JOIN right USING JACCARDSIMILARITY(left."+leftKey+", right."+rightKey+") " + operator +" "+threshold)
      }
      case "selfjaccardsimilarity" =>{
        //val condition = SelfJaccardSimilarity(leftKey.expr, Literal(operator), Literal(threshold))
        //SimilarityJoin(logicalPlan, right.logicalPlan, joinType = Similarity, Some(condition))
        gcoreRunner.sparkSession.sql("SELECT * FROM left SIMILARITY JOIN right USING JACCARDSIMILARITY(left."+leftKey+", right."+rightKey+") " + operator +" "+threshold)
      }
      case "editsimilarity" =>
        //val condition = EditSimilarity(leftKey.expr, rightKey.expr, Literal(operator), Literal(threshold))
        //SimilarityJoin(logicalPlan, right.logicalPlan, joinType = Similarity, Some(condition))
        gcoreRunner.sparkSession.sql("SELECT * FROM left SIMILARITY JOIN right USING EDITSIMILARITY(left."+leftKey+", right."+rightKey+") " + operator +" "+threshold)
    }
  }

  def BruteForceSimJoin(left: DataFrame, right: DataFrame, leftKey: String, rightKey: String,
                        function: String, threshold: Double, operator: String): DataFrame = {
    null
  }

  def compareSimJoinBruteForce(left: DataFrame, right: DataFrame, leftKey: String, rightKey: String,
                               function: String, threshold: Double, operator: String): Result = {
    var res = new Result()
    val results = SimJoin(left,right,leftKey,rightKey,function, threshold, operator).select(leftKey, rightKey).collect().map(x => {
      ( x.get(0).toString, x.get(1).toString)
    }).sortBy(x => (x._1, x._2))
    val groundTruth = BruteForceSimJoin(left, right, leftKey, rightKey, function, threshold, operator).select(leftKey, rightKey).collect().map(x => {
      ( x.get(0).toString, x.get(1).toString)
    }).sortBy(x => (x._1, x._2))
    /*res.truePos = groundTruth.intersect(results).size
    res.falsePos = results.diff(groundTruth).size
    res.falseNeg = groundTruth.diff(results).size
    res.trueNeg = 0 //not used yet
    //verificar com a lista de groundtruth e criar result
    res*/
    Result(groundTruth.intersect(results).size, 0, results.diff(groundTruth).size, groundTruth.diff(results).size)
  }

  def compareSimJoinWithFile(left: DataFrame, right: DataFrame, leftKey: String, rightKey: String,
                             function: String, threshold: Double, operator: String, path: String, sep: String): Result = {
    var res = new Result()
    var groundTruth: ArrayBuffer[(String,String)] = new ArrayBuffer[(String, String)]()
    val results = SimJoin(left,right,leftKey,rightKey,function, threshold, operator).select(leftKey, rightKey).collect().map(x => {
      ( x.get(0).toString, x.get(1).toString)
    }).sortBy(x => (x._1, x._2))
    for(line <- Source.fromFile(path).getLines()) {
      val groundTruthPair = line.split(sep)
      val pair: (String,String) = (groundTruthPair.apply(0), groundTruthPair.apply(1))
      groundTruth += pair
    }
    groundTruth.sortBy(x => (x._1, x._2))
    /*res.truePos = groundTruth.intersect(results).size
    res.falsePos = results.diff(groundTruth).size
    res.falseNeg = groundTruth.diff(results).size
    res.trueNeg = 0 //not used yet
    //verificar com a lista de groundtruth e criar result
    return res*/
    Result(groundTruth.intersect(results).size, 0, results.diff(groundTruth).size, groundTruth.diff(results).size)
  }

}