package is.hail

import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.linalg._
import org.apache.spark.mllib.linalg.distributed._

import scala.reflect.ClassTag

object BlockMatrixIsDistributedMatrix extends DistributedMatrix[BlockMatrix] {
  type M = BlockMatrix

  def cache(m: M): M = m.cache()

  def from(rdd: RDD[Array[Double]]): M =
    new IndexedRowMatrix(rdd.zipWithIndex().map { case (x, i) => new IndexedRow(i, new DenseVector(x)) })
      .toBlockMatrixDense()
  def from(bm: BlockMatrix): M = bm
  def from(cm: CoordinateMatrix): M = cm.toBlockMatrix()

  def transpose(m: M): M = m.transpose
  def diagonal(m: M): Array[Double] = toCoordinateMatrix(m)
    .entries
    .filter(me => me.i == me.j)
    .map(me => (me.i, me.value))
    .collect()
    .sortBy(_._1)
    .map(_._2)

  def multiply(l: M, r: M): M = l.multiply(r)

  def multiply(l: M, r: DenseMatrix): M = {
    val sc = l.blocks.sparkContext
    val rbc = sc.broadcast(r)
    val rRowsPerBlock = l.colsPerBlock
    val rColsPerBlock = l.rowsPerBlock
    val rRowBlocks = (r.numRows - 1) / rRowsPerBlock + 1
    val rColBlocks = (r.numCols - 1) / rColsPerBlock + 1
    val rRowsRemainder = r.numRows % rRowsPerBlock
    val rColsRemainder = r.numCols % rColsPerBlock
    val indices = for {
      i <- 0 until rRowBlocks
      j <- 0 until rColBlocks
    } yield (i, j)
    val rMats = sc.parallelize(indices).map { case (i, j) =>
      val rRowsInThisBlock = (if (i + 1 == rRowBlocks) rRowsRemainder else rRowsPerBlock)
      val rColsInThisBlock = (if (i + 1 == rColBlocks) rColsRemainder else rColsPerBlock)
      val a = new Array[Double](rRowsInThisBlock * rColsInThisBlock)
      for {
        ii <- 0 until rRowsInThisBlock
        jj <- 0 until rColsInThisBlock
      } {
        a(jj*rRowsInThisBlock + ii) = rbc.value(i*rRowsPerBlock + ii, j*rColsPerBlock + jj)
      }
      ((i, j), new DenseMatrix(rRowsInThisBlock, rColsInThisBlock, a, false): Matrix)
    }
    l.multiply(new BlockMatrix(rMats, rRowsPerBlock, rColsPerBlock, r.numRows, r.numCols))
  }

  def map4(op: (Double, Double, Double, Double) => Double)(a: M, b: M, c: M, d: M): M = {
    require(a.numRows() == b.numRows(), s"expected a's dimensions to match b's dimensions, but: ${a.numRows()} x ${a.numCols()},  ${b.numRows()} x ${b.numCols()}")
    require(b.numRows() == c.numRows(), s"expected b's dimensions to match c's dimensions, but: ${b.numRows()} x ${b.numCols()},  ${c.numRows()} x ${c.numCols()}")
    require(c.numRows() == d.numRows(), s"expected c's dimensions to match d's dimensions, but: ${c.numRows()} x ${c.numCols()},  ${d.numRows()} x ${d.numCols()}")
    require(a.numCols() == b.numCols())
    require(b.numCols() == c.numCols())
    require(c.numCols() == d.numCols())
    require(a.rowsPerBlock == b.rowsPerBlock)
    require(b.rowsPerBlock == c.rowsPerBlock)
    require(c.rowsPerBlock == d.rowsPerBlock)
    require(a.colsPerBlock == b.colsPerBlock)
    require(b.colsPerBlock == c.colsPerBlock)
    require(c.colsPerBlock == d.colsPerBlock)
    val blocks: RDD[((Int, Int), Matrix)] = a.blocks.join(b.blocks).join(c.blocks).join(d.blocks).map { case (block, (((m1, m2), m3), m4)) =>
      (block, new DenseMatrix(m1.numRows, m1.numCols, m1.toArray.zip(m2.toArray).zip(m3.toArray).zip(m4.toArray).map { case (((a, b), c), d) => op(a,b,c,d) }))
    }
    new BlockMatrix(blocks, a.rowsPerBlock, a.colsPerBlock, a.numRows(), a.numCols())
  }

  def map2(op: (Double, Double) => Double)(l: M, r: M): M = {
    require(l.numRows() == r.numRows())
    require(l.numCols() == r.numCols())
    require(l.rowsPerBlock == r.rowsPerBlock)
    require(l.colsPerBlock == r.colsPerBlock)
    val blocks: RDD[((Int, Int), Matrix)] = l.blocks.join(r.blocks).map { case (block, (m1, m2)) =>
      (block, new DenseMatrix(m1.numRows, m1.numCols, m1.toArray.zip(m2.toArray).map(op.tupled)))
    }
    new BlockMatrix(blocks, l.rowsPerBlock, l.colsPerBlock, l.numRows(), l.numCols())
  }

  def pointwiseAdd(l: M, r: M): M = map2(_ + _)(l, r)
  def pointwiseSubtract(l: M, r: M): M = map2(_ - _)(l, r)
  def pointwiseMultiply(l: M, r: M): M = map2(_ * _)(l, r)
  def pointwiseDivide(l: M, r: M): M = map2(_ / _)(l, r)

