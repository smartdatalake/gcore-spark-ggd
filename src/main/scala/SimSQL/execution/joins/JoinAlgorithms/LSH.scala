package SimSQL.execution.joins.JoinAlgorithms

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow

//simple minhash using shingling for string -> set
//implementation from github just for quick testing purposes
class LSH(shingleLength: Int = 3,
          minHashLength: Int = 100,
          numberBands: Int=10,
          processedDocuments: RDD[(Long, String)],
          threshold: Double=0.8, sparkContext: SparkContext)  extends Serializable {


  val randomHashFunctions = randomLinearHashFunction(minHashLength);
  //each RDD has a internalRow as "id" and Sequence of (String,Int) --> token  --> ("ok",1), ("dvd",2), etc..

  val documentShingles = processedDocuments.map(doc => {
    val shingles = doc._2.toLowerCase.split(" ").toSet//.toList.sliding(shingleLength).map(s => s.mkString).toSet
    (doc._1, shingles)
  }).collect().toMap //collect for quick testing -> to improve implementation afterwards


  val shingleVocab = documentShingles.values.flatten.toSet.toIndexedSeq.zipWithIndex.toMap

  //val shingleVocab = documentShingles.values.flatten.toSet.toIndexedSeq.zipWithIndex.toMap

  var mBands: IndexedSeq[Band] = null;

  createHash()

  private def randomLinearHashFunction(n: Int) = {
    val slope = scala.util.Random.shuffle(0 to 1000);
    val const = scala.util.Random.shuffle(0 to 1000);
    slope.zip(const).take(minHashLength);
  }


  def findCandidates(shingles: Set[String]) = {
    val minHash = getMinHash(shingles);

    val subArrays = partitionArray(minHash).zipWithIndex;

    val candidates = subArrays.flatMap { subArray =>
      val index = subArray._2;
      val hashedBucket = mBands(index).getCollisionObjects(subArray._1)
      hashedBucket
    }.flatten.toSet

    candidates
  }

  /** Returns documents that have Jaccard Similarity greater than threshold Assumes that a documents
   have already been hashed
  @tparam : document . The document for which similar documents have to be identified
   **/

  def findSimilar(document: String) = {
    val shingles = document.toLowerCase.split(" ").toSet/*document.toList.sliding(shingleLength)
      .map(_.mkString)
      .map(shingle => shingle.toLowerCase)
      .toSet;*/

    val candidates = findCandidates(shingles).filter(r => r != -1);
    candidates.filter(candidate => JaccardSimilarity(shingles, documentShingles(candidate.toInt)) >= threshold)
  }

  /** Returns the Min Hash of a document
   *@tparam : The shingle representation for that document
   **/

  def getMinHash(shingles: Set[String]) = {

    val minHash = Array.fill[Double](minHashLength)(Double.PositiveInfinity);

    shingles.filter(x => shingleVocab.contains(x))
      .foreach { shingle =>
        val shingleIndex = shingleVocab(shingle);
        var hashIndex = 0;
        randomHashFunctions.foreach { function =>
          val permutedIndex = (function._1 * shingleIndex + function._2) % shingleVocab.size

          if (minHash(hashIndex) > permutedIndex)
            minHash(hashIndex) = permutedIndex

          hashIndex += 1;
        }

      }

    minHash

  }

  /** Partition the min-hash into  numberBands bands
  @tparam : The shingle represenatation of the document
   **/

  def partitionArray(minHash: Array[Double]): IndexedSeq[Array[Double]] = {

    if (minHash.length < numberBands) {
      println("number of bands exceeds minHash")
      System.exit(0);
    }

    val elementsPerBand = (minHash.length / numberBands);
    (0 to numberBands - 1).map { bandIndex =>
      val start = bandIndex * elementsPerBand
      val end = start + elementsPerBand;
      minHash.slice(start, end);
    }
  }

  /** Creates a locality sensitive hash for the all the processed documents **/

  def createHash() = {

    val minHashCollection = documentShingles.mapValues(shingleSet => getMinHash(shingleSet))
    val bands =

      (0 to numberBands - 1).map { bandIndex =>
        val elementsPerBand = (1.0 * minHashLength / numberBands).ceil.toInt
        val start = bandIndex * elementsPerBand
        val end = if (bandIndex == numberBands - 1) minHashLength else start + elementsPerBand;
        val subArray = minHashCollection.map(document => (document._1, document._2.slice(start, end)))
        val band = new Band()
        subArray.foreach(array => band.hash(array))
        band
      }

    mBands = bands
  }

}

/** Represents one band of the Locality Sensitive Hash **/

class Band() extends Serializable {
  import scala.collection.mutable.ArrayBuffer

  val buckets = scala.collection.mutable.Map[List[Double], ArrayBuffer[Long]]()

  /** Hashes the sub- array into buckets **/
  def hash(subArray: (Long, Array[Double])) {
    buckets.get(subArray._2.toList) match {
      case Some(value: ArrayBuffer[Long]) => value += subArray._1;
      case None => buckets(subArray._2.toList) = ArrayBuffer(subArray._1)
    }

  }

  /** Returns the documents that collide to the same bucket **/
  def getCollisionObjects(subArray: Array[Double]): Option[List[Long]] = {
    buckets.get(subArray.toList) match {
      case Some(value: ArrayBuffer[Long]) => Some(value.toList);
      case None => buckets(subArray.toList) = ArrayBuffer(-1); None
    }
  }

}

/** Computes the Jaccrd Similarity of two sets**/

object JaccardSimilarity extends Serializable {
  def apply(set1: Set[String], set2: Set[String]): Double = {
    val intersection = set1.intersect(set2).size
    val union = set2.union(set2).size

    return (intersection * 1.0) / union
  }
}

