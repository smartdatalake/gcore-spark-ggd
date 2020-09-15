package ggd.utils

import org.apache.spark.ml.feature.{HashingTF, MinHashLSH}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{col, split}


//min hash similarity join for jaccard distance using tdidf and min hash from spark mlLib
case class MinHashSimJoin(dfA: DataFrame, dfB: DataFrame, colNameA: String, colNameB: String, threshold: Double){

  def sparkHash(numHashes: Int) : DataFrame = {
    val dfAt = dfA.select(col(colNameA.split('$').apply(0)+"$id").alias("id"), split(col(colNameA)," ").as("descArray"))
    val dfAN = dfAt.na.drop()
    val dfBt = dfB.select(col(colNameB.split('$').apply(0)+"$id").alias("id"), split(col(colNameB)," ").as("descArray"))
    val dfBN = dfBt.na.drop()
    val hashingTF = new HashingTF()
      .setInputCol("descArray").setOutputCol("features").setNumFeatures(100)
    val featurizedDataA = hashingTF.transform(dfAN)
    featurizedDataA.show(10)
    val featurizedDataB = hashingTF.transform(dfBN)
    val mh = new MinHashLSH()
      .setNumHashTables(numHashes)
      .setInputCol("features")
      .setOutputCol("hashes")
    val model = mh.fit(featurizedDataA)
    println("Approximately joining dfA and dfB on Jaccard distance smaller than" + threshold)
    val simJoinResult = model.approxSimilarityJoin(featurizedDataA, featurizedDataB, threshold, "JaccardDistance").
      select(col("datasetA.id").alias("idA"),
        col("datasetB.id").alias("idB"),
        col("JaccardDistance"))
    simJoinResult.show(10)
    val join1 = dfA.join(simJoinResult, dfA(colNameA.split('$').apply(0)+"$id") === simJoinResult("idA"), "inner")
    val join2 = join1.join(dfB, join1("idB") === dfB(colNameB.split('$').apply(0)+"$id"), "inner")
    join2
    //simJoinResult
  }

}