  def map(op: Double => Double)(m: M): M = {
    val blocks: RDD[((Int, Int), Matrix)] = m.blocks.map { case (block, m) =>
      (block, new DenseMatrix(m.numRows, m.numCols, m.toArray.map(op)))
    }
    new BlockMatrix(blocks, m.rowsPerBlock, m.colsPerBlock, m.numRows(), m.numCols())
  }
  def scalarAdd(m: M, i: Double): M = map(_ + i)(m)
  def scalarSubtract(m: M, i: Double): M = map(_ - i)(m)
  def scalarMultiply(m: M, i: Double): M = map(_ * i)(m)
  def scalarDivide(m: M, i: Double): M = map(_ / i)(m)
  def scalarAdd(i: Double, m: M): M = map(i + _)(m)
  def scalarSubtract(i: Double, m: M): M = map(i - _)(m)
  def scalarMultiply(i: Double, m: M): M = map(i * _)(m)
  def scalarDivide(i: Double, m: M): M = map(i / _)(m)

  private def mapWithRowIndex(op: (Double, Int) => Double)(x: M): M = {
    val nRows: Long = x.numRows
    val nCols: Long = x.numCols
    val rowsPerBlock: Int = x.rowsPerBlock
    val colsPerBlock: Int = x.colsPerBlock
    val rowBlocks: Int = ((nRows - 1) / rowsPerBlock).toInt + 1
    val colBlocks: Int = ((nCols - 1) / colsPerBlock).toInt + 1
    val rowsRemainder: Int = (nRows % rowsPerBlock).toInt
    val colsRemainder: Int = (nCols % colsPerBlock).toInt
    val blocks: RDD[((Int, Int), Matrix)] = x.blocks.map { case ((blockRow, blockCol), m) =>
      ((blockRow, blockCol), new DenseMatrix(m.numRows, m.numCols, m.toArray.zipWithIndex.map { case (e, j) =>
        val rowsInThisBlock: Int = (if (blockRow + 1 == rowBlocks) rowsRemainder else rowsPerBlock)
        val colsInThisBlock: Int = (if (blockCol + 1 == colBlocks) colsRemainder else colsPerBlock)
        if (blockRow.toLong * rowsInThisBlock + j % rowsInThisBlock < nRows &&
          blockCol.toLong * colsInThisBlock + j / rowsInThisBlock < nCols)
          op(e, blockRow * rowsInThisBlock + j % rowsInThisBlock)
        else
          e
      }))
    }
    new BlockMatrix(blocks, x.rowsPerBlock, x.colsPerBlock, x.numRows(), x.numCols())
  }
  private def mapWithColIndex(op: (Double, Int) => Double)(x: M): M = {
    val nRows = x.numRows
    val nCols = x.numCols
    val rowsPerBlock = x.rowsPerBlock
    val colsPerBlock = x.colsPerBlock
    val rowBlocks = ((nRows - 1) / rowsPerBlock).toInt + 1
    val colBlocks = ((nCols - 1) / colsPerBlock).toInt + 1
    val rowsRemainder = (nRows % rowsPerBlock).toInt
    val colsRemainder = (nCols % colsPerBlock).toInt
    val blocks: RDD[((Int, Int), Matrix)] = x.blocks.map { case ((blockRow, blockCol), m) =>
      ((blockRow, blockCol), new DenseMatrix(m.numRows, m.numCols, m.toArray.zipWithIndex.map { case (e, j) =>
        val rowsInThisBlock = (if (blockRow + 1 == rowBlocks) rowsRemainder else rowsPerBlock)
        val colsInThisBlock = (if (blockCol + 1 == colBlocks) colsRemainder else colsPerBlock)
        if (blockRow * rowsInThisBlock + j % rowsInThisBlock < nRows &&
          blockCol * colsInThisBlock + j / rowsInThisBlock < nCols)
          op(e, blockCol * colsInThisBlock + j / rowsInThisBlock)
        else
          e
      }))
    }
    new BlockMatrix(blocks, x.rowsPerBlock, x.colsPerBlock, x.numRows(), x.numCols())
  }

  def vectorAddToEveryColumn(v: Array[Double])(m: M): M = {
    require(v.length == m.numRows())
    val vbc = m.blocks.sparkContext.broadcast(v)
    mapWithRowIndex((x,i) => x + vbc.value(i))(m)
  }
  def vectorPointwiseMultiplyEveryColumn(v: Array[Double])(m: M): M = {
    require(v.length == m.numRows())
    val vbc = m.blocks.sparkContext.broadcast(v)
    mapWithRowIndex((x,i) => x * vbc.value(i))(m)
  }
  def vectorPointwiseMultiplyEveryRow(v: Array[Double])(m: M): M = {
    require(v.length == m.numCols())
    val vbc = m.blocks.sparkContext.broadcast(v)
    mapWithColIndex((x,i) => x * vbc.value(i))(m)
  }

  def mapRows[U](m: M, f: Array[Double] => U)(implicit uct: ClassTag[U]): RDD[U] =
    m.toIndexedRowMatrix().rows.map((ir: IndexedRow) => f(ir.vector.toArray))

  def toBlockRdd(m: M): RDD[((Int, Int), Matrix)] = m.blocks
  def toCoordinateMatrix(m: M): CoordinateMatrix = m.toCoordinateMatrix()

  def toLocalMatrix(m: M): Matrix = m.toLocalMatrix()
}
