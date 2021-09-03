package is.hail.types.physical.stypes.concrete

import is.hail.annotations.Region
import is.hail.asm4s._
import is.hail.expr.ir.{EmitCode, EmitCodeBuilder, IEmitCode}
import is.hail.types.physical.{PCanonicalNDArray, PNDArray, PType}
import is.hail.types.physical.stypes.interfaces._
import is.hail.types.physical.stypes.primitives.{SBooleanCode, SFloat32Code, SFloat64Code, SInt32Code, SInt64Code, SPrimitiveCode}
import is.hail.types.physical.stypes.{EmitType, SCode, SSettable, SType}
import is.hail.types.virtual._
import is.hail.utils.FastIndexedSeq
import is.hail.variant.ReferenceGenome

object SUnreachable {
  def fromVirtualType(t: Type): SType = {
    require(t.isRealizable)
    t match {
      case t if t.isPrimitive => SType.canonical(t)
      case ts: TBaseStruct => SUnreachableStruct(ts)
      case tc: TContainer => SUnreachableContainer(tc)
      case tnd: TNDArray => SUnreachableNDArray(tnd)
      case tl: TLocus => SUnreachableLocus(tl)
      case ti: TInterval => SUnreachableInterval(ti)
      case TCall => SUnreachableCall
      case TBinary => SUnreachableBinary
      case TString => SUnreachableString
      case TVoid => SVoid
    }
  }
}

abstract class SUnreachable extends SType {
  override def settableTupleTypes(): IndexedSeq[TypeInfo[_]] = FastIndexedSeq()

  override def storageType(): PType = PType.canonical(virtualType, required = false, innerRequired = true)

  override def asIdent: String = s"s_unreachable"

  override def castRename(t: Type): SType = SUnreachable.fromVirtualType(t)

  val sv: SUnreachableValue

  override def fromSettables(settables: IndexedSeq[Settable[_]]): SSettable = sv

  override def fromValues(values: IndexedSeq[Value[_]]): SUnreachableValue = sv

  override def _coerceOrCopy(cb: EmitCodeBuilder, region: Value[Region], value: SCode, deepCopy: Boolean): SCode = sv

  override def copiedType: SType = this

  override def containsPointers: Boolean = false
}

abstract class SUnreachableValue extends SCode with SSettable {
  def settableTuple(): IndexedSeq[Settable[_]] = FastIndexedSeq()

  def valueTuple: IndexedSeq[Value[_]] = FastIndexedSeq()

  def store(cb: EmitCodeBuilder, v: SCode): Unit = {}

  override def get: SCode = this

  // These overrides are needed to disambiguate inheritance from SCode and SValue.
  // Can remove when SCode is gone.
  override def castTo(cb: EmitCodeBuilder, region: Value[Region], destType: SType): SCode =
    castTo(cb, region, destType, false)

  override def castTo(cb: EmitCodeBuilder, region: Value[Region], destType: SType, deepCopy: Boolean): SCode = {
    destType.coerceOrCopy(cb, region, this, deepCopy)
  }

  override def copyToRegion(cb: EmitCodeBuilder, region: Value[Region], destType: SType): SCode =
    destType.coerceOrCopy(cb, region, this, deepCopy = true)
}

case class SUnreachableStruct(virtualType: TBaseStruct) extends SUnreachable with SBaseStruct {
  override def size: Int = virtualType.size

  val fieldTypes: IndexedSeq[SType] = virtualType.types.map(SUnreachable.fromVirtualType)
  val fieldEmitTypes: IndexedSeq[EmitType] = fieldTypes.map(f => EmitType(f, true))

  def fieldIdx(fieldName: String): Int = virtualType.fieldIdx(fieldName)

  val sv = new SUnreachableStructValue(this)
}

class SUnreachableStructValue(val st: SUnreachableStruct) extends SUnreachableValue with SBaseStructValue with SBaseStructCode {
  override def memoizeField(cb: EmitCodeBuilder, name: String): SBaseStructValue = this

  override def memoize(cb: EmitCodeBuilder, name: String): SBaseStructValue = this

  def loadField(cb: EmitCodeBuilder, fieldIdx: Int): IEmitCode = IEmitCode.present(cb, SUnreachable.fromVirtualType(st.virtualType.types(fieldIdx)).defaultValue)

  override def isFieldMissing(fieldIdx: Int): Code[Boolean] = false

  override def loadSingleField(cb: EmitCodeBuilder, fieldIdx: Int): IEmitCode = loadField(cb, fieldIdx)

  override def subset(fieldNames: String*): SBaseStructValue = {
    val oldType = st.virtualType.asInstanceOf[TStruct]
    val newType = TStruct(fieldNames.map(f => (f, oldType.fieldType(f))): _*)
    new SUnreachableStructValue(SUnreachableStruct(newType))
  }

