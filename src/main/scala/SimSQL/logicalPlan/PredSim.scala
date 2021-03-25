package SimSQL.logicalPlan

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.catalyst.expressions.{Expression, Literal, Predicate}

object JaccardSimilarity {
  def jaccardSimilarity(s1: String, s2: String, delta: Int = 10000): Double = {
    val overlap = (s1.split(" ").intersect(s2.split(" "))).length
    overlap.toDouble / (s1.length + s2.length - overlap).toDouble
  }
  def apply(s1: String, s2: String): Double = jaccardSimilarity(s1, s2)
}

case class JaccardSimilarity(string: Expression,
                             target: Expression,
                             operator: Literal,
                             delta: Literal) extends Predicate with CodegenFallback {
  //  require(delta.dataType.isInstanceOf[DoubleType])
//  println(string.toString())
  //println("Jaccard Similarity!! PredSim")
  //println(operator.toString())
  //println(delta.value.asInstanceOf[org.apache.spark.sql.types.Decimal].toDouble)
  override def children: Seq[Expression] =
    Seq(string, target, operator, delta, Literal("JaccardSimilarity"))

  override def nullable: Boolean = false

  override def toString: String = s" **($string) IN JACCARD SIMILARITY ($target) within ($delta) with operator ($operator)"

  /** Returns the result of evaluating this expression on a given input Row */
  override def eval(input: InternalRow): Any = {
    val eval_string = string.eval(input).toString
    val eval_target = target.eval(input).toString
    val eval_operator = operator.toString()
    val eval_delta = delta.value.asInstanceOf[org.apache.spark.sql.types.Decimal].toDouble

    //JaccardSimilarity.jaccardSimilarity(eval_string, eval_target) <= eval_delta
    eval_operator match {
      case "<" => JaccardSimilarity.jaccardSimilarity(eval_string, eval_target) < eval_delta
      case ">" => JaccardSimilarity.jaccardSimilarity(eval_string, eval_target) > eval_delta
      case "<=" => JaccardSimilarity.jaccardSimilarity(eval_string, eval_target) <= eval_delta
      case ">=" => JaccardSimilarity.jaccardSimilarity(eval_string, eval_target) >= eval_delta
      case "=" => JaccardSimilarity.jaccardSimilarity(eval_string, eval_target) == eval_delta
      case "<>" => JaccardSimilarity.jaccardSimilarity(eval_string, eval_target) != eval_delta
    }
  }
}

object SelfJaccardSimilarity {
  def jaccardSimilarity(s1: String, s2: String, delta: Int = 10000): Double = {
    val overlap = (s1.split(" ").intersect(s2.split(" "))).length
    overlap.toDouble / (s1.length + s2.length - overlap).toDouble
  }
  def apply(s1: String, s2: String): Double = jaccardSimilarity(s1, s2)
}

case class SelfJaccardSimilarity(string: Expression,
                             operator: Literal,
                             delta: Literal) extends Predicate with CodegenFallback {
  //  require(delta.dataType.isInstanceOf[DoubleType])
  //  println(string.toString())
  //println("Jaccard Similarity!! PredSim")
  //println(operator.toString())
  //println(delta.value.asInstanceOf[org.apache.spark.sql.types.Decimal].toDouble)
  override def children: Seq[Expression] =
    Seq(string, operator, delta, Literal("SelfJaccardSimilarity"))

  override def nullable: Boolean = false

  override def toString: String = s" **($string) IN SELFJACCARD SIMILARITY ($string) within ($delta) with operator ($operator)"

  /** Returns the result of evaluating this expression on a given input Row */
  override def eval(input: InternalRow): Any = {
    val eval_string = string.eval(input).toString
    val eval_target = string.eval(input).toString
    val eval_operator = operator.toString()
    val eval_delta = delta.value.asInstanceOf[org.apache.spark.sql.types.Decimal].toDouble

    //JaccardSimilarity.jaccardSimilarity(eval_string, eval_target) <= eval_delta
    eval_operator match {
      case "<" => JaccardSimilarity.jaccardSimilarity(eval_string, eval_target) < eval_delta
      case ">" => JaccardSimilarity.jaccardSimilarity(eval_string, eval_target) > eval_delta
      case "<=" => JaccardSimilarity.jaccardSimilarity(eval_string, eval_target) <= eval_delta
      case ">=" => JaccardSimilarity.jaccardSimilarity(eval_string, eval_target) >= eval_delta
      case "=" => JaccardSimilarity.jaccardSimilarity(eval_string, eval_target) == eval_delta
      case "<>" => JaccardSimilarity.jaccardSimilarity(eval_string, eval_target) != eval_delta
    }
  }
}



object EditSimilarity {
  def editSimilarity(s1: String, s2: String, delta: Int = 10000): Int = {
    if (math.abs(s1.length - s2.length) > delta) {
      return (delta + 1)
    }

    val dist = Array.tabulate(s2.length + 1, s1.length + 1){
      (j, i) => if (j == 0) i else if (i == 0) j else 0
    }

    for (j <- 1 to s2.length; i <- 1 to s1.length)
      dist(j)(i) = if (s2(j - 1) == s1(i - 1)) dist(j - 1)(i - 1)
      else math.min(math.min(dist(j - 1)(i) + 1, dist(j)(i - 1) + 1), dist(j - 1)(i - 1) + 1)

    dist(s2.length)(s1.length)
  }

  def apply(s1: String, s2: String): Double = editSimilarity(s1, s2)
}

case class EditSimilarity(string: Expression,
                        target: Expression,
                        operator: Literal,
                        delta: Literal) extends Predicate with CodegenFallback {
  //  require(delta.dataType.isInstanceOf[DoubleType])

  override def children: Seq[Expression] =
    Seq(string, target,operator, delta, Literal("EditSimilarity"))

  override def nullable: Boolean = false

  override def toString: String = s" **($string) IN EDIT SIMILARITY ($target) within ($delta) with operator ($operator)"

  /** Returns the result of evaluating this expression on a given input Row */
  override def eval(input: InternalRow): Any = {
    val eval_string = string.eval(input).toString
    val eval_target = target.eval(input).toString
    val eval_operator = operator.toString()
    val eval_delta = delta.value.asInstanceOf[java.lang.Integer]

    eval_operator match {
      case "<" => EditSimilarity.editSimilarity(eval_string, eval_target) < eval_delta
      case ">" => EditSimilarity.editSimilarity(eval_string, eval_target) > eval_delta
      case "<=" => EditSimilarity.editSimilarity(eval_string, eval_target) <= eval_delta
      case ">=" => EditSimilarity.editSimilarity(eval_string, eval_target) >= eval_delta
      case "=" => EditSimilarity.editSimilarity(eval_string, eval_target) == eval_delta
      case "<>" => EditSimilarity.editSimilarity(eval_string, eval_target) != eval_delta
    }

    //EditSimilarity.editSimilarity(eval_string, eval_target) <= eval_delta
    //    true
  }
}