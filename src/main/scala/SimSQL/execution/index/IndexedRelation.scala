/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package SimSQL.execution.index

import SimSQL.execution.partitioner.{HashPartition, SimilarityHashPartitioner}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.MultiInstanceRelation
import org.apache.spark.sql.catalyst.expressions.{Attribute, BindReferences}
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, Statistics}
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.storage.StorageLevel

import scala.collection.mutable.ArrayBuffer
/**
 * Code and Method for JaccardSimilarity and Edit Similarity from Dima - project from TsinghuaDatabase Group
 * https://github.com/TsinghuaDatabaseGroup/Dima
 */
/**
 * Created by dong on 1/15/16.
 * Indexed Relation Structures for Simba
 */



private case class PackedPartitionWithIndex(data: Array[InternalRow], index: Index)

private object IndexedRelation {
  def apply(child: SparkPlan, table_name: Option[String], index_type: IndexType,
            column_keys: List[Attribute], index_name: String): IndexedRelation = {
    index_type match {
      case JaccardIndexType =>
        new JaccardIndexIndexedRelation(child.output, child, table_name, column_keys, index_name)()
      case _ => null
    }
  }
}

private abstract class IndexedRelation extends LogicalPlan {
  self: Product =>
  var _indexedRDD: RDD[PackedPartitionWithIndex]
  def indexedRDD: RDD[PackedPartitionWithIndex] = _indexedRDD

  override def children: Seq[LogicalPlan] = Nil
  def output: Seq[Attribute]

  def withOutput(newOutput: Seq[Attribute]): IndexedRelation
}

private case class HashMapIndexedRelation(
  output: Seq[Attribute],
  child: SparkPlan,
  table_name: Option[String],
  column_keys: List[Attribute],
  index_name: String)(var _indexedRDD: RDD[PackedPartitionWithIndex] = null)
  extends IndexedRelation with MultiInstanceRelation {
  require(column_keys.length == 1)
  val numShufflePartitions = child.sqlContext.getConf("spark.sql.shuffle.partitions").toInt//child.sqlContext.conf.numShufflePartitions
  val maxEntriesPerNode = 25//child.sqlContext.conf.maxEntriesPerNode
  val sampleRate = 0.01//child.sqlContext.conf.sampleRate
  val transferThreshold = Some(800 * 1024 * 1024).get//child.sqlContext.conf.transferThreshold

  if (_indexedRDD == null) {
    buildIndex()
  }

  private def buildIndex(): Unit = {
    val dataRDD = child.execute().map(row => {
      val key = BindReferences.bindReference(column_keys.head, child.output).eval(row)
      (key, row)
    })

    val partitionedRDD = HashPartition(dataRDD, numShufflePartitions)
    val indexed = partitionedRDD.mapPartitions(iter => {
      val data = iter.toArray
      val index = HashMapIndex(data)
      Array(PackedPartitionWithIndex(data.map(_._2), index)).iterator
    }).persist(StorageLevel.MEMORY_AND_DISK_SER)

    indexed.setName(table_name.map(n => s"$n $index_name").getOrElse(child.toString))
    _indexedRDD = indexed
  }

  override def newInstance(): IndexedRelation = {
    new HashMapIndexedRelation(output.map(_.newInstance()), child, table_name,
      column_keys, index_name)(_indexedRDD).asInstanceOf[this.type]
  }

  override def withOutput(new_output: Seq[Attribute]): IndexedRelation = {
    new HashMapIndexedRelation(new_output, child, table_name, column_keys, index_name)(_indexedRDD)
  }

  @transient lazy val statistics = Statistics(
    // TODO: Instead of returning a default value here, find a way to return a meaningful size
    // estimate for RDDs. See PR 1238 for more discussions.
    sizeInBytes = BigInt(child.sqlContext.getConf("spark.sql.defaultSizeInBytes").toInt)//BigInt(child.sqlContext.conf.defaultSizeInBytes)
  )
}

/**
  * Created by sunji on 16/10/15.
  */

case class IPartition(partitionId: Int,
                                   index: Index,
                                   data: Array[((Int,
                                     InternalRow,
                                     Array[(Array[Int], Array[Boolean])]),
                                     Boolean)])

