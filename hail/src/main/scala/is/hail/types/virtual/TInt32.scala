package is.hail.types.virtual

import is.hail.annotations.{Region, _}
import is.hail.asm4s.Code
import is.hail.check.Arbitrary._
import is.hail.check.Gen
import is.hail.expr.ir.EmitMethodBuilder
import is.hail.types.physical.PInt32

import scala.reflect.{ClassTag, _}

case object TInt32 extends TIntegral {
  def _toPretty = "Int32"

  override def pyString(sb: StringBuilder): Unit = {
    sb.append("int32")
  }

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[Int]

  override def genNonmissingValue: Gen[Annotation] = arbitrary[Int]

  override def scalaClassTag: ClassTag[java.lang.Integer] = classTag[java.lang.Integer]

  override lazy val isSmall = true

  override val ordering: ExtendedOrdering = mkOrdering()

  override def mkOrdering(missingEqual: Boolean): ExtendedOrdering =
    ExtendedOrdering.extendToNull(implicitly[Ordering[Int]], missingEqual)
}
