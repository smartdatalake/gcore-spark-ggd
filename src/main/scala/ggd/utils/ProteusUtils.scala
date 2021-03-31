package ggd.utils

import java.sql.ResultSet

import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.types.{DataType, DoubleType, FloatType, IntegerType, LongType, StringType, StructField, StructType}

import scala.collection.mutable.ArrayBuffer

object ProteusUtils{

  /**
   * Extract Schema from a result set and uses sep as the separator between variable and colummname
   * Proteus does not support renaming with special characters or strings
   * Using as a substitute the string 0_0 as temporary separator in the Proteus Compiler
   */
  def extractSchema(rs: ResultSet, sep: String): StructType = {
    val rsmd = rs.getMetaData
    // The column count starts from 1
    var header = new ArrayBuffer[(String, String)]()
    var structFields = new ArrayBuffer[StructField]()
    for(i <- 1 to rsmd.getColumnCount){
      var colName = rsmd.getColumnName(i)
      if(!sep.equals("")){
        colName = rsmd.getColumnName(i).replaceAll(sep, "\\$")
      }
      //structFields += StructField(rsmd.getColumnName(i), getType(rsmd.getColumnTypeName(i)))
      structFields += StructField(colName, getType(rsmd.getColumnTypeName(i)))
    }
    StructType(structFields)
  }

  def getType(str: String): DataType = {
    str match {
      case "int" => IntegerType
      case "varchar" => StringType
      case "float" => FloatType
      case "double" => DoubleType
      case "bigint" => LongType
      case _ => StringType
    }
  }

  def parseResultSet(rs: ResultSet): Row = {
    val rsmd = rs.getMetaData
    val columnCount = rsmd.getColumnCount
    // The column count starts from 1
    var header = new ArrayBuffer[(String, String)]()
    for (i <- 1 to columnCount) {
      header += ((rsmd.getColumnName(i), rsmd.getColumnTypeName(i)))
      // Do stuff with name
    }
    val resultSetRecord = header.map(c => {
      if(rs.getString(c._1).endsWith("\n")){
        val value = rs.getString(c._1).patch(rs.getString(c._1).lastIndexOf('\n'), "", 1)
        c._2.toLowerCase match {
          case "int" => value.asInstanceOf[Int]
          case "varchar" => value.asInstanceOf[String]
          case "float" => value.asInstanceOf[Float]
          case "double" => value.asInstanceOf[Double]
          case "bigint" => value.asInstanceOf[Long]
          case _ => value.asInstanceOf[String]
        }
      }else{
        val value = rs.getString(c._1)
        //valueConversionScala(c._2, str)
        c._2.toLowerCase match {
          case "int" => value.asInstanceOf[Int]
          case "varchar" => value.asInstanceOf[String]
          case "float" => value.asInstanceOf[Float]
          case "double" => value.asInstanceOf[Double]
          case "bigint" => value.asInstanceOf[Long]
          case _ => value.asInstanceOf[String]
        }
      }
    })
    Row(resultSetRecord:_*)
  }

  def resultSetToIter(rs: ResultSet)(f: ResultSet => Row): Iterator[Row] = {
    new Iterator[Row] {
      def hasNext: Boolean = rs.next()
      def next(): Row = f(rs)
    }
  }

  def parallelizeResultSet(rs: ResultSet, spark: SparkSession, schema: StructType): DataFrame = {
    val rdd = spark.sparkContext.parallelize(resultSetToIter(rs)(parseResultSet).toSeq)
    spark.createDataFrame(rdd, schema) // use the schema you defined in step 1
  }



}
