package ggd.graphdataset

import algebra.expressions.Label
import ggd.{GcoreRunner,Runner}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.{lit, monotonically_increasing_id, explode, split, col}
import schema.EntitySchema.LabelRestrictionMap
import schema.{SchemaMap, Table}
import spark.{SaveGraph, SparkCatalog, SparkGraph}


object CordisGraph {

  def main(args: Array[String]): Unit = {
    val gcoreRunner: GcoreRunner = Runner.newRunner
    val inputPath = args(0)
    val outputPath = args(1)
    var c = ""
    if(args.size > 2){
      c = args(2)
    }

    import gcoreRunner.sparkSession.implicits._

    val organizations = gcoreRunner.sparkSession.read
      .option("header","true")
      .option("inferschema", true)
      .option("escape", "\"")
      .option("delimiter", ";")
      .csv(inputPath + "cordis-h2020organizations.csv")
      .alias("org")
      .withColumn("OrgID", col("id"))
    val publications = gcoreRunner.sparkSession.read
      .option("header","true")
      .option("inferschema", true)
      .option("ignoreLeadingWhiteSpace", true)
      .option("multiline", true)
      .option("escape", "\"")
      .option("delimiter", ";")
      .csv(inputPath + "cordis-h2020projectPublications.csv")
      .alias("pub")
      .dropDuplicates()
      .withColumn("PubID", monotonically_increasing_id())
    val projects = gcoreRunner.sparkSession.read
      .option("header","true")
      .option("inferschema", true)
      .option("ignoreLeadingWhiteSpace", true)
      .option("multiline", true)
      .option("escape", "\"")
      .option("delimiter", ";")
      .csv(inputPath + "cordis-h2020projects.csv")
      .alias("proj")
      .withColumn("ProjID", col("rcn"))
    val projKeyWords = gcoreRunner.sparkSession.read
      .option("header","true")
      .option("inferschema", true)
      .option("delimiter", ",")
      .csv(inputPath + "cordisKeywordsRelatedProject.csv")
      .alias("keyw")
    val projKeyWordsGrouped = gcoreRunner.sparkSession.read
      .option("header","true")
      .option("inferschema", true)
      .option("delimiter", ",")
      .csv(inputPath + "cordisKeywordsRelatedProjectGrouped.csv")
      .select("rcn", "keywords")
      .alias("kg")
    val projSciVoc = gcoreRunner.sparkSession.read
      .option("header","true")
      .option("inferschema", true)
      .option("delimiter", ",")
      .csv(inputPath + "cordisSciVocRelatedProject.csv")
      .alias("sci")


    val projectJoin = projects.join(projKeyWordsGrouped, Seq("rcn"), "outer").dropDuplicates()
    val projectsCols = Seq("rcn", "proj.acronym", "proj.title", "proj.totalCost", "proj.ecMaxContribution",
      "proj.startDate", "proj.endDate", "proj.frameworkProgramme", "proj.programme", "proj.projectURL", "proj.objective", "proj.id", "ProjID", "kg.keywords",
      "proj.fundingScheme", "proj.subjects")//, "scig.title")
    val projectsNode = projectJoin.select(projectsCols.head, projectsCols.tail:_*).dropDuplicates("rcn")
      .withColumn("label", lit("Project"))
      .drop("ProjID")
      .alias("project")

    //projectsNode.show(10)

    val orgCols = Seq("org.name", "id", "org.vatNumber", "org.shortName" , "OrgID", "org.street", "org.city", "org.postcode", "org.organizationUrl", "org.contactForm",
      "org.contactType","org.contactTitle","org.contactFirstNames","org.contactLastNames","org.contactFunction","org.contactTelephoneNumber","org.contactFaxNumber")
    val orgNodes = organizations.select(orgCols.head, orgCols.tail:_*).dropDuplicates()
      .withColumn("label", lit("Organization"))

    //orgNodes.show(10)

    val partOf = Seq("org.ecContribution", "org.role", "proj.id", "OrgID")
    val partOfEdges2 = projects.join(organizations, projects("id") === organizations("projectID")) .select(partOf.head, partOf.tail:_*)
    partOfEdges2.show(10)
    val partOfEdges = partOfEdges2
      .withColumn("label", lit("partOf"))
      .withColumnRenamed("id", "toId")
      .withColumnRenamed("OrgID", "fromId")
      .withColumn("id", monotonically_increasing_id())//partOf edges

    //partOfEdges.show(10)

    val typeNodes = organizations.select("org.activityType").dropDuplicates()
      .withColumn("label", lit("Type"))
      .withColumn("id", monotonically_increasing_id()).alias("type")

    //typeNodes.show(20)

    val hasTypePre = organizations.select(col("org.activityType"), col("org.id").as("OrgID")).dropDuplicates()
    val hasType2 = hasTypePre.join(typeNodes, "activityType").select("type.id", "OrgID").dropDuplicates()
    hasType2.show(20)
    val hasTypeEdges = hasType2
      .withColumn("label", lit("hasType"))
      .withColumnRenamed("id", "toId")
      .withColumnRenamed("OrgID", "fromId")
      .withColumn("id", monotonically_increasing_id())

    //hasTypeEdges.show(30)

    val coordinatorNodes = projects.select("proj.coordinator").withColumn("label", lit("Coordinator"))
      .dropDuplicates()
      .withColumnRenamed("coordinator", "name")
      .withColumn("id", monotonically_increasing_id()).alias("coord")

    //coordinatorNodes.show(20)

    val hasCoordinatorPre = projects.select(col("proj.id").as("projid"), col("proj.coordinator"), col("proj.coordinatorCountry")).dropDuplicates()
    val hasCoordinatorJoin = hasCoordinatorPre.join(coordinatorNodes, hasCoordinatorPre("proj.coordinator") === coordinatorNodes("name")).dropDuplicates()
    hasCoordinatorJoin.show(30)
    val coordinatorEdges = hasCoordinatorJoin.select(col("projid"), col("coord.id").as("coordid"))
      .withColumnRenamed("projid", "fromId")
      .withColumnRenamed("coordid", "toId")
      .withColumn("label", lit("hasCoordinator"))
      .withColumn("id", monotonically_increasing_id())

    //coordinatorEdges.show(30)

    val keywordCols = Seq("keyw.title", "keyw.id", "keyw.code")//, "ProjID", "proj.id")
    val keywordNodes = projKeyWords.select(keywordCols.head, keywordCols.tail:_*).dropDuplicates("title", "code")
      .withColumn("label", lit("Keywords"))
      .withColumnRenamed("keyw.id", "projectID_XML")
      .withColumn("id", monotonically_increasing_id())

    //keywordNodes.show(20)

    val isAboutEdgesPre = projKeyWords.select( "rcn", "keyw.title", "keyw.code")
    val isAboutEdgeJoin = isAboutEdgesPre.join(keywordNodes, isAboutEdgesPre("keyw.title") === keywordNodes("title") && isAboutEdgesPre("keyw.code") === keywordNodes("code"))
    val isAboutEdges = isAboutEdgeJoin.select("keyw.rcn", "id").dropDuplicates()
      .alias("keyn")
      .join(projects, "rcn")
      .select(col("proj.id").as("projid"), col("keyn.id").as("keywid"))
      .withColumn("label", lit("isAbout"))
      .withColumnRenamed("keywid", "toId")
      .withColumnRenamed("projid", "fromId")
      .withColumn("id", monotonically_increasing_id())

    //isAboutEdges.show(10)

    val scivocCols = Seq("sci.title", "sci.id", "sci.code", "sci.displaycode")//, "ProjID", "proj.id")
    val scivocNodes = projSciVoc.select(scivocCols.head, scivocCols.tail:_*).dropDuplicates("title", "code")
      .withColumn("label", lit("FielOfScience"))
      .withColumnRenamed("sci.id", "projectID_XML")
      .withColumn("id", monotonically_increasing_id())

    //scivocNodes.show(20)

    val isInEdgesPre = projSciVoc.select( "rcn", "sci.title", "sci.code")
    val isInEdgeJoin = isInEdgesPre.join(scivocNodes, isInEdgesPre("sci.title") === scivocNodes("title") && isInEdgesPre("sci.code") === scivocNodes("code"))
    val isInEdges = isInEdgeJoin.select(col("sci.rcn"), col("id").as("sciid")).dropDuplicates()
      .join(projects, "rcn")
      .select(col("proj.id").as("projid"), col("sciid"))
      .withColumn("label", lit("isIn"))
      .withColumnRenamed("sciid", "toId")
      .withColumnRenamed("projid", "fromId")
      .withColumn("id", monotonically_increasing_id())

    //isInEdges.show(10)

    val selectCountry = projects.select("proj.participantCountries").filter("proj.participantCountries is not null")
    selectCountry.show(10)
    val countrySeq = selectCountry.collect().map(row => row.getAs[String]("participantCountries").split(";")).flatten.distinct.toSeq
    val countryNodes = countrySeq.toDF("name").withColumn("label", lit("Country"))
      .withColumn("id", monotonically_increasing_id()).alias("country")

    //countryNodes.show(20)

    val fromCountryJoin = hasCoordinatorJoin.join(countryNodes, hasCoordinatorJoin("proj.coordinatorCountry") === countryNodes("country.name")).dropDuplicates()
    val fromCountryEdges = fromCountryJoin.select(col("coord.id").as("coord"), col("country.id").as("country"))
      .withColumn("label", lit("fromCountry"))
      .withColumnRenamed("coord", "fromId")
      .withColumnRenamed("country", "toId")
      .withColumn("id", monotonically_increasing_id())

    //fromCountryEdges.show(10)

    val countriesProject = projects.select("proj.id", "ProjID", "proj.participantCountries")
    val projIdCountry = countriesProject.withColumn("countryName", explode(split($"proj.participantCountries", ";")))
    val joinCountry = projIdCountry.join(countryNodes, projIdCountry("countryName") === countryNodes("name")).dropDuplicates()
    val participatesInEdges = joinCountry.select(col("country.id").as("countryid"), col("proj.id").as("projid"))
      .withColumn("label", lit("participatesIn"))
      .withColumnRenamed("countryid", "fromId")
      .withColumnRenamed("projid", "toId")
      .withColumn("id", monotonically_increasing_id())

    //participatesInEdges.show(20)

    //located in edges
    val selectOrgCountries = organizations.select("org.id", "org.country")
    val joinWithCountries = selectOrgCountries.join(countryNodes, countryNodes("country.name") === selectOrgCountries("org.country"))
    val locatedInEdges = joinWithCountries.select(col("org.id").as("orgid"), col("country.id").as("countryid"))
      .withColumnRenamed("orgid", "fromId")
      .withColumnRenamed("countryid", "toId")
      .withColumn("id", monotonically_increasing_id())

    //locatedInEdges.show(20)

    val callNodes = projects.select("proj.call").dropDuplicates().withColumn("label", lit("Call"))
      .withColumnRenamed("call", "number")
      .withColumn("id", monotonically_increasing_id())
      .alias("call")

    //callNodes.show(10)

    val callEdgesSelect = projects.select("proj.id", "proj.call").dropDuplicates()
    val callEdgesJoin = callEdgesSelect.join(callNodes, callEdgesSelect("proj.call") === callNodes("number"))
    val callEdges = callEdgesJoin.select(col("call.id").as("callid"), col("proj.id").as("projid"))
      .withColumn("label", lit("hasCall"))
      .withColumnRenamed("projid", "fromId")
      .withColumnRenamed("callid", "toId")
      .withColumn("id", monotonically_increasing_id())

    //callEdges.show(10)

    val journalNodes = publications.select("pub.journalTitle").dropDuplicates()
      .withColumn("label", lit("Journal"))
      .withColumn("id", monotonically_increasing_id())

    journalNodes.show(10)

    val paperAttr = Seq("pub.title", "pub.projectID", "pub.publishedYear", "pub.publishedPages", "pub.issn", "pub.doi","PubID", "pub.rcn", "pub.legalBasis",
      "pub.isbn", "pub.isPublishedAs", "pub.lastUpdateDate")
    val papersNodes = publications.select("pub.title", "pub.projectID", "pub.publishedYear", "pub.publishedPages", "pub.issn", "pub.doi","PubID", "pub.rcn", "pub.legalBasis",
      "pub.isbn").dropDuplicates()
      .withColumn("label", lit("Paper"))
      .withColumn("id", col("PubID"))
      .alias("paper")

    //papersNodes.show(30)


    val publishedInTemp = publications.select(col("PubID"), col("pub.journalTitle").as("corTi"))//, col("pub.journalNumber").as("number"))
      .join(journalNodes, col("corTi") === journalNodes.col("journalTitle"))// && col("number") === journalNodes.col("journalNumber"))
    val publishedInEdges = publishedInTemp.select(col("PubID"), col("id").as("journalId"))
      .withColumnRenamed("PubID", "fromId")
      .withColumnRenamed("journalId", "toId")
      .withColumn("label", lit("publishedIn"))
      .withColumn("id", monotonically_increasing_id())

    //publishedInEdges.show(10)


    val hasPublicationJoin = papersNodes.join(projectsNode, projectsNode("project.id") === papersNodes("paper.projectID")).dropDuplicates()
    hasPublicationJoin.show(20)
    val hasPublicationEdges = hasPublicationJoin.select(col("paper.id").as("paperid"), col("project.id").as("projectid")).dropDuplicates()
      .withColumn("label", lit("hasPublication"))
      .withColumnRenamed("paperid", "toId")
      .withColumnRenamed("projectid", "fromId")
      .withColumn("id", monotonically_increasing_id())
    //hasPublicationEdges.show(20)

    val authorsAll = publications.select("pub.authors").collect().map(row => row.getAs[String]("authors").split("[,;]")).flatten
      .distinct.toSeq
    val authorNodes = authorsAll.toDF("name").withColumn("label", lit("Author"))
      .withColumn("id", monotonically_increasing_id())
      .alias("authors")
    //authorNodes.show(20)

    val authorsEdgesSelect = publications.select("pub.authors", "PubID").dropDuplicates().join(papersNodes, "PubID")
    val authorEdgesDF = authorsEdgesSelect.withColumn("authorsNames", explode(split($"authors", "[,;]")))
    val joinAuthors = authorEdgesDF.join(authorNodes, authorNodes("name") === authorEdgesDF("authorsNames"))
    val hasAuthorsEdges = joinAuthors.select(col("authors.id").as("authorsid"), col("paper.id").as("paperid")).dropDuplicates()
      .withColumn("label", lit("hasAuthor"))
      .withColumnRenamed("authorsid", "toId")
      .withColumnRenamed("paperid", "fromId")
      .withColumn("id", monotonically_increasing_id())
    hasAuthorsEdges.show(30)

    val topicPublications = publications.select("topics").collect().map(row => row.getString(0)).distinct.toSeq
    val topicProjects = projects.select("proj.topics").collect().map(row => row.getAs[String](0)).distinct.toSeq
    println(topicProjects)
    val topicsNodes = (topicProjects ++ topicPublications).distinct.toSeq.toDF("topic")
      .withColumn("label", lit("Topics"))
      .withColumn("id", monotonically_increasing_id())
      .alias("topics")
    //topicsNodes.show(30)

    val publicationT = publications.select("pub.topics", "PubID").dropDuplicates().join(papersNodes, "PubID")
    val pubExplodeDF = publicationT.withColumn("topicsName", explode(split($"topics", ",")))
    val joinPublications = pubExplodeDF.join(topicsNodes, topicsNodes("topic") === pubExplodeDF("topicsName"))
    val publicationAboutEdges = joinPublications.select(col("paper.id").as("paperid"), col("topics.id").as("topicsid"))
      .withColumn("label", lit("pubAbout"))
      .withColumnRenamed("paperid", "fromId")
      .withColumnRenamed("topicsid", "toId")
      .withColumn("id", monotonically_increasing_id())
    //publicationAboutEdges.show(10)

    val projectsTemp = projects.select("proj.topics", "ProjID", "proj.id").dropDuplicates()
    val projectsT = projectsTemp.join(projectsNode, projectsTemp("proj.id") === projectsNode("id"))
    val projExplodeDF = projectsT.withColumn("topicsName", explode(split($"topics", ",")))
    val joinProj = projExplodeDF.join(topicsNodes, topicsNodes("topic") === projExplodeDF("topicsName"))
    val projectAboutEdges = joinProj.select(col("project.id").as("projectid"), col("topics.id").as("topicsid"))
      .withColumn("label", lit("projAbout"))
      .withColumnRenamed("projectid", "fromId")
      .withColumnRenamed("topicsid", "toId")
      .withColumn("id", monotonically_increasing_id())
    //projectAboutEdges.show(10)

    println("paper nodes:" + papersNodes.count())
    println("org nodes" + organizations.count())
    println("proj nodes" + projectsNode.count())

    val orgNodesFinal = orgNodes.drop("OrgID")
    val papersNodesFinal = papersNodes.drop("PubID")

    val cordisGraph = new SparkGraph {
      override var graphName: String = "CordisGraph_V2"

      override def edgeRestrictions: LabelRestrictionMap = SchemaMap(
        Map(Label(c+"projAbout") -> (Label(c+"Project"), Label(c+"Topics")),
          Label(c+"pubAbout") -> (Label(c+"Paper"),Label(c+"Topics")),
          Label(c+"hasAuthor") -> (Label(c+"Paper"),Label(c+"Author")),
          Label(c+"hasPublication")-> (Label(c+"Project"),Label(c+"Paper")),
          Label(c+"hasCall") -> (Label(c+"Project"),Label(c+"Call")),
          Label(c+"participatesIn") -> (Label(c+"Country"),Label(c+"Project")),
          Label(c+"fromCountry") -> (Label(c+"Coordinator"), Label(c+"Country")),
          Label(c+"hasCoordinator") -> (Label(c+"Project"), Label(c+"Coordinator")),
          Label(c+"hasType") -> (Label(c+"Organization"), Label(c+"Type")),
          Label(c+"partOf") -> (Label(c+"Organization"), Label(c+"Project")),
          Label(c+"publishedIn") -> (Label(c+"Paper"), Label(c+"Journal")),
          Label(c+"isAbout") -> (Label(c+"Project"), Label(c+"Keywords")),
          Label(c+"isIn") -> (Label(c+"Project"), Label(c+"FieldOfScience")),
          Label(c+"locatedIn") -> (Label(c+"Organization"), Label(c+"Country"))
        )
      )

      override def storedPathRestrictions: LabelRestrictionMap = SchemaMap.empty

      override def vertexData: Seq[Table[DataFrame]] = Seq(
        new Table[DataFrame](Label(c+"Project"), projectsNode),
        new Table[DataFrame](Label(c+"Organization"), orgNodesFinal),
        new Table[DataFrame](Label(c+"Paper"), papersNodesFinal),
        new Table[DataFrame](Label(c+"Type"), typeNodes),
        new Table[DataFrame](Label(c+"Coordinator"), coordinatorNodes),
        new Table[DataFrame](Label(c+"Country"), countryNodes),
        new Table[DataFrame](Label(c+"Topics"), topicsNodes),
        new Table[DataFrame](Label(c+"Call"), callNodes),
        new Table[DataFrame](Label(c+"Author"), authorNodes),
        new Table[DataFrame](Label(c+"Journal"), journalNodes),
        new Table[DataFrame](Label(c+"Keywords"), keywordNodes),
        new Table[DataFrame](Label(c+"FieldOfScience"), scivocNodes)
      )

      override def edgeData: Seq[Table[DataFrame]] = Seq(
        new Table[DataFrame](Label(c+"partOf"), partOfEdges),
        new Table[DataFrame](Label(c+"participatesIn"), participatesInEdges),
        new Table[DataFrame](Label(c+"hasType"), hasTypeEdges),
        new Table[DataFrame](Label(c+"hasCoordinator"), coordinatorEdges),
        new Table[DataFrame](Label(c+"fromCountry"), fromCountryEdges),
        new Table[DataFrame](Label(c+"hasCall"), callEdges),
        new Table[DataFrame](Label(c+"hasPublication"), hasPublicationEdges),
        new Table[DataFrame](Label(c+"hasAuthor"), hasAuthorsEdges),
        new Table[DataFrame](Label(c+"pubAbout"), publicationAboutEdges),
        new Table[DataFrame](Label(c+"projAbout"), projectAboutEdges),
        new Table[DataFrame](Label(c+"publishedIn"), publishedInEdges),
        new Table[DataFrame](Label(c+"isAbout"), isAboutEdges),
        new Table[DataFrame](Label(c+"isIn"), isInEdges),
        new Table[DataFrame](Label(c+"locatedIn"), locatedInEdges)
      )

      override def pathData: Seq[Table[DataFrame]] = Seq.empty
    }

    println(cordisGraph.schemaString)

    val saveGraph = new SaveGraph
    saveGraph.saveJsonGraph(cordisGraph, outputPath)
  }

}
