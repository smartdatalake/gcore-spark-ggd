package SimSQL

import ggd.GcoreRunner
import org.apache.spark.sql.DataFrame

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

}