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
    /*val overlap = (s1.split(" ").intersect(s2.split(" "))).toSet.size
    val union = (s1.split(" ").union(s2.split(" "))).toSet.size
    overlap.toDouble/union.toDouble*/
    val dist = new distanceFunctions
    dist.jaccardSimilarity(s1,s2," ")
  });

  val edit_udf = udf((s1 : String, s2 : String) => {
    /*val dist = Array.tabulate(s2.length + 1, s1.length + 1) { (j, i) => if (j == 0) i else if (i == 0) j else 0 }
    @inline
    def minimum(i: Int*): Int = i.min
    for {j <- dist.indices.tail
         i <- dist(0).indices.tail} dist(j)(i) =
      if (s2(j - 1) == s1(i - 1)) dist(j - 1)(i - 1)
      else minimum(dist(j - 1)(i) + 1, dist(j)(i - 1) + 1, dist(j - 1)(i - 1) + 1)
    dist(s2.length)(s1.length)*/
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