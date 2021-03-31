package ggd

import scala.math._

class distanceFunctions {

  def EuclideanDistance(xs: Array[Double], ys: Array[Double]) = {
    sqrt((xs zip ys).map { case (x,y) => pow(y - x, 2) }.sum)
  }

  def Diff(xs: Array[Double], ys: Array[Double]) = {
    (xs zip ys).map { case (x,y) => y - x }.sum
  }

  def editDistList[A](a: Iterable[A], b: Iterable[A]) ={
    ((0 to b.size).toList /: a)((prev, x) =>
      (prev zip prev.tail zip b).scanLeft(prev.head + 1) {
        case (h, ((d, v), y)) => min(min(h + 1, v + 1), d + (if (x == y) 0 else 1))
      })
  } // /: means foldLeft //verify edit distance


  def editDistance(s1: String, s2: String): Int = {
    val dist = Array.tabulate(s2.length + 1, s1.length + 1) { (j, i) => if (j == 0) i else if (i == 0) j else 0 }

    @inline
    def minimum(i: Int*): Int = i.min

    for {j <- dist.indices.tail
         i <- dist(0).indices.tail} dist(j)(i) =
      if (s2(j - 1) == s1(i - 1)) dist(j - 1)(i - 1)
      else minimum(dist(j - 1)(i) + 1, dist(j)(i - 1) + 1, dist(j - 1)(i - 1) + 1)

    dist(s2.length)(s1.length)
  }

  //def EqualVar() x= x

  def getArray(str: String): Array[Double] = {
    val a: Array[String] = str.split(",")
    return a.map(str => str.toDouble)
  }

}
