package SimSQL

import ggd.{GcoreRunner, distanceFunctions}
import ggd.utils.Result
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.udf

import scala.collection.mutable.ArrayBuffer
import scala.io.Source

case class SimilarityAPI(gcoreRunner: GcoreRunner) {


  def SimJoin(left: DataFrame, right: DataFrame, leftKey: String, rightKey: String,
              function: String, threshold: Double, operator: String): DataFrame = {
    val leftRenamed = left.withColumnRenamed(leftKey, "left_key_sim").createOrReplaceTempView("left")
    val rightRenamed = right.withColumnRenamed(rightKey, "right_key_sim").createOrReplaceTempView("right")
    //the spark.sql function is not identifying correclty columns with alias using var$column format --> quick fix:
    function.toLowerCase match {
      case "jaccardsimilarity" =>{
        gcoreRunner.sparkSession.sql("SELECT * FROM left SIMILARITY JOIN right USING JACCARDSIMILARITY(left.left_key_sim, right.right_key_sim) " + operator +" "+threshold)
          .withColumnRenamed("left_key_sim", leftKey)
          .withColumnRenamed("right_key_sim", rightKey)
      }
      case "selfjaccardsimilarity" =>{
        gcoreRunner.sparkSession.sql("SELECT * FROM left SIMILARITY JOIN right USING JACCARDSIMILARITY(left.left_key_sim, right.right_key_sim) " + operator +" "+threshold)
          .withColumnRenamed("left_key_sim", leftKey)
          .withColumnRenamed("right_key_sim", rightKey)
      }
      case "editsimilarity" =>
        gcoreRunner.sparkSession.sql("SELECT * FROM left SIMILARITY JOIN right USING EDITSIMILARITY(left.left_key_sim, right.right_key_sim) " + operator +" "+threshold)
          .withColumnRenamed("left_key_sim", leftKey)
          .withColumnRenamed("right_key_sim", rightKey)    }
  }

  //Not recommended for big datasets, just for testing purposes
  def BruteForceSimJoin(left: DataFrame, right: DataFrame, leftKey: String, rightKey: String,
                        function: String, threshold: Double, operator: String): DataFrame = {
    function.toLowerCase match {
      case "jaccardsimilarity" =>{
        if(operator == "<") left.join(right, jaccard_udf(left.col(leftKey), right.col(rightKey)) < threshold)
        else  left.join(right, jaccard_udf(left.col(leftKey), right.col(rightKey)) <= threshold)
      }
      case "selfjaccardsimilarity" =>{
        if(operator == "<") left.join(right, jaccard_udf(left.col(leftKey), right.col(rightKey)) < threshold)
        else  left.join(right, jaccard_udf(left.col(leftKey), right.col(rightKey)) <= threshold)
       }
      case "editsimilarity" =>
        if(operator == "<") left.join(right, edit_udf(left.col(leftKey), right.col(rightKey)) < threshold)
        else  left.join(right, edit_udf(left.col(leftKey), right.col(rightKey)) <= threshold)
    }
  }

  val jaccard_udf = udf((s1 : String, s2 : String) => {
    val dist = new distanceFunctions
    dist.jaccardSimilarity(s1,s2," ")
  });

  val edit_udf = udf((s1 : String, s2 : String) => {
    val dist = new distanceFunctions
    dist.editDistance(s1,s2)
  });

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
    res.trueNeg = 0 //not used yet*/
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
    res.trueNeg = 0 //not used yet*/
    Result(groundTruth.intersect(results).size, 0, results.diff(groundTruth).size, groundTruth.diff(results).size)
  }

}