package is

import org.apache.spark.mllib.linalg.distributed._

package object hail {
  implicit def toRichIndexedRowMatrix(irm: IndexedRowMatrix): RichIndexedRowMatrix =
    new RichIndexedRowMatrix(irm)

}
