package SimSQL.execution.joins.JoinAlgorithms.joins

import SimSQL.logicalPlan.SimilarityJoinType
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, BindReferences, Expression, JoinedRow, Literal}
import org.apache.spark.sql.catalyst.plans.JoinType
import org.apache.spark.sql.execution.{BinaryExecNode, SparkPlan}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.storage.StorageLevel.DISK_ONLY

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/***
 * Vernica Join implemented by ARC and modified by TU/e
 * for including as physical operator in SparkSQL
 * @param leftKeys
 * @param rightKeys
 * @param joinType
 * @param left
 * @param right
 * @param operator
 * @param thresh
 */

case class VernicaJoin(leftKeys: Expression,
                                     rightKeys: Expression,
                                     joinType: SimilarityJoinType,
                                     left: SparkPlan,
                                     right: SparkPlan,
                                     operator: Literal,
                                     thresh: Literal)
  extends BinaryExecNode {
  final val threshold = thresh.toString.toDouble
  var tokenOrHM_BD: Broadcast[mutable.HashMap[String, (Int, Int)]] = null
  val simThress_BD: Broadcast[Double] = sparkContext.broadcast(threshold)
  //RDD[id, Array[token]]
  var in_RDD: RDD[(InternalRow, Array[String])] = null

  //RDD[idx, (rec-id, Array[tok])]
  var in_RDD_idx: RDD[(Int, (InternalRow, Array[String]))] = null

  //RDD[idx, rec-id]
  var inRDD_2: RDD[(Int, InternalRow)] = null

  private def getInternalRows(longs: Set[Long], map: Map[Long, InternalRow]) : Set[(InternalRow)] = {
    map.filter(r => {
      longs.contains(r._1)
    }).map(_._2).toSet
    //filters the RDD to check if the aritficial ids are in there and returns an RDD of the InternalRows
  }

  override def output: Seq[Attribute] = left.output ++ right.output

  override protected def doExecute(): RDD[InternalRow] = {

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
      if(key == null) print(key)
      (key, row.copy())
    })
    //rdd[(InternalRow, String)]

    val in_RDD1 = left_rdd.map(doc => {
      val shingles = doc._1.toLowerCase.split(" ")//.toList.sliding(shingleLength).map(s => s.mkString).toSet
      (doc._2.copy(), shingles)
    }).persist(StorageLevel.MEMORY_AND_DISK_SER)//collect for quick testing -> to improve implementation afterwards


    val in_RDD2 = right_rdd.map(doc => {
      val shingles = doc._1.toLowerCase.split(" ")//.toList.sliding(shingleLength).map(s => s.mkString).toSet
      (doc._2.copy(), shingles)
    }).persist(StorageLevel.MEMORY_AND_DISK_SER)//collect for quick testing -> to improve implementation afterwards

    val vernicaResult = vernicaJoin(in_RDD1, in_RDD2)

    vernicaResult
    val joinRow = new JoinedRow
    val joinedRows = vernicaResult.map(r => {
      joinRow(r._1.copy(),r._2.copy()).asInstanceOf[InternalRow]
    }
    ).persist(StorageLevel.MEMORY_AND_DISK)

    joinedRows
  }

  def vernicaJoin(in_RDD_1: RDD[(InternalRow, Array[String])], in_RDD_2: RDD[(InternalRow, Array[String])]) = {

    val logArrBuff = ArrayBuffer[String]()

    //RDD[ri, ri_set]
    this.in_RDD = in_RDD_1.union(in_RDD_2)
      .reduceByKey((a, b) => a)
      .persist(StorageLevel.DISK_ONLY)

    //Stage 1. Token Ordering with index.
    //RDD((tok, tokID, tok_count)
    val tokenOrdRDD = this.in_RDD.flatMap(_._2.map((_, 1)))
      .reduceByKey(_ + _)
      .zipWithIndex()
      .map(x => (x._1._1, x._2.toInt, x._1._2))


    //HashMap[tok, (tokID, tok_count)]
    val tokenOrdHM = tokenOrdRDD.aggregate(mutable.HashMap[String, (Int, Int)]())(
      (zHM, x) => {
        zHM += ((x._1, (x._2, x._3)))
      },
      _ ++= _
    )

    //println(s"tokenOrHM size = ${tokenOrdHM.size}")
    logArrBuff += s"tokenOrHM size = ${tokenOrdHM.size}"

    //RDD[idx, (Ri, ri)]. Rdd with index for every Ri.
    this.in_RDD_idx = this.in_RDD.zipWithIndex()
      .map(x => (x._2.toInt, x._1.copy()) )
      .persist(StorageLevel.MEMORY_AND_DISK_SER)

    //We broadcast the HashMap of Tokens.
    this.tokenOrHM_BD = sparkContext.broadcast(tokenOrdHM)

    //Stage 2. RID-Pair Generation. Construction of signature of every Ri.
    //RDD[(Idx(ri), (_Ri_Idx, vec(id)))]
    val rdd1_1 = this.in_RDD_idx.flatMap {
      case (_Ri_idx, (_Ri, set)) => {
        val setSize = set.size
        val pref_length = setSize - math.ceil(setSize * threshold).toInt + 1
        val sortedVec = set.map(x => this.tokenOrHM_BD.value(x)).toVector.sortBy(y => (y._2, y._1)).map(_._1)

        for(i <- 0 until pref_length) yield{
          val (v1, v2) = sortedVec.splitAt(i)
          (sortedVec(i), (_Ri_idx, v1 ++ v2.drop(1)))
        }
      }
    }

    //AggregateByKey (key as sig).
    //Verify records that have at least One Common Sig-id
    //Return an ArrayBuffer[Ri-Sj, jacc_sim]
    rdd1_1.repartition(100)
    val rdd2 = rdd1_1.aggregateByKey((ArrayBuffer[(Int, Vector[Int])](), ArrayBuffer[(Long, Double)]()))(
      (zHMT, xT) => {
        val dArrBuff = zHMT._1
        val jsArrBuff = zHMT._2
        val (rIdx, rVec) = xT
        val rSize = rVec.size + 1

        for((kIdx, kVec) <- dArrBuff){
          val kSize = kVec.size + 1

          //Length Filter
          if(kSize >= (threshold * rSize) && kSize <= (rSize / threshold)){

            val intesectionSize = rVec.intersect(kVec).size + 1
            val js = intesectionSize / (kSize + rSize - intesectionSize).toDouble

            var n = 0L
            if(js >= threshold){
              if(rIdx < kIdx)
                n = (rIdx.toLong << 32) | kIdx.toLong
              else
                n = (kIdx.toLong << 32) | rIdx.toLong

              jsArrBuff.append((n, js))
            }
          }
        }

        dArrBuff.append((rIdx, rVec))

        (dArrBuff, jsArrBuff)
      },
      (zArrBuff_1, zArrBuff_2) => {

        val dArrBuff1  = zArrBuff_1._1
        val jsArrBuff1 = zArrBuff_1._2

        val dArrBuff2  = zArrBuff_2._1
        val jsArrBuff2 = zArrBuff_2._2

        val _jsArrBuff = ArrayBuffer[(Long, Double)]()
        for{
          (rIdx, rVec) <- dArrBuff1
          rSize = rVec.size + 1
          (kIdx, kVec) <- dArrBuff2
        }{
          val kSize = kVec.size + 1
          //Length Filter
          if(kSize >= (threshold * rSize) && kSize <= (rSize / threshold)){

            val intesectionSize = rVec.intersect(kVec).size + 1
            val js = intesectionSize / (kSize + rSize - intesectionSize).toDouble

            var n = 0L
            if(js >= threshold){
              if(rIdx < kIdx)
                n = (rIdx.toLong << 32) | kIdx.toLong
              else
                n = (kIdx.toLong << 32) | rIdx.toLong

              _jsArrBuff.append((n, js))
            }
          }
        }

        (dArrBuff1 ++= dArrBuff2, jsArrBuff1 ++= (jsArrBuff2 ++= _jsArrBuff))
      }
    )


    //Keep Only the distinct elements of ArrayBuffer[Ri-Sj, jacc_sim].
    val rdd3 = rdd2.flatMap{
      x => {
        val jsArrBuff = x._2._2
        jsArrBuff
      }
    }.reduceByKey((a, _) => a)

    //Unzip Long to (R_id, S_id, js)
    val rdd4 = rdd3.map{
      y => {
        val (n1, n2) = ((y._1 >> 32).toInt, (y._1 & (4294967296L - 1)).toInt)
        (n1, n2, y._2)
      }
    }

    //RDD[idx, rec-id]
    this.inRDD_2 = this.in_RDD_idx.map(x => (x._1, x._2._1)).persist(DISK_ONLY)

  //Final result. RDD[Ri, Sj, js]
    val rdd5 = rdd4.map(x => (x._1, (x._2, x._3)))
       .join(this.inRDD_2)
       .map(x => (x._2._1._1, (x._2._2, x._2._1._2)))
       .join(this.inRDD_2)
       .map(x => (x._2._1._1.copy(), x._2._2.copy()))
     //.map(x => (x._2._1._1, x._2._2, x._2._1._2))


    rdd5
  }


  def cleanVN_Tmp_vars() = {
    this.tokenOrHM_BD.destroy()
    this.simThress_BD.destroy()

    this.in_RDD.unpersist()
    this.in_RDD_idx.unpersist()
    this.inRDD_2.unpersist()
  }


}