/**
 *
 */
package ch.ethz.dal.dbcfw.optimization

import org.apache.spark.mllib.optimization.Optimizer
import org.apache.spark.rdd.RDD
import scala.util.Random
import ch.ethz.dal.dbcfw.classification.StructSVMModel
import breeze.linalg._
import breeze.numerics._
import ch.ethz.dal.dbcfw.regression.LabeledObject
import java.io.File

/**
 *
 */
class BCFWSolver /*extends Optimizer*/ (
  // val allPatterns: Vector[Matrix[Double]],
  // val allLabels: Vector[Vector[Double]],
  val data: Vector[LabeledObject],
  val featureFn: (Vector[Double], Matrix[Double]) => Vector[Double], // (y, x) => FeatureVect, 
  val lossFn: (Vector[Double], Vector[Double]) => Double, // (yTruth, yPredict) => LossVal, 
  val oracleFn: (StructSVMModel, Vector[Double], Matrix[Double]) => Vector[Double], // (model, y_i, x_i) => Lab, 
  val predictFn: (StructSVMModel, Matrix[Double]) => Vector[Double],
  // Parameters
  val solverOptions: SolverOptions) {

  val numPasses = solverOptions.numPasses
  val lambda = solverOptions.lambda
  val debugOn: Boolean = solverOptions.debug
  val xldebug: Boolean = solverOptions.xldebug

  val maxOracle = oracleFn
  val phi = featureFn
  // Number of dimensions of \phi(x, y)
  val ndims: Int = phi(data(0).label, data(0).pattern).size

  val eps: Double = 2.2204E-16

  /**
   * BCFW optimizer
   */
  def optimize(): StructSVMModel = {

    /* Initialization */
    var k: Integer = 0
    val n: Int = data.length
    val d: Int = featureFn(data(0).label, data(0).pattern).size
    // Use first example to determine dimension of w
    val model: StructSVMModel = new StructSVMModel(DenseVector.zeros(d), 0.0, DenseVector.zeros(ndims), featureFn, lossFn, oracleFn, predictFn)
    val wMat: DenseMatrix[Double] = DenseMatrix.zeros[Double](d, n)
    var ell: Double = 0.0
    val ellMat: DenseVector[Double] = DenseVector.zeros[Double](n)

    // Initialization in case of Weighted Averaging
    var wAvg: DenseVector[Double] =
      if (solverOptions.doWeightedAveraging)
        DenseVector.zeros(d)
      else null
    var lAvg: Double = 0.0

    if (debugOn) {
      println("Beginning training of %d data points in %d passes with lambda=%f".format(n, numPasses, lambda))
    }

    for (passNum <- 0 until numPasses) yield {

      if (debugOn)
        println("Starting pass #%d".format(passNum))

      for (dummy <- 0 until n) yield {
        // 1) Pick example
        val i: Int = dummy
        val pattern: Matrix[Double] = data(i).pattern
        val label: Vector[Double] = data(i).label

        // 2) Solve loss-augmented inference for point i
        val ystar_i: Vector[Double] = maxOracle(model, label, pattern)

        // 3) Define the update quantities
        val psi_i: Vector[Double] = phi(label, pattern) - phi(ystar_i, pattern)
        val w_s: Vector[Double] = psi_i :* (1 / (n * lambda))
        val loss_i: Double = lossFn(label, ystar_i)
        val ell_s: Double = (1.0 / n) * loss_i

        if (xldebug && dummy == (n - 1))
          csvwrite(new File("data/debug/scala-w-%d.csv".format(passNum + 1)), w_s.toDenseVector.toDenseMatrix)
        // csvwrite(new File("data/debug/scala-w.csv"), w_s.toDenseVector.toDenseMatrix)

        // 4) Get step-size gamma
        val gamma: Double =
          if (solverOptions.doLineSearch) {
            val gamma_opt = (model.getWeights().t * (wMat(::, i) - w_s) - ((ellMat(i) - ell_s) * (1 / lambda))) /
              ((wMat(::, i) - w_s).t * (wMat(::, i) - w_s) + eps)
            max(0.0, min(1.0, gamma_opt))
          } else {
            (2.0 * n) / (k + 2.0 * n)
          }

        // 5, 6, 7, 8) Update the weights of the model
        val tempWeights1: Vector[Double] = model.getWeights() - wMat(::, i)
        model.updateWeights(tempWeights1)
        wMat(::, i) := (wMat(::, i) * (1.0 - gamma)) + (w_s * gamma)
        val tempWeights2: Vector[Double] = model.getWeights() + wMat(::, i)
        model.updateWeights(tempWeights2)

        ell = ell - ellMat(i)
        ellMat(i) = (ellMat(i) * (1.0 - gamma)) + (ell_s * gamma)
        ell = ell + ellMat(i)

        // 9) Optionally update the weighted average
        if (solverOptions.doWeightedAveraging) {
          val rho: Double = 2.0 / (k + 2.0)
          wAvg = (wAvg * (1.0 - rho)) + (model.getWeights * rho)
          lAvg = (lAvg * (1.0 - rho)) + (ell * rho)
        }

        k = k + 1

      }
      if (debugOn)
        println("Completed pass #%d".format(passNum))

    }

    if (solverOptions.doWeightedAveraging) {
      model.updateWeights(wAvg)
      model.updateEll(lAvg)
    } else {
      model.updateEll(ell)
    }

    return model
  }

}