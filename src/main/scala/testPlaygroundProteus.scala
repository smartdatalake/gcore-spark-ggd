import ggd.utils.loadJDBC
import ggd.{ERCommand, Runner}

object testPlaygroundProteus {

  def main(args: Array[String]): Unit = {
    val gcoreRunner = Runner.newRunner
    val loadP = new loadJDBC(gcoreRunner)
    val runner = new ERCommand(gcoreRunner)

    loadP.openConnection("jdbc:avatica:remote:url=http://diascld32.iccluster.epfl.ch:18010;serialization=PROTOBUF", "user", "pwd")
    //loadP.runQuery("select * from ProductGoogle limit 5")
    //loadP.exampleQuery()
    loadP.loadGraph("/home/larissacsh/Documents/entityResolution/benchmarkDatasetsNew/Google/config.json")
    //val s = new SaveGraph()
    //s.saveJsonGraph(gcoreRunner.catalog.graph("Google"), "/home/Documents/entityResolution/")
    //val df = runner.runSelectQuery("SELECT * MATCH (p:ProductGoogle)-[x:producedbyGoogle]->(y:ManufacturerGoogle) ON Google LIMIT 10") //(p:ProductGoogle)-[x:producedbyGoogle]->(y:ManufacturerGoogle)
    val df = runner.runSelectQueryJDBC("SELECT * MATCH (p:ProductGoogle) ON Google")
    df.show(20)
    loadP.closeConnection()

  }

}