package ggd.utils

import org.apache.spark.sql.DataFrame

import scala.collection.mutable.ArrayBuffer

object DataFrameUtils {

  def dropColumns(inputDF: DataFrame, dropList: List[String]): DataFrame =
    dropList.foldLeft(inputDF)((df, col) => df.drop(col))

  def removeDuplicateColumns(dataFrame:DataFrame): DataFrame = {
    var allColumns:  ArrayBuffer[String] = new ArrayBuffer[String]()
    val dup_Columns: ArrayBuffer[String] = new ArrayBuffer[String]()
    val columns = dataFrame.columns
    for(i <- columns){
     // if(allColumns.contains(i)) {
        if(allColumns.contains(i)) {
          allColumns += "dup_" + i
          dup_Columns += "dup_" +i
        }else{
          allColumns += i
        }
     // }
    }
    println(allColumns.size)
    val columnSeq = allColumns.toSeq
    println(columnSeq.mkString(" , "))
    val df = dataFrame.toDF(columnSeq:_*)
    val unDF = df.drop(dup_Columns:_*)
    unDF
  }

  def columnsStartingWith(inputDf: DataFrame, prefix: List[String]) : Array[String] = {
    def colNameStartsWith: String => Boolean = (colName: String) =>
      prefix.map(p => colName.startsWith(p)).reduce(_ || _)
    // Filter columns list by checking against given prefixes sequence
    val columns = inputDf.columns.filter(colNameStartsWith)
    columns
  }


}