private case class IndexGlobalInfo(threshold: Double,
                           frequencyTable: Broadcast[scala.collection.Map[(Int, Boolean), Long]],
                           multiGroup: Broadcast[Array[(Int, Int)]],
                           minimum: Int,
                           alpha: Double,
                           partitionNum: Int,
                           threshold_base: Double) extends Serializable

private case class JaccardIndexIndexedRelation(
  output: Seq[Attribute],
  child: SparkPlan,
  table_name: Option[String],
  column_keys: List[Attribute],
  index_name: String)(var _indexedRDD: RDD[PackedPartitionWithIndex] = null,
                      var indexRDD: RDD[IPartition] = null,
                      var indexGlobalInfo: IndexGlobalInfo = null)
  extends IndexedRelation with MultiInstanceRelation {
  require(column_keys.length == 1)
  val numPartitions = 2//sqlContext.conf.numSimilarityPartitions
  val threshold = 0.8//thresh.toString.toInt
  val alpha = 0.95//sqlContext.conf.similarityMultigroupThreshold
  val topDegree = 0//sqlContext.conf.similarityBalanceTopDegree
  val abandonNum = 2//sqlContext.conf.similarityFrequencyAbandonNum


  if (indexRDD == null) {
    buildIndex()
  }

  private def multigroup(
                               mini: Int,
                               maxi: Int,
                               threshold: Double,
                               alpha : Double): Array[(Int, Int)] = {
    var result = ArrayBuffer[(Int, Int)]()
    var l = mini
    while (l <= maxi) {
      val l1 = Math.floor(l / alpha + 0.0001).toInt
      result += Tuple2(l, l1)
      l = l1 + 1
    }
    result.toArray
  }

  private def CalculateH ( l: Int, s: Int, threshold: Double ) = {
    Math.floor((1 - threshold) * (l + s) / (1 + threshold) + 0.0001).toInt + 1
  }

  private def CalculateH1 ( l: Int, threshold: Double ): Int = {
    // 生成分段的段数(按照query长度)
    Math.floor ( (1 - threshold) * l / threshold + 0.0001).toInt + 1
  }

  private def segNum(s: String, n: Int): Int = {
    val hash = s.hashCode % n
    if (hash >= 0) {
      hash + 1
    } else {
      hash + n + 1
    }
  }

  private def createInverse(ss1: String,
                                 group: Array[(Int, Int)],
                                 threshold: Double
                                ): Array[(String, Int, Int)] = {
    {
      val ss = ss1.split(" ").filter(x => x.length > 0)
      val range = group.filter(
        x => (x._1 <= ss.length && x._2 >= ss.length)
      )
      val sl = range(range.length-1)._1
      val H = CalculateH1(sl, threshold)
      logInfo(s"createInverse: H: " + H.toString)
      for (i <- 1 until H + 1) yield {
        val s = ss.filter(x => {segNum(x, H) == i})
        if (s.length == 0) {
          Tuple3("", i, sl)
        } else if (s.length == 1) {
          Tuple3(s(0), i, sl)
        } else {
          Tuple3(s.reduce(_ + " " + _), i, sl)
        }
      }
    }.toArray
  }

  private def createDeletion(ss1: String): Array[String] = {
    {
      val ss = ss1.split(" ")
      if (ss.length == 1) {
        Array("")
      } else if (ss.length == 2) {
        Array(ss(0), ss(1))
      } else {
        for (s <- 0 until ss.length) yield {
          Array.concat(ss.slice(0, s), ss.slice(s + 1, ss.length)).reduce(_ + " " + _)
        }
      }.toArray
    }
  }

  def sort(xs: Array[String]): Array[String] = {
    if (xs.length <= 1) {
      xs
    } else {
      val pivot = xs(xs.length / 2)
      Array.concat(
        sort(xs filter (pivot >)),
        xs filter (pivot ==),
        sort(xs filter (pivot <))
      )
    }
  }

  def sortByValue(x: String): String = {
    sort(x.split(" ")).reduce(_ + " " + _)
  }

  private def buildIndex(): Unit = {

    val dataRDD = child.execute().map(row => {
      val key = BindReferences.bindReference(column_keys.head, child.output).eval(row)
      (key, row.copy())
    })

    val rdd = dataRDD
      .map(t => (t._1.asInstanceOf[org.apache.spark.unsafe.types.UTF8String].toString.split(" "),
        t._2))

    val rdd1 = rdd.map(x => x._1.length).persist(StorageLevel.DISK_ONLY)
    val minimum = child.sqlContext.sparkContext.broadcast(rdd1.min())
    val maximum = child.sqlContext.sparkContext.broadcast(rdd1.max())
    val count = child.sqlContext.sparkContext.broadcast(rdd1.count())
    val average = child.sqlContext.sparkContext.broadcast(rdd1.sum() / count.value)

    val multiGroup = child.sqlContext.sparkContext.broadcast(
      multigroup(minimum.value, maximum.value, threshold, alpha
      )
    )

    val inverseRDD = dataRDD
      .map(t => (sortByValue(t._1.asInstanceOf[org.apache.spark.unsafe.types.UTF8String].toString),
        t._2))

    val splittedRecord = inverseRDD
      .map(x => {
        ((x._1, x._2), createInverse(x._1, multiGroup.value, threshold))
      })
      .flatMapValues(x => x)
      .map(x => ((x._1, x._2._2, x._2._3), x._2._1))

    val deletionIndexSig = splittedRecord
      .filter(x => (x._2.length > 0))
      .map(x => (x._1, createDeletion(x._2))) // (1,i,l), deletionSubstring
      .flatMapValues(x => x)
      .map(x => {
        ((x._2, x._1._2, x._1._3).hashCode(), (x._1._1, true))
      })
    // (hashCode, (String, internalrow))

    val segIndexSig = splittedRecord
      .map(x => {
        ((x._2, x._1._2, x._1._3).hashCode(), (x._1._1, false))
      })

    val index = deletionIndexSig.union(segIndexSig).persist(StorageLevel.DISK_ONLY)

    val f = index
      .map(x => ((x._1, x._2._2), 1L))
      .reduceByKey(_ + _)
      .filter(x => x._2 > abandonNum)
      .persist

    val frequencyTable = child.sqlContext.sparkContext.broadcast(
      f.collectAsMap()
    )

    val partitionTable = child.sqlContext.sparkContext.broadcast(Array[(Int, Int)]().toMap)

    val partitionedRDD = index
      .partitionBy(new SimilarityHashPartitioner(numPartitions, partitionTable))

    val indexed = partitionedRDD.mapPartitionsWithIndex((partitionId, iter) => {
      val data = iter.toArray
      val index = JaccardIndex(data,
        threshold, frequencyTable, multiGroup, minimum.value, alpha, numPartitions)
      Array(IPartition(partitionId, index, data
        .map(x => ((sortByValue(x._2._1._1).hashCode,
          x._2._1._2,
          createInverse(sortByValue(x._2._1._1),
            multiGroup.value,
            threshold)
        .map(x => {
          if (x._1.length > 0) {
            (x._1.split(" ").map(s => s.hashCode), Array[Boolean]())
          } else {
            (Array[Int](), Array[Boolean]())
          }
        })), x._2._2)))).iterator
    }).persist(StorageLevel.MEMORY_AND_DISK_SER)
    indexed.count

    indexGlobalInfo = new IndexGlobalInfo(threshold,
      frequencyTable, multiGroup, minimum.value, alpha, numPartitions, threshold)

    indexed.setName(table_name.map(n => s"$n $index_name").getOrElse(child.toString))
    indexRDD = indexed
  }

  override def newInstance(): IndexedRelation = {
    new JaccardIndexIndexedRelation(output.map(_.newInstance()), child, table_name,
      column_keys, index_name)(_indexedRDD, indexRDD, indexGlobalInfo).asInstanceOf[this.type]
  }

  override def withOutput(new_output: Seq[Attribute]): IndexedRelation = {
    new JaccardIndexIndexedRelation(new_output,
      child, table_name, column_keys, index_name)(_indexedRDD, indexRDD, indexGlobalInfo)
  }

  @transient lazy val statistics = Statistics(
    // TODO: Instead of returning a default value here, find a way to return a meaningful size
    // estimate for RDDs. See PR 1238 for more discussions.
    sizeInBytes = BigInt(child.sqlContext.getConf("spark.sql.defaultSizeInBytes").toInt)//BigInt(child.sqlContext.conf.defaultSizeInBytes)
  )
}