package ggd.utils

//Result class used for comparing entity resolution results
case class Result(truePos: Int = 0, trueNeg: Int = 0, falsePos: Int = 0, falseNeg: Int= 0){

  def Recall(): Double = {
    val sum = (truePos + falseNeg).toDouble
    truePos.toDouble/ sum
  }

  def Precision(): Double = {
    val sum = (truePos + falsePos).toDouble
    truePos.toDouble/ sum
  }

  //traditional balanced f-measure
  def FMeasure(): Double = {
    val result = truePos.toDouble + ((falsePos+falseNeg).toDouble/2)
    (truePos.toDouble/result.toDouble)
  }

}
