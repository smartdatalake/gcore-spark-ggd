package SimSQL.logicalPlan

import java.util.Locale


object SimilarityJoinType {
  def apply(typ: String): SimilarityJoinType = typ.toLowerCase(Locale.ROOT).replace("_", "") match {
    case "similarity" => Similarity
    case _ =>
      val supported = Seq("similarity")

      throw new IllegalArgumentException(s"Unsupported join type '$typ'. " +
        "Supported join types include: " + supported.mkString("'", "', '", "'") + ".")
  }
}

sealed abstract class SimilarityJoinType {
  def sql: String
}

case object Similarity extends SimilarityJoinType {
  override def sql: String = "SIMILARITY JOIN"
}