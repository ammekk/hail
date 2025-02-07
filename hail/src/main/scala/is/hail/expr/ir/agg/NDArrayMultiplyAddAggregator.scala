package is.hail.expr.ir.agg

import is.hail.annotations.Region
import is.hail.asm4s.{UnitInfo, Value, _}
import is.hail.expr.ir.{CodeParamType, EmitCode, EmitCodeBuilder}
import is.hail.linalg.LinalgCodeUtils
import is.hail.types.VirtualTypeWithReq
import is.hail.types.physical.stypes.interfaces.{SNDArray, SNDArrayCode, SNDArrayValue}
import is.hail.types.physical.{PCanonicalNDArray, PType}
import is.hail.types.virtual.Type
import is.hail.utils.{FastIndexedSeq, valueToRichCodeRegion}

class NDArrayMultiplyAddAggregator(nDVTyp: VirtualTypeWithReq) extends StagedAggregator {
  private val ndTyp = nDVTyp.canonicalPType.setRequired(false).asInstanceOf[PCanonicalNDArray]

  override type State = TypedRegionBackedAggState

  override def resultType: PType = ndTyp

  override def initOpTypes: Seq[Type] = Array[Type]()

  override def seqOpTypes: Seq[Type] = Array(ndTyp.virtualType, ndTyp.virtualType)

  val ndarrayFieldNumber = 0

  override protected def _initOp(cb: EmitCodeBuilder, state: State, init: Array[EmitCode]): Unit = {
    val initMethod = cb.emb.genEmitMethod[Unit]("ndarray_multiply_add_aggregator_init_op")
    initMethod.voidWithBuilder(cb =>
      state.storeMissing(cb)
    )
    cb.invokeVoid(initMethod)
  }

  override protected def _seqOp(cb: EmitCodeBuilder, state: State, seq: Array[EmitCode]): Unit = {
    val Array(nextNDArrayACode, nextNDArrayBCode) = seq
    val seqOpMethod = cb.emb.genEmitMethod("ndarray_add_multiply_aggregator_seq_op",
      FastIndexedSeq(nextNDArrayACode.emitParamType, nextNDArrayBCode.emitParamType), CodeParamType(UnitInfo))
    seqOpMethod.voidWithBuilder { cb =>
      val ndArrayAEmitCode = seqOpMethod.getEmitParam(cb, 1, null)
      ndArrayAEmitCode.toI(cb).consume(cb, {}, { case checkA: SNDArrayValue =>
        val ndArrayBEmitCode = seqOpMethod.getEmitParam(cb, 2, null)
        ndArrayBEmitCode.toI(cb).consume(cb, {}, { case checkB: SNDArrayValue =>
          val tempRegionForCreation = cb.newLocal[Region]("ndarray_add_multily_agg_temp_region", Region.stagedCreate(Region.REGULAR, cb.emb.ecb.pool()))
          val NDArrayA = LinalgCodeUtils.checkColMajorAndCopyIfNeeded(checkA, cb, tempRegionForCreation)
          val NDArrayB = LinalgCodeUtils.checkColMajorAndCopyIfNeeded(checkB, cb, tempRegionForCreation)
          val statePV = state.storageType.loadCheapSCode(cb, state.off).asBaseStruct
          statePV.loadField(cb, ndarrayFieldNumber).consume(cb,
            {
              cb += state.region.getNewRegion(Region.REGULAR)
              state.storageType.setFieldPresent(cb, state.off.get, ndarrayFieldNumber)
              val shape = IndexedSeq(NDArrayA.shapes(0), NDArrayB.shapes(1))
              val uninitializedNDArray = ndTyp.constructUnintialized(shape, ndTyp.makeColumnMajorStrides(shape, tempRegionForCreation, cb), cb, tempRegionForCreation)
              state.storeNonmissing(cb, uninitializedNDArray)
              SNDArray.gemm(cb, "N", "N", const(1.0), NDArrayA.get, NDArrayB.get, const(0.0), uninitializedNDArray.get)
            },
            { currentNDPValue =>
              SNDArray.gemm(cb, "N", "N", NDArrayA.get, NDArrayB.get, currentNDPValue.asNDArray.get)
            }
          )
          cb += tempRegionForCreation.clearRegion()
        })
      })
    }
    cb.invokeVoid(seqOpMethod, nextNDArrayACode, nextNDArrayBCode)
  }

  override protected def _combOp(cb: EmitCodeBuilder, state: State, other: State): Unit = {
    val combOpMethod = cb.emb.genEmitMethod[Unit]("ndarraymutiply_add_agg_comb_op")

    combOpMethod.voidWithBuilder { cb =>
      val rightPV = other.storageType.loadCheapSCode(cb, other.off).asBaseStruct
      rightPV.loadField(cb, ndarrayFieldNumber).consume(cb, {},
        { case rightNdValue: SNDArrayValue =>
          val leftPV = state.storageType.loadCheapSCode(cb, state.off).asBaseStruct
          leftPV.loadField(cb, ndarrayFieldNumber).consume(cb,
            {
              state.storeNonmissing(cb, rightNdValue)
            },
            { case leftNdValue: SNDArrayValue =>
              NDArraySumAggregator.addValues(cb, state.region, leftNdValue, rightNdValue)
            })
        }
      )
    }
    cb.invokeVoid(combOpMethod)
  }

  override protected def _storeResult(cb: EmitCodeBuilder, state: State, pt: PType, addr: Value[Long], region: Value[Region], ifMissing: EmitCodeBuilder => Unit): Unit = {
    state.get(cb).consume(cb,
      ifMissing(cb),
      { sc => pt.storeAtAddress(cb, addr, region, sc.asNDArray, deepCopy = true) })
  }
}