  override def insert(cb: EmitCodeBuilder, region: Value[Region], newType: TStruct, fields: (String, EmitCode)*): SBaseStructCode =
    new SUnreachableStructValue(SUnreachableStruct(newType))

  override def _insert(newType: TStruct, fields: (String, EmitCode)*): SBaseStructCode =
    new SUnreachableStructValue(SUnreachableStruct(newType))

  override def get: SBaseStructCode = this
}

case object SUnreachableBinary extends SUnreachable with SBinary {
  override def virtualType: Type = TBinary

  val sv = new SUnreachableBinaryValue
}

class SUnreachableBinaryValue extends SUnreachableValue with SBinaryValue with SBinaryCode {
  override def memoizeField(cb: EmitCodeBuilder, name: String): SUnreachableBinaryValue = this

  override def memoize(cb: EmitCodeBuilder, name: String): SUnreachableBinaryValue = this

  override def loadByte(i: Code[Int]): Code[Byte] = const(0.toByte)

  override def loadBytes(): Code[Array[Byte]] = Code._null[Array[Byte]]

  override def loadLength(): Code[Int] = const(0)

  def st: SUnreachableBinary.type = SUnreachableBinary

  override def get: SUnreachableBinaryValue = this
}

case object SUnreachableString extends SUnreachable with SString {
  override def virtualType: Type = TString

  val sv = new SUnreachableStringValue

  override def constructFromString(cb: EmitCodeBuilder, r: Value[Region], s: Code[String]): SStringCode = sv
}

class SUnreachableStringValue extends SUnreachableValue with SStringValue with SStringCode {
  override def memoizeField(cb: EmitCodeBuilder, name: String): SUnreachableStringValue = this

  override def memoize(cb: EmitCodeBuilder, name: String): SUnreachableStringValue = this

  override def loadLength(): Code[Int] = const(0)

  def st: SUnreachableString.type = SUnreachableString

  override def loadString(): Code[String] = Code._null[String]

  override def toBytes(): SBinaryCode = new SUnreachableBinaryValue

  override def get: SUnreachableStringValue = this
}

case class SUnreachableLocus(virtualType: TLocus) extends SUnreachable with SLocus {
  val sv = new SUnreachableLocusValue(this)

  override def contigType: SString = SUnreachableString

  override def rg: ReferenceGenome = virtualType.rg
}

class SUnreachableLocusValue(val st: SUnreachableLocus) extends SUnreachableValue with SLocusValue with SLocusCode {
  override def memoizeField(cb: EmitCodeBuilder, name: String): SUnreachableLocusValue = this

  override def memoize(cb: EmitCodeBuilder, name: String): SUnreachableLocusValue = this

  override def position(cb: EmitCodeBuilder): Code[Int] = const(0)

  override def contig(cb: EmitCodeBuilder): SStringCode = new SUnreachableStringValue

  override def structRepr(cb: EmitCodeBuilder): SBaseStructValue = SUnreachableStruct(TStruct("contig" -> TString, "position" -> TInt32)).defaultValue.asInstanceOf[SUnreachableStructValue]
}


case object SUnreachableCall extends SUnreachable with SCall {
  override def virtualType: Type = TCall

  val sv = new SUnreachableCallValue
}

class SUnreachableCallValue extends SUnreachableValue with SCallValue with SCallCode {
  override def memoizeField(cb: EmitCodeBuilder, name: String): SUnreachableCallValue = this

  override def memoize(cb: EmitCodeBuilder, name: String): SUnreachableCallValue = this

  override def loadCanonicalRepresentation(cb: EmitCodeBuilder): Code[Int] = const(0)

  override def forEachAllele(cb: EmitCodeBuilder)(alleleCode: Value[Int] => Unit): Unit = {}

  override def isPhased(): Code[Boolean] = const(false)

  override def ploidy(): Code[Int] = const(0)

  override def canonicalCall(cb: EmitCodeBuilder): Code[Int] = const(0)

  def st: SUnreachableCall.type = SUnreachableCall

  override def get: SUnreachableCallValue = this

  override def lgtToGT(cb: EmitCodeBuilder, localAlleles: SIndexableValue, errorID: Value[Int]): SCallCode = this
}


case class SUnreachableInterval(virtualType: TInterval) extends SUnreachable with SInterval {
  val sv = new SUnreachableIntervalValue(this)

  override def pointType: SType = SUnreachable.fromVirtualType(virtualType.pointType)

  override def pointEmitType: EmitType = EmitType(pointType, true)
}

class SUnreachableIntervalValue(val st: SUnreachableInterval) extends SUnreachableValue with SIntervalValue with SIntervalCode {
  override def memoizeField(cb: EmitCodeBuilder, name: String): SUnreachableIntervalValue = this

  override def memoize(cb: EmitCodeBuilder, name: String): SUnreachableIntervalValue = this

  def includesStart(): Value[Boolean] = const(false)

