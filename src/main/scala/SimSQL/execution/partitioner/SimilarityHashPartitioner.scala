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
package SimSQL.execution.partitioner

/**
 * Code and Method for JaccardSimilarity and Edit Similarity from Dima - project from TsinghuaDatabase Group
 * https://github.com/TsinghuaDatabaseGroup/Dima
 */
/**
  * Created by sunji on 16/10/15.
  */
import org.apache.spark.Partitioner
import org.apache.spark.broadcast.Broadcast

class SimilarityHashPartitioner(numParts: Int,
                                frequencyTable: Broadcast[scala.collection.immutable.Map[Int, Int]]
                               ) extends Partitioner {

  override def numPartitions: Int = numParts
  def hashStrategy(key: Any): Int = {
    val code = (key.hashCode % numPartitions)
    if (code < 0) {
      code + numPartitions
    } else {
      code
    }
  }
  override def getPartition(key: Any): Int = {
    val k = key.hashCode()
    frequencyTable.value.getOrElse(k, hashStrategy(k))
  }
  override def equals(other: Any): Boolean = other match {
    case similarity: SimilarityHashPartitioner =>
      similarity.numPartitions == numPartitions
    case _ =>
      false
  }
  override def hashCode: Int = numPartitions
}
