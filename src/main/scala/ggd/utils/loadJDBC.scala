package ggd.utils

import java.nio.file.Paths
import java.sql.{Connection, DriverManager, ResultSet, Statement}

import algebra.expressions.Label
import ggd.GcoreRunner
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.types.{DataType, DoubleType, FloatType, IntegerType, LongType, StringType, StructField, StructType}
import schema.EntitySchema.LabelRestrictionMap
import schema.Table
import spark.{GraphJsonConfig, GraphSource, SparkCatalog, SparkGraph}

import scala.collection.mutable.ArrayBuffer

class loadJDBC(gcoreRunner: GcoreRunner) {
  var con: Connection = null
  var user: String = ""
  var password: String = ""

  def readTableSparkJDBC(tableName: String, url: String): DataFrame = {
    gcoreRunner.sparkSession.read.format("jdbc")
      .option("url", url) //"jdbc:avatica:remote:url=http://diascld32.iccluster.epfl.ch:18010;serialization=PROTOBUF")
      .option("dbtable", tableName)
      .option("user", user)
      .option("password", password)
      .load()
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
      if (rs.getString(c._1).endsWith("\n")) {
        val value = rs.getString(c._1).patch(rs.getString(c._1).lastIndexOf('\n'), "", 1)
        c._2.toLowerCase match {
          case "int" => value.asInstanceOf[Int]
          case "varchar" => value.asInstanceOf[String]
          case "float" => value.asInstanceOf[Float]
          case "double" => value.asInstanceOf[Double]
          case "bigint" => value.asInstanceOf[Long]
          case _ => value.asInstanceOf[String]
        }
      } else {
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
    Row(resultSetRecord: _*)
  }

  def resultSetToIter(rs: ResultSet)(f: ResultSet => Row): Iterator[Row] = {
    new Iterator[Row] {
      def hasNext: Boolean = rs.next()

      def next(): Row = f(rs)
    }
  }

  def parallelizeResultSet(rs: ResultSet, spark: SparkSession, schema: StructType, columns: Seq[(String, String)]): DataFrame = {
    val rdd = spark.sparkContext.parallelize(resultSetToIter(rs)(parseResultSet).toSeq)
    spark.createDataFrame(rdd, schema) // use the schema you defined in step 1
  }

  def openConnection(url: String, username: String, pwd: String): Unit = {
    Class.forName("org.apache.calcite.avatica.remote.Driver");
    user = username
    password = pwd
    con = DriverManager.getConnection(url, user, password)
  }

  def exampleQuery(): Unit = {
    val stmt: Statement = con.createStatement();
    println("Connected!!!")
    val rs: ResultSet = stmt.executeQuery("select description from Abt");
    println("ok!")
    while (rs.next()) {
      println(rs.getObject(1).asInstanceOf[String])
    }
  }

  def runQuery(query: String): Unit = {
    val stmt: Statement = con.createStatement();
    println("Connected!!!")
    val rs: ResultSet = stmt.executeQuery(query);
    println("ok!")
    while (rs.next()) {
      println(rs.getObject(1).asInstanceOf[String])
    }
  }

  def closeConnection(): Unit = {
    con.close()
  }

  def loadGraph(configPath: String): Unit = {
    var sparkCatalog: SparkCatalog = SparkCatalog(gcoreRunner.sparkSession)
    val graphSource = new GraphSource(gcoreRunner.sparkSession) {
      override val loadDataFn: String => DataFrame = _ => gcoreRunner.sparkSession.emptyDataFrame
    }
    val cat: SparkCatalog = new SparkCatalog(gcoreRunner.sparkSession)
    val jsonConfig = GraphSource.parseJsonConfig(Paths.get(configPath))
    val graph = loadGraphJDBC(jsonConfig, graphSource)
    val name = graph.graphName
    println("Graph name: " + graph.graphName)
    println(graph.schemaString)
    cat.registerGraph(graph)
    //graphName --> configPath
    //val graphName = configPath.split(File.separator).apply(configPath.split(File.separator).size-1)
    //println("Graph name:" + graphName)
    gcoreRunner.catalog.registerGraph(graph)
    //gcoreRunner.catalog.registerGraph(cat.graph(graphName))
  }

  /*def valueConversionScala(typeName: String, value: String): Any = {
    if(typeName.toLowerCase == "int") return value.asInstanceOf[Int]
    if (typeName.toLowerCase == "varchar")  return value.asInstanceOf[String]
    if (typeName.toLowerCase() == "float") return value.asInstanceOf[Float]
    if (typeName.toLowerCase() == "double") return value.asInstanceOf[Double]
    if(typeName.toLowerCase() == "bigint") return value.asInstanceOf[Long]
    return value.asInstanceOf[String]
  }*/

  def loadSparkJDBC(configPath: String): Unit = {
    var sparkCatalog: SparkCatalog = SparkCatalog(gcoreRunner.sparkSession)
    val graphSource = new GraphSource(gcoreRunner.sparkSession) {
      override val loadDataFn: String => DataFrame = _ => gcoreRunner.sparkSession.emptyDataFrame
    }
    val cat: SparkCatalog = new SparkCatalog(gcoreRunner.sparkSession)
    val jsonConfig = GraphSource.parseJsonConfig(Paths.get(configPath))
    val graph = loadGraphSparkJDBC(jsonConfig, graphSource)
    val name = graph.graphName
    println("Graph name: " + graph.graphName)
    println(graph.schemaString)
    cat.registerGraph(graph)
    //graphName --> configPath
    //val graphName = configPath.split(File.separator).apply(configPath.split(File.separator).size-1)
    //println("Graph name:" + graphName)
    gcoreRunner.catalog.registerGraph(graph)
    //gcoreRunner.catalog.registerGraph(cat.graph(graphName))
  }

  def loadGraphSparkJDBC(config: GraphJsonConfig, source: GraphSource): SparkGraph = {
    val edgeLabels = config.edgeLabels
    val vertexLabels = config.vertexLabels
    //val pathLabels = config.pathLabels - for now no paths just vertex and edges
    var vertices = Seq[Any]()
    for (v <- vertexLabels) {
      val stmt: Statement = con.createStatement()
      //val rs:ResultSet =stmt.executeQuery("select * from " + config.graphName + "-" + v);
      val rs: ResultSet = stmt.executeQuery("select * from " + v + " limit 1"); //limit 5;
      val rsmd = rs.getMetaData
      val columnCount = rsmd.getColumnCount
      // The column count starts from 1
      var header = new ArrayBuffer[(String, String)]()
      //var types = new ArrayBuffer[String]()
      for (i <- 1 to columnCount) {
        header += ((rsmd.getColumnName(i), rsmd.getColumnTypeName(i)))
        //header += rsmd.getColumnName(i)
        //types += rsmd.getColumnTypeName(i)
        // Do stuff with name
      }
      val schema = StructType(header.map(fieldName => StructField(fieldName._1, getSparkTypes(fieldName._2), true))) //schema ok
      //transform rows to dataframe -> parallize result set
      //val df = readTableSparkJDBC(v)
      //df.show(10)
      val df = parallelizeResultSet(rs, gcoreRunner.sparkSession, schema, header)
      val table = (Table(Label(v), df.cache()))
      vertices = vertices :+ table
    }
    //for edges
    var edges = Seq[Any]()
    for (v <- edgeLabels) {
      val stmt: Statement = con.createStatement()
      val rs: ResultSet = stmt.executeQuery("select * from " + v + " limit 1"); //+ " limit 5");
      val rsmd = rs.getMetaData
      val columnCount = rsmd.getColumnCount
      // The column count starts from 1
      var header = new ArrayBuffer[(String, String)]()
      //var types = new ArrayBuffer[String]()
      for (i <- 1 to columnCount) {
        header += ((rsmd.getColumnName(i), rsmd.getColumnTypeName(i)))
        //header += rsmd.getColumnName(i)
        //types += rsmd.getColumnTypeName(i)
        // Do stuff with name
      }
      val schema = StructType(header.map(fieldName => StructField(fieldName._1, getSparkTypes(fieldName._2), true))) //schema ok
      //transform rows to dataframe
      //val df = readTableSparkJDBC(v)
      //val dataRDD = gcoreRunner.sparkSession.sparkContext.parallelize()
      //val df = gcoreRunner.sparkSession.createDataFrame(dataRDD, schema)
      val df = parallelizeResultSet(rs, gcoreRunner.sparkSession, schema, header)
      df.show(10)
      val table = (Table(Label(v), df.cache()))
      edges = edges :+ table
    }

    new SparkGraph {
      override var graphName: String = config.graphName

      override def vertexData: Seq[Table[DataFrame]] = vertices.asInstanceOf[Seq[Table[DataFrame]]]

      override def edgeData: Seq[Table[DataFrame]] = edges.asInstanceOf[Seq[Table[DataFrame]]]

      override def pathData: Seq[Table[DataFrame]] = Seq.empty

      override def edgeRestrictions: LabelRestrictionMap = source.buildRestrictions(config.edgeRestrictions)

      override def storedPathRestrictions: LabelRestrictionMap = source.buildRestrictions(config.pathRestrictions)
    }
  }


  def getSparkTypes(typeName: String): DataType = {
    if (typeName.toLowerCase == "int") return IntegerType
    if (typeName.toLowerCase == "varchar") return StringType
    if (typeName.toLowerCase() == "float") return FloatType
    if (typeName.toLowerCase() == "double") return DoubleType
    if (typeName.toLowerCase() == "bigint") return LongType
    return StringType
  }

  def loadGraphJDBC(config: GraphJsonConfig, graphSource: GraphSource): SparkGraph = {
    val edgeLabels = config.edgeLabels
    val vertexLabels = config.vertexLabels
    //val pathLabels = config.pathLabels - for now no paths just vertex and edges
    var vertices = Seq[Any]()
    for (v <- vertexLabels) {
      val stmt: Statement = con.createStatement()
      //val rs:ResultSet =stmt.executeQuery("select * from " + config.graphName + "-" + v);
      val rs: ResultSet = stmt.executeQuery("select * from " + v); //limit 5;
      val rsmd = rs.getMetaData
      val columnCount = rsmd.getColumnCount
      // The column count starts from 1
      var header = new ArrayBuffer[(String, String)]()
      //var types = new ArrayBuffer[String]()
      for (i <- 1 to columnCount) {
        header += ((rsmd.getColumnName(i), rsmd.getColumnTypeName(i)))
        //header += rsmd.getColumnName(i)
        //types += rsmd.getColumnTypeName(i)
        // Do stuff with name
      }
      val schema = StructType(header.map(fieldName => StructField(fieldName._1, getSparkTypes(fieldName._2), true))) //schema ok
      //transform rows to dataframe -> parallize result set
      val df = parallelizeResultSet(rs, gcoreRunner.sparkSession, schema, header)
      df.show(10)
      val table = (Table(Label(v), df.cache()))
      vertices = vertices :+ table
    }
    //for edges
    var edges = Seq[Any]()
    for (v <- edgeLabels) {
      val stmt: Statement = con.createStatement()
      val rs: ResultSet = stmt.executeQuery("select * from " + v); //+ " limit 5");
      val rsmd = rs.getMetaData
      val columnCount = rsmd.getColumnCount
      // The column count starts from 1
      var header = new ArrayBuffer[(String, String)]()
      //var types = new ArrayBuffer[String]()
      for (i <- 1 to columnCount) {
        header += ((rsmd.getColumnName(i), rsmd.getColumnTypeName(i)))
        //header += rsmd.getColumnName(i)
        //types += rsmd.getColumnTypeName(i)
        // Do stuff with name
      }
      val schema = StructType(header.map(fieldName => StructField(fieldName._1, getSparkTypes(fieldName._2), true))) //schema ok
      //transform rows to dataframe
      val df = parallelizeResultSet(rs, gcoreRunner.sparkSession, schema, header)
      //val dataRDD = gcoreRunner.sparkSession.sparkContext.parallelize()
      //val df = gcoreRunner.sparkSession.createDataFrame(dataRDD, schema)
      df.show(10)
      val table = (Table(Label(v), df.cache()))
      edges = edges :+ table
    }

    new SparkGraph {
      override var graphName: String = config.graphName

      override def vertexData: Seq[Table[DataFrame]] = vertices.asInstanceOf[Seq[Table[DataFrame]]]

      override def edgeData: Seq[Table[DataFrame]] = edges.asInstanceOf[Seq[Table[DataFrame]]]

      override def pathData: Seq[Table[DataFrame]] = Seq.empty

      override def edgeRestrictions: LabelRestrictionMap = graphSource.buildRestrictions(config.edgeRestrictions)

      override def storedPathRestrictions: LabelRestrictionMap = graphSource.buildRestrictions(config.pathRestrictions)
    }
  }
}