  def includesEnd(): Value[Boolean] = const(false)

  def codeIncludesStart(): Code[Boolean] = const(false)

  def codeIncludesEnd(): Code[Boolean] = const(false)

  def loadStart(cb: EmitCodeBuilder): IEmitCode = IEmitCode.present(cb, SUnreachable.fromVirtualType(st.virtualType.pointType).defaultValue)

  def startDefined(cb: EmitCodeBuilder): Code[Boolean] = const(false)

  def loadEnd(cb: EmitCodeBuilder): IEmitCode = IEmitCode.present(cb, SUnreachable.fromVirtualType(st.virtualType.pointType).defaultValue)

  def endDefined(cb: EmitCodeBuilder): Code[Boolean] = const(false)

  def isEmpty(cb: EmitCodeBuilder): Code[Boolean] = const(false)
}


case class SUnreachableNDArray(virtualType: TNDArray) extends SUnreachable with SNDArray {
  val sv = new SUnreachableNDArrayValue(this)

  override def nDims: Int = virtualType.nDims

  lazy val elementType: SType = SUnreachable.fromVirtualType(virtualType.elementType)

  override def elementPType: PType = PType.canonical(elementType.storageType())

  override def pType: PNDArray = PCanonicalNDArray(elementPType.setRequired(true), nDims, false)

  override def elementByteSize: Long = 0L
}

class SUnreachableNDArrayValue(val st: SUnreachableNDArray) extends SUnreachableValue with SNDArraySettable with SNDArrayCode {
  override def memoizeField(cb: EmitCodeBuilder, name: String): SUnreachableNDArrayValue = this

  def shape(cb: EmitCodeBuilder): SBaseStructCode = SUnreachableStruct(TTuple((0 until st.nDims).map(_ => TInt64): _*)).defaultValue.get.asBaseStruct

  def loadElement(indices: IndexedSeq[Value[Long]], cb: EmitCodeBuilder): SCode = SUnreachable.fromVirtualType(st.virtualType.elementType).defaultValue

  def loadElementAddress(indices: IndexedSeq[is.hail.asm4s.Value[Long]],cb: is.hail.expr.ir.EmitCodeBuilder): is.hail.asm4s.Code[Long] = const(0L)

  def shapes: IndexedSeq[SizeValue] = (0 until st.nDims).map(_ => SizeValueStatic(0L))

  def strides: IndexedSeq[Value[Long]] = (0 until st.nDims).map(_ => const(0L))

  override def outOfBounds(indices: IndexedSeq[Value[Long]], cb: EmitCodeBuilder): Code[Boolean] = const(false)

  override def assertInBounds(indices: IndexedSeq[Value[Long]], cb: EmitCodeBuilder, errorId: Int = -1): Unit = {}

  override def sameShape(cb: EmitCodeBuilder, other: SNDArrayValue): Code[Boolean] = const(false)

  override def coerceToShape(cb: EmitCodeBuilder, otherShape: IndexedSeq[SizeValue]): SNDArrayValue = this

  def firstDataAddress: Value[Long] = const(0L)

  override def memoize(cb: EmitCodeBuilder, name: String): SUnreachableNDArrayValue = this

  override def get: SUnreachableNDArrayValue = this

  override def coiterateMutate(cb: EmitCodeBuilder, region: Value[Region], deepCopy: Boolean, indexVars: IndexedSeq[String],
    destIndices: IndexedSeq[Int], arrays: (SNDArrayCode, IndexedSeq[Int], String)*)(body: IndexedSeq[SCode] => SCode): Unit = ()
}

case class SUnreachableContainer(virtualType: TContainer) extends SUnreachable with SContainer {
  val sv = new SUnreachableContainerValue(this)

  lazy val elementType: SType = SUnreachable.fromVirtualType(virtualType.elementType)

  lazy val elementEmitType: EmitType = EmitType(elementType, true)
}

class SUnreachableContainerValue(val st: SUnreachableContainer) extends SUnreachableValue with SIndexableValue with SIndexableCode {
  override def memoizeField(cb: EmitCodeBuilder, name: String): SUnreachableContainerValue = this

  override def memoize(cb: EmitCodeBuilder, name: String): SUnreachableContainerValue = this

  def loadLength(): Value[Int] = const(0)

  override def codeLoadLength(): Code[Int] = const(0)

  def isElementMissing(i: Code[Int]): Code[Boolean] = const(false)

  def loadElement(cb: EmitCodeBuilder, i: Code[Int]): IEmitCode = IEmitCode.present(cb, SUnreachable.fromVirtualType(st.virtualType.elementType).defaultValue)

  def hasMissingValues(cb: EmitCodeBuilder): Code[Boolean] = const(false)

  def castToArray(cb: EmitCodeBuilder): SIndexableCode = SUnreachable.fromVirtualType(st.virtualType.arrayElementsRepr).defaultValue.get.asIndexable
}
