package ggd.utils

case class Result(truePos: Int = 0, trueNeg: Int = 0, falsePos: Int = 0, falseNeg: Int= 0){

  /*var truePos : Int = 0 //o que tem nas duas listas
  var trueNeg : Int = 0 //o resto do numero de entities -> nao usado
  var falsePos : Int = 0 //o que tem apenas na lista de resultados
  var falseNeg : Int = 0 //o que tem apenas na lista de ground truth*/

  def Recall(): Double = {
    val sum = (truePos + falseNeg).toDouble
    truePos.toDouble/ sum
  }

  def Precision(): Double = {
    val sum = (truePos + falsePos).toDouble
    truePos.toDouble/ sum
  }

}
