import ggd.{ERCommand, Runner}
import ggd.utils.loadProteus

object testPlaygroundProteus {

  def main(args: Array[String]): Unit = {
    val gcoreRunner = Runner.newRunner
    val loadP = new loadProteus(gcoreRunner)
    val runner = new ERCommand(gcoreRunner)

    loadP.openConnection()
    //loadP.runQuery("select * from ProductGoogle limit 5")
    //loadP.exampleQuery()
    loadP.loadGraph("/benchmarkDatasetsNew/Google/config.json")
    //val s = new SaveGraph()
    //s.saveJsonGraph(gcoreRunner.catalog.graph("Google"), "/home/Documents/entityResolution/")
    //val df = runner.runSelectQuery("SELECT * MATCH (p:ProductGoogle)-[x:producedbyGoogle]->(y:ManufacturerGoogle) ON Google LIMIT 10") //(p:ProductGoogle)-[x:producedbyGoogle]->(y:ManufacturerGoogle)
    val df = runner.runSelectQueryProteus("SELECT * MATCH (p:ProductGoogle) ON Google")
    df.show(20)
    loadP.closeConnection()



  }

}