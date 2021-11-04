# Graph Generating Dependencies using G-CORE interpreter on Spark

The Graph Generating Dependencies is a new class of graph dependencies proposed for property graphs inspired by the tuple- and equality-generating dependencies for relational data.
More information on the Graph Generating Dependencies and its syntax refer to the paper at: 

Shimomura, Larissa C., George Fletcher, and Nikolay Yakovets. "GGDs: Graph Generating Dependencies." arXiv preprint arXiv:2004.10247 (2020).
URL: https://arxiv.org/abs/2004.10247

This program is able to run the validation of the input GGDs and "fix" it by generating new nodes/edges in the graph.
Example on the input format are available in the folder GGDInput of the project.

The project uses the G-Core interpreter on Spark for querying the defined graph patterns. The G-Core project is implemented and maintained by the LDBC council in their repository disponible at https://ldbc.github.io/gcore-spark/. For more information on G-Core query language refer to the original project page.

Some operators on the G-Core interpreter used for the GGDs were added: SELECT, UNION and OPTIONAL queries. To add these operators we changed the Spoofax language file as well as on the compilation class file of these new types of queries.

This project is still under development.

## Similarity Join

We added similarity join operators to SparkSQL using the Spark extensions API.

The similarity join operators included are:
* Dima - Edit Similarity
* Dima - Jaccard Similarity
* Vernica Join - Jaccard Similarity Join- (code provided by Athena Research Center)

The Dima similarity join algorithms are belong to the Tsinghua Database Group github repository (https://github.com/TsinghuaDatabaseGroup/Dima).
The methods from Dima were merged into our project, we made very few modifications on the source code in order to integrate to our project. We also include the NOTICE file from Dima and Spark in our repository, following their License rules. 

The Vernica Similarity Join algorithm for Jaccard Similarity Join was implemented by Athena Research Center and integrated as a physical operator in Spark.

For more information check our manual pdf file (sHINERmanual.pdf) or for the source code check the SimSQL package in our project.


## To build and run
The GGDs were built as an extension to the G-Core project, the instructions to build and run this project are the same as in the G-Core interpreter.
The project will build successfully under Java 8. Spark 2.4.5 is needed to run
the application. Spark can be downloaded from
https://spark.apache.org/downloads.html.

To run locally you can build the project using maven,
```bash
mvn install 
```
and run the built jar that includes the dependencies of the project
```bash
java -jar target/gcore-interpreter-ggd-1.0-SNAPSHOT-jar-with-dependencies.jar
```

To submit on Spark, the project needs to be compiled as an uber-jar. The
```-DskipTests``` flag can be used with the mvn package command to avoid running
the tests when packaging the uber-jar.

The jar needs to be submitted to an Spark that support similarity join algorithms. In this case, use the spark-sim-2.4 available in the SmartDataLake repository and build the project and use it as the spark instance.

The Spoofax parser uses Guice as a dependency injection framework. The Guice 4.0
jar needs to be passed separately to the driver as a
```spark.driver.extraClassPath``` property, otherwise the driver is not able to
find it.

```bash
mvn package -DskipTests
spark-submit \
    --class ggd.ERRunner \
    --master local[2] \
    --conf "spark.driver.extraClassPath=/path_to/guice-4.0.jar" \
    target/gcore-interpreter-1.0-SNAPSHOT-jar-with-dependencies.jar
```

## To run REST API
```bash
mvn package -DskipTests
spark-submit \
    --class application.WebServer \
    --master local[2] \
    --conf "spark.driver.extraClassPath=/path_to/guice-4.0.jar" \
    target/gcore-interpreter-1.0-SNAPSHOT-allinone.jar port_number /dataset/folder/path
```

## To run tests
```bash
mvn test
```
## Manual

For more information on how to use this component, refer to the sHINERManual.pdf document in the repository.
This document contains more information about the component architecture, input formats, how to use the command-line application and the REST API.
The project is under development, as the project is updated the manual will describe the changes made to the project.

## Acknowledgments

This project has received funding from the European Union's Horizon 2020 research and innovation programme under grant agreement No 825041.