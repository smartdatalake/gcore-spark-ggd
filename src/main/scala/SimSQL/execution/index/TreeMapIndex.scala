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

import org.apache.spark.sql.catalyst.InternalRow
/**
 * Code and Method for JaccardSimilarity and Edit Similarity from Dima - project from TsinghuaDatabase Group
 * https://github.com/TsinghuaDatabaseGroup/Dima
 */
/**
 * Created by dong on 1/15/16.
 * Encapsulated TreeMap Index
 */
class TreeMapIndex[T] extends Index with Serializable {
  var index = new java.util.TreeMap[T, Int]()
}

object TreeMapIndex {
  def apply[T](data: Array[(T, InternalRow)]): TreeMapIndex[T] = {
    val res = new TreeMapIndex[T]
    for (i <- data.indices)
      res.index.put(data(i)._1, i)
    res
  }
}