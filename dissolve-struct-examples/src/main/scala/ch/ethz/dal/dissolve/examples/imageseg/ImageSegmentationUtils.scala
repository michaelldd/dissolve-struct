package ch.ethz.dal.dissolve.examples.imageseg

import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import breeze.linalg.{ Matrix, Vector }
import ch.ethz.dal.dbcfw.regression.LabeledObject
import scala.io.Source
import org.apache.spark.mllib.linalg.DenseMatrix
import breeze.linalg.DenseMatrix
import java.awt.image.DataBufferInt
import breeze.linalg.DenseVector
import breeze.linalg.max
import breeze.linalg.min

object ImageSegmentationUtils {

  val featurizer_options: List[String] = List("HIST")

  val colormapFile = "../data/imageseg_colormap.txt"
  val colormap: Map[Int, Int] = Source.fromFile(colormapFile)
    .getLines()
    .map { line => line.split(" ") }
    .map {
      case Array(label, value, r, g, b, className) =>
        value.toInt -> label.toInt
    }
    .toMap

  /**
   * Constructs a histogram vector using pixel (i, j) and a surrounding region of size (width x height)
   */
  def histogramFeaturizer(patch: BufferedImage, patchWithContext: BufferedImage): ROIFeature = {

    // The intensities are split into these many bins.
    // For example, in case of 4 bins, Bin 0 corresponds to intensities 0-63, bin 1 is 64-127,
    // bin 2 is 128-191, and bin 3 is 192-255.
    val NUM_BINS = 4
    // Store the histogram in 3 blocks of [ R | G | B ]
    val histogramVector = DenseVector.zeros[Double](NUM_BINS * NUM_BINS * NUM_BINS)

    // Convert patchWithContext region into an ARGB vector
    val imageRGBVector: Array[Int] = patchWithContext.getRaster()
      .getDataBuffer().asInstanceOf[DataBufferInt]
      .getData()

    for (rgb <- imageRGBVector) {
      val red = (rgb >> 16) & 0xFF
      val green = (rgb >> 8) & 0xFF
      val blue = (rgb) & 0xFF

      // Calculate the index of this pixel in the histogramVector
      val idx = ((red * NUM_BINS) / 256) +
        4 * ((green * NUM_BINS) / 256) +
        16 * ((blue * NUM_BINS) / 256)
      histogramVector(idx) += 1
    }

    ROIFeature(histogramVector)
  }

  /**
   * Given a path to an image, extracts feature representation of that image
   */
  def featurizeImage(imgPath: String, regionWidth: Int, regionHeight: Int): Matrix[ROIFeature] = {

    // Use an additional frame whose thickness is given by this size around the patch
    val PATCH_CONTEXT_SIZE = 0

    val img: BufferedImage = ImageIO.read(new File(imgPath))

    val xmin = PATCH_CONTEXT_SIZE
    val ymin = PATCH_CONTEXT_SIZE

    val xmax = img.getWidth() - (regionWidth + PATCH_CONTEXT_SIZE)
    val ymax = img.getHeight() - (regionHeight + PATCH_CONTEXT_SIZE)

    val xstep = regionWidth
    val ystep = regionHeight

    val featureMask = DenseMatrix.zeros[ROIFeature](ymax, xmax)

    // Upper left of the image is (0, 0)
    for (
      y <- ymin until ymax by ystep;
      x <- xmin until xmax by xstep
    ) {
      // Extract a region given by coordinates (x, y) and (x + PATCH_WIDTH, y + PATCH_HEIGHT)
      val patch = img.getSubimage(x, y, regionWidth, regionHeight)
      val patchWithContext = img.getSubimage(max(0, x - PATCH_CONTEXT_SIZE),
        max(0, y - PATCH_CONTEXT_SIZE),
        min(img.getWidth(), regionWidth + PATCH_CONTEXT_SIZE),
        min(img.getHeight(), regionHeight + PATCH_CONTEXT_SIZE))
      val patchFeature = histogramFeaturizer(patch, patchWithContext)

      featureMask(x, y) = patchFeature
    }

    featureMask
  }

  /**
   * Given path to the Ground Truth (Image Mask), represent each pixel by its object class
   */
  def featurizeGT(gtPath: String, regionWidth: Int, regionHeight: Int): Matrix[ROILabel] = {

    val gtImage: BufferedImage = ImageIO.read(new File(gtPath))

    val xmin = 0
    val ymin = 0

    val xmax = gtImage.getWidth() - regionWidth
    val ymax = gtImage.getHeight() - regionHeight

    val xstep = regionWidth
    val ystep = regionHeight

    val labelMask = DenseMatrix.zeros[ROILabel](ymax, xmax)

    // Upper left of the image is (0, 0)
    for (
      y <- ymin until ymax by ystep;
      x <- xmin until xmax by xstep
    ) {

      val rgb = gtImage.getRGB(x, y) // Returns in TYPE_INT_ARGB format (4 bytes integer, in ARGB order)
      val red = (rgb >> 16) & 0xFF
      val green = (rgb >> 8) & 0xFF
      val blue = (rgb) & 0xFF

      val rgbIndex = blue + (255 * green) + (255 * 255 * red)
      val label = colormap(rgbIndex)

      labelMask(x, y) = ROILabel(label)
    }

    labelMask
  }

  /**
   * Returns a LabeledObject instance for an image and its corresponding labeled segments
   */
  def getLabeledObject(imgPath: String, gtPath: String): LabeledObject[Matrix[ROIFeature], Matrix[ROILabel]] = {

    val REGION_WIDTH = 5
    val REGION_HEIGHT = 5

    LabeledObject(featurizeGT(gtPath, REGION_WIDTH, REGION_HEIGHT), featurizeImage(imgPath, REGION_WIDTH, REGION_HEIGHT))
  }

  /**
   * Converts the MSRC dataset into an array of LabeledObjects
   * Requires dataFolder argument should contain two folders: "Images" and "GroundTruth"
   */
  def loadMSRC(msrcFolder: String): Array[LabeledObject[Matrix[ROIFeature], Matrix[ROILabel]]] = {

    // Split obtained from: http://graphics.stanford.edu/projects/densecrf/unary/
    // (trainSetFilenames uses training and validation sets)
    // These files contains filenames of respective GT images
    val trainSetFileListPath: String = "../data/imageseg_train.txt"
    val testSetFileListPath: String = "../data/imageseg_test.txt"

    val imagesDir: String = msrcFolder + "/Images"
    val gtDir: String = msrcFolder + "/GroundTruth"

    val data =
      for (imgFilename <- Source.fromFile(trainSetFileListPath).getLines().slice(0, 1)) yield {
        val imgPath = "%s/%s".format(imagesDir, imgFilename)

        val gtFilename = imgFilename.replace("_s", "_s_GT")
        val gtPath = "%s/%s".format(gtDir, gtFilename)

        getLabeledObject(imgPath, gtPath)
      }

    data.toArray
  }

  def main(args: Array[String]): Unit = {
    loadMSRC("../data/generated/MSRC_ObjCategImageDatabase_v2")
  }

}