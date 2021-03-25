package SimSQL.execution.joins

import SimSQL.execution.joins.JoinAlgorithms.LSH
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.execution._

case class JaccardSimilarityJoinExec(leftKeys: Expression,
                                     rightKeys: Expression,
                                     joinType: JoinType,
                                     left: SparkPlan,
                                     right: SparkPlan,
                                     operator: Literal,
                                     thresh: Literal)
  extends BinaryExecNode {
  final val threshold = thresh.toString.toDouble

  override def output: Seq[Attribute] = left.output ++ right.output

  /*private[sql] def tokenizeStringsHash(ss1: String, row: InternalRow): (InternalRow, Seq[(String, Int)]) = {
    val s = ss1.split(" ").filter(x => x.length > 0 | x !=null).map(r => r.toLowerCase)
      .zipWithIndex //tirar os nulos -- array de strings
    //tranformar em hash codes
    return (row,s)
  } //all to lower case*/

  private def getInternalRows(longs: Set[Long], map: Map[Long, InternalRow]) : Set[(InternalRow)] = {
    map.filter(r => {
      longs.contains(r._1)
    }).map(_._2).toSet
    //filters the RDD to check if the aritficial ids are in there and returns an RDD of the InternalRows
  }

  private def sort(xs: Array[String]): Array[String] = {
    if (xs.length <= 1) {
      xs
    } else {
      val pivot = xs(xs.length / 2)
      Array.concat(
        sort(xs filter (pivot.hashCode > _.hashCode)),
        xs filter (pivot.hashCode == _.hashCode),
        sort(xs filter (pivot.hashCode < _.hashCode))
      )
    }
  }

  private def sortByValue(x: String): String = {
    sort(x.split(" ")).reduce(_ + " " + _)
  }


  override protected def doExecute(): RDD[InternalRow] = {
    //left.execute()
     val left_rdd = left.execute().map(row =>
    {
      val key = BindReferences
        .bindReference(leftKeys, left.output)
        .eval(row)
        .asInstanceOf[org.apache.spark.unsafe.types.UTF8String]
        .toString
      (key, row.copy())
    })

    val right_rdd = right.execute().map(row =>
    {
      val key = BindReferences
        .bindReference(rightKeys, right.output)
        .eval(row)
        .asInstanceOf[org.apache.spark.unsafe.types.UTF8String]
        .toString
      (key, row.copy())
    })
    //rdds contains strings of the keys as "first of the pair"
    //tokenize and turn into hashList
    //some stats on the strings
    //left.execute()
   val recordIds = left_rdd.zipWithUniqueId()

    val recordInternalRowId = sparkContext.broadcast(recordIds.map(r => {
      (r._2, r._1._2)
    }).collect().toMap)

    val recordStringId = recordIds.map(r => {
      (r._2, r._1._1)
    })//.persist(StorageLevel.MEMORY_ONLY)


    //left transformed in hash codes
    /*val splittedRecord = record.map(x => {
      ((x._1, x._2), tokenizeStringsHash(x._1, x._2))
    })*/
     // .flatMapValues(x => x)
     // .map(x => ((x._1., x._2._2), x._2._1))
    //println(splittedRecord.collect().mkString(","))

    //val lsh: LSH = new LSH(shingleLength = 3, minHashLength = 100, numberBands = 10, splittedRecord.map(r => r._2), threshold)

    val lsh: LSH = new LSH(shingleLength = 2, minHashLength = 10, numberBands =5, recordStringId, threshold, sparkContext)
    val similarLSH = right_rdd.map(r => (r._2, lsh.findSimilar(r._1))) //similar sets of int

    val joinRow = new JoinedRow
    val joinedRows = similarLSH.map(r => {
      val leftRows : Set[(InternalRow)] = getInternalRows(r._2, recordInternalRowId.value)
      leftRows.map( lr => joinRow(lr.copy(), r._1.copy()).asInstanceOf[InternalRow])
      //(r._1, leftRows)
    })
    val resultRDD = joinedRows.reduce(_ ++ _).toSeq
    val result = sparkContext.makeRDD(resultRDD)
    println("Size result:" + result.collect().size)
    result
    /*val join = new JoinedRow
    val leftRows = left_rdd.map(_._2)
    val rightRows = right_rdd.map(_._2)
    val result = leftRows.cartesian(rightRows).map(r => join(r._1,r._2).asInstanceOf[InternalRow])
    result*/
  }
}
