package ggd.utils

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/*
 * Class for performing the Vernica Join Algorithm.
 * Code by Athena Research Center
 */

case class VernicaJoinAthena(val simThress: Double, sparkSession: SparkSession) {

  import sparkSession.implicits._

  var tokenOrHM_BD: Broadcast[mutable.HashMap[String, (Int, Int)]] = null
  val simThress_BD: Broadcast[Double] = sparkSession.sparkContext.broadcast(simThress)

  //RDD[id, Array[token]]
  var in_RDD: RDD[(String, Array[String])] = null

  //RDD[idx, (rec-id, Array[tok])]
  var in_RDD_idx: RDD[(Int, (String, Array[String]))] = null

  //RDD[idx, rec-id]
  var inRDD_2: RDD[(Int, String)] = null


  //def vernicaJoin(in_RDD_1: RDD[(String, Array[String])], in_RDD_2: RDD[(String, Array[String])]) = {
  def vernicaJoin(in_RDD1: RDD[(String, String)], in_RDD2: RDD[(String, String)], id1: String, id2: String) = {

    val in_RDD_1 = in_RDD1.map(x => {
      (x._1, x._2.split(" "))
    })

    val in_RDD_2 = in_RDD2.map(x =>{
      (x._1, x._2.split(" "))
    })

      val logArrBuff = ArrayBuffer[String]()

    //RDD[ri, ri_set]
    this.in_RDD = in_RDD_1.union(in_RDD_2)
      .reduceByKey((a, b) => a)
      .persist(DISK_ONLY)

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
      .map(x => (x._2.toInt, x._1) )
      .persist(DISK_ONLY)

    //We broadcast the HashMap of Tokens.
    this.tokenOrHM_BD = sparkSession.sparkContext.broadcast(tokenOrdHM)

    //Stage 2. RID-Pair Generation. Construction of signature of every Ri.
    //RDD[(Idx(ri), (_Ri_Idx, vec(id)))]
    val rdd1_1 = this.in_RDD_idx.flatMap {
      case (_Ri_idx, (_Ri, set)) => {
        val setSize = set.size
        val pref_length = setSize - math.ceil(setSize * simThress).toInt + 1
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
    val rdd2 = rdd1_1.aggregateByKey((ArrayBuffer[(Int, Vector[Int])](), ArrayBuffer[(Long, Double)]()))(
      (zHMT, xT) => {
        val dArrBuff = zHMT._1
        val jsArrBuff = zHMT._2
        val (rIdx, rVec) = xT
        val rSize = rVec.size + 1

        for((kIdx, kVec) <- dArrBuff){
          val kSize = kVec.size + 1

          //Length Filter
          if(kSize >= (simThress * rSize) && kSize <= (rSize / simThress)){

            val intesectionSize = rVec.intersect(kVec).size + 1
            val js = intesectionSize / (kSize + rSize - intesectionSize).toDouble

            var n = 0L
            if(js >= simThress){
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
          if(kSize >= (simThress * rSize) && kSize <= (rSize / simThress)){

            val intesectionSize = rVec.intersect(kVec).size + 1
            val js = intesectionSize / (kSize + rSize - intesectionSize).toDouble

            var n = 0L
            if(js >= simThress){
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
      .map(x => (x._2._1._1, x._2._2, x._2._1._2))
      .filter(x => x._1 != x._2)

    val inRDD1 = in_RDD1.map(x => x._1).toDF(id1)
    val inRDD2 = in_RDD2.map(x => x._1).toDF(id2)
    val resultRDD = rdd5.toDF(id1, id2, "similarity")

    resultRDD.join(inRDD1, id1).join(inRDD2, id2).drop("similarity").dropDuplicates()

    //(rdd5, logArrBuff)
    //rdd5
  }


  def cleanVN_Tmp_vars() = {
    this.tokenOrHM_BD.destroy()
    this.simThress_BD.destroy()

    this.in_RDD.unpersist()
    this.in_RDD_idx.unpersist()
    this.inRDD_2.unpersist()
  }

}


