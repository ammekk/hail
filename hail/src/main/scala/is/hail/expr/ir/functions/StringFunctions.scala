package is.hail.expr.ir.functions

import java.time.temporal.ChronoField
import java.time.{Instant, ZoneId}
import java.util.Locale
import is.hail.annotations.Region
import is.hail.asm4s._
import is.hail.expr.JSONAnnotationImpex
import is.hail.expr.ir._
import is.hail.types.physical._
import is.hail.types.physical.stypes._
import is.hail.types.physical.stypes.concrete.{SIndexablePointer, SJavaArrayString, SJavaString, SStringPointer}
import is.hail.types.physical.stypes.interfaces._
import is.hail.types.physical.stypes.primitives.{SBoolean, SInt32, SInt64}
import is.hail.types.virtual._
import is.hail.utils._
import org.apache.spark.sql.Row
import org.json4s.JValue
import org.json4s.jackson.JsonMethods

import java.util.regex.Pattern
import scala.collection.mutable

object StringFunctions extends RegistryFunctions {

  val ab = new BoxedArrayBuilder[String]()
  val sb = new StringBuilder()

  def reverse(s: String): String = {
    val sb = new StringBuilder
    sb.append(s)
    sb.reverseContents().result()
  }

  def upper(s: String): String = s.toUpperCase

  def lower(s: String): String = s.toLowerCase

  def strip(s: String): String = s.trim()

  def contains(s: String, t: String): Boolean = s.contains(t)

  def startswith(s: String, t: String): Boolean = s.startsWith(t)

  def endswith(s: String, t: String): Boolean = s.endsWith(t)

  def firstMatchIn(s: String, regex: String): Array[String] = {
    regex.r.findFirstMatchIn(s).map(_.subgroups.toArray).orNull
  }

  def regexMatch(regex: String, s: String): Boolean = regex.r.findFirstIn(s).isDefined

  def regexFullMatch(regex: String, s: String): Boolean = s.matches(regex)

  def concat(s: String, t: String): String = s + t

  def replace(str: String, pattern1: String, pattern2: String): String =
    str.replaceAll(pattern1, pattern2)

  def split(s: String, p: String): Array[String] = s.split(p, -1)

  def translate(s: String, d: Map[String, String]): String = {
    val charD = new mutable.HashMap[Char, String]
    d.foreach { case (k, v) =>
      if (k.length != 1)
        fatal(s"translate: mapping keys must be one character, found '$k'", ErrorIDs.NO_ERROR)
      charD += ((k(0), v))
    }

    val sb = new StringBuilder
    var i = 0
    while (i < s.length) {
      val charI = s(i)
      charD.get(charI) match {
        case Some(replacement) => sb.append(replacement)
        case None => sb.append(charI)
      }
      i += 1
    }
    sb.result()
  }

  def splitLimited(s: String, p: String, n: Int): Array[String] = s.split(p, n)

  def arrayMkString(a: Array[String], sep: String): String = a.mkString(sep)

  def setMkString(s: Set[String], sep: String): String = s.mkString(sep)

  def escapeString(s: String): String = StringEscapeUtils.escapeString(s)

  def splitQuoted2(s: String, separator: String, missing: Array[String], quote: String): Array[String] = {
    if (quote.size != 1 && quote != null) throw new HailException(s"quote length cannot be greater than 1," +
                                                 s" quote length entered ${quote.size}")

    val quoteC = if (quote == null) '\u0000' else quote.charAt(0)

    ab.clear()
    sb.clear()

    val testIfMissing = (field_entry: String) => {
      if (!missing.contains(field_entry)) field_entry
      else null
    }
    val matchSep: Int => Int = separator.length match {
      case 0 => fatal("Hail does not currently support 0-character separators")
      case 1 =>
        val sepChar = separator(0)
        (i: Int) => if (s(i) == sepChar) 1 else -1
      case _ =>
        val p = Pattern.compile(separator)
        val m = p.matcher(s)

        { (i: Int) =>
          m.region(i, s.length)
          if (m.lookingAt())
            m.end() - m.start()
          else
            -1
        }
    }

    var i = 0
    while (i < s.length) {
      val c = s(i)

      val l = matchSep(i)
      if (l != -1) {
        i += l
        ab += testIfMissing(sb.result())
        sb.clear()
      } else if (quoteC != '\u0000' && c == quoteC) {
        if (sb.nonEmpty)
          fatal(s"opening quote character '$quoteC' not at start of field")
        i += 1 // skip quote

        while (i < s.length && s(i) != quoteC) {
          sb += s(i)
          i += 1
        }

        if (i == s.length)
          fatal(s"missing terminating quote character '$quoteC'")
        i += 1 // skip quote

        // full field must be quoted
        if (i < s.length) {
          val l = matchSep(i)
          if (l == -1)
            fatal(s"terminating quote character '$quoteC' not at end of field")
          ab += testIfMissing(sb.result())
          sb.clear()
        }
      } else {
        sb += c
        i += 1
      }
    }
    ab += testIfMissing(sb.result())
    ab.result()
  }


  def splitQuoted(s: String, separator: String, missing: Array[String], quote: String): Array[String] = {

    ab.clear()

    val sepChar = separator(0)

    val testIfMissing = (field_entry: String) => {
      if (!missing.contains(field_entry)) field_entry
      else null
    }
    var offset = 0
    var nextDelim = s.indexOf(sepChar, offset)
    var nextQuote = if (quote == null) -1 else s.indexOf(quote, offset)
    var i = 0
    while (offset < s.length) {
      if (nextQuote != -1 && nextQuote <= nextDelim + 1) {
        if (nextQuote < nextDelim + 1) fatal(s"opening quote character $quote not at start of field")
        if (nextQuote == nextDelim + 1) {
          val closingQuote = s.indexOf(quote, nextQuote + 1)
          nextDelim = s.indexOf(sepChar, closingQuote)
          if (closingQuote == -1 || (nextDelim != closingQuote + 1 && s.length - 1 != closingQuote))
            fatal(s"terminating quote character $quote not at end of field")
            ab += testIfMissing(s.slice(nextQuote + 1, closingQuote))
            offset = closingQuote + 2
            nextQuote = s.indexOf(quote, closingQuote + 2)
        }
      }
      else {
        nextDelim = s.indexOf(sepChar, offset)
        if (nextDelim != -1) {
          ab += testIfMissing(s.slice(offset, nextDelim))
          offset = nextDelim + 1
        }
        else {
          ab += testIfMissing(s.slice(offset, s.length))
          offset = s.length()
        }

      }
    }
    val a = ab.result()
    a.map(x => println(x))
    a
  }

  def splitQuoted3(s: String, separator: String, missing: Array[String], quote: String): Array[String] = {

    val missingString = missing.mkString(" ")

    val quoteC = if (quote == null) '\u0000' else quote.charAt(0)

    ab.clear()
    sb.clear()
    val sepChar = separator(0)

    val testIfMissing = (field_entry: String) => {
      if (missingString.indexOf(field_entry) == -1) field_entry
      else null
    }

    var offset = 0
    while (offset < s.length) {
      val c = s(offset)
      if (c == sepChar) {
        ab += testIfMissing(sb.result())
        sb.clear()
        offset += 1
      } else if (quoteC != '\u0000' && c == quoteC) {
        if (sb.nonEmpty)
          fatal(s"opening quote character '$quoteC' not at start of field")
        offset += 1 // skip quote

        var indexOfNextQuote = -1
        while (offset < s.length) {
          offset += 1
          if (s(offset) == quoteC) indexOfNextQuote = offset
          else sb += s(offset)
        }
        if (indexOfNextQuote == -1)
          fatal(s"missing terminating quote character '$quoteC'")
        offset += 1 // skip quote

        // full field must be quoted
        if (offset < s.length - 1) {
          if (s(offset + 1) != sepChar)
            fatal(s"terminating quote character '$quoteC' not at end of field")
          offset += 1
          ab += testIfMissing(sb.result())
          sb.clear()
        }
      } else {
        sb += c
        offset += 1
      }
    }
    ab += testIfMissing(sb.result())
    val a = ab.result()
    a
  }

  def softBounds(i: IR, len: IR): IR =
    If(i < -len, 0, If(i < 0, i + len, If(i >= len, len, i)))

  private val locale: Locale = Locale.US

  def strftime(fmtStr: String, epochSeconds: Long, zoneId: String): String =
    DateFormatUtils.parseDateFormat(fmtStr, locale).withZone(ZoneId.of(zoneId))
      .format(Instant.ofEpochSecond(epochSeconds))

  def strptime(timeStr: String, fmtStr: String, zoneId: String): Long =
    DateFormatUtils.parseDateFormat(fmtStr, locale).withZone(ZoneId.of(zoneId))
      .parse(timeStr)
      .getLong(ChronoField.INSTANT_SECONDS)

  def registerAll(): Unit = {
    val thisClass = getClass

    registerSCode1("length", TString, TInt32, (_: Type, _: SType) => SInt32) { case (r: EmitRegion, cb, _, s: SStringCode, _) =>
      primitive(s.loadString().invoke[Int]("length"))
    }

    registerSCode3("substring", TString, TInt32, TInt32, TString, {
      (_: Type, _: SType, _: SType, _: SType) => SJavaString
    }) {
      case (r: EmitRegion, cb, st: SJavaString.type, s, start, end, _) =>
        val str = s.asString.loadString().invoke[Int, Int, String]("substring", start.asInt.intCode(cb), end.asInt.intCode(cb))
        st.construct(cb, str).get
    }

    registerIR3("slice", TString, TInt32, TInt32, TString) { (_, str, start, end, _) =>
      val len = Ref(genUID(), TInt32)
      val s = Ref(genUID(), TInt32)
      val e = Ref(genUID(), TInt32)
      Let(len.name, invoke("length", TInt32, str),
        Let(s.name, softBounds(start, len),
          Let(e.name, softBounds(end, len),
            invoke("substring", TString, str, s, If(e < s, s, e)))))
    }

    registerIR2("index", TString, TInt32, TString) { (_, s, i, errorID) =>
      val len = Ref(genUID(), TInt32)
      val idx = Ref(genUID(), TInt32)
      Let(len.name, invoke("length", TInt32, s),
        Let(idx.name,
          If((i < -len) || (i >= len),
            Die(invoke("concat", TString,
              Str("string index out of bounds: "),
              invoke("concat", TString,
                invoke("str", TString, i),
                invoke("concat", TString, Str(" / "), invoke("str", TString, len)))), TInt32, errorID),
            If(i < 0, i + len, i)),
          invoke("substring", TString, s, idx, idx + 1)))
    }

    registerIR2("sliceRight", TString, TInt32, TString) { (_, s, start, _) => invoke("slice", TString, s, start, invoke("length", TInt32, s)) }
    registerIR2("sliceLeft", TString, TInt32, TString) { (_, s, end, _) => invoke("slice", TString, s, I32(0), end) }

    registerSCode1("str", tv("T"), TString, (_: Type, _: SType) => SJavaString) { case (r, cb, st: SJavaString.type, a, _) =>
      val annotation = scodeToJavaValue(cb, r.region, a)
      val str = cb.emb.getType(a.st.virtualType).invoke[Any, String]("str", annotation)
      st.construct(cb, str).get
    }

    registerIEmitCode1("showStr", tv("T"), TString, {
      (_: Type, _: EmitType) => EmitType(SJavaString, true)
    }) { case (cb, r, st: SJavaString.type, _, a) =>
      val jObj = cb.newLocal("showstr_java_obj")(boxedTypeInfo(a.st.virtualType))
      a.toI(cb).consume(cb,
        cb.assignAny(jObj, Code._null(boxedTypeInfo(a.st.virtualType))),
        sc => cb.assignAny(jObj, scodeToJavaValue(cb, r, sc.get)))

      val str = cb.emb.getType(a.st.virtualType).invoke[Any, String]("showStr", jObj)

      IEmitCode.present(cb, st.construct(cb, str))
    }

    registerIEmitCode2("showStr", tv("T"), TInt32, TString, {
      (_: Type, _: EmitType, truncType: EmitType) => EmitType(SJavaString, truncType.required)
    }) { case (cb, r, st: SJavaString.type, _, a, trunc) =>
      val jObj = cb.newLocal("showstr_java_obj")(boxedTypeInfo(a.st.virtualType))
      trunc.toI(cb).map(cb) { trunc =>

        a.toI(cb).consume(cb,
          cb.assignAny(jObj, Code._null(boxedTypeInfo(a.st.virtualType))),
          sc => cb.assignAny(jObj, scodeToJavaValue(cb, r, sc.get)))

        val str = cb.emb.getType(a.st.virtualType).invoke[Any, Int, String]("showStr", jObj, trunc.asInt.intCode(cb))
        st.construct(cb, str)
      }
    }

    registerIEmitCode1("json", tv("T"), TString, (_: Type, _: EmitType) => EmitType(SJavaString, true)) {
      case (cb, r, st: SJavaString.type, _, a) =>
        val ti = boxedTypeInfo(a.st.virtualType)
        val inputJavaValue = cb.newLocal("json_func_input_jv")(ti)
        a.toI(cb).consume(cb,
          cb.assignAny(inputJavaValue, Code._null(ti)),
          { sc =>
            val jv = scodeToJavaValue(cb, r, sc.get)
            cb.assignAny(inputJavaValue, jv)
          })
        val json = cb.emb.getType(a.st.virtualType).invoke[Any, JValue]("toJSON", inputJavaValue)
        val str = Code.invokeScalaObject1[JValue, String](JsonMethods.getClass, "compact", json)
        IEmitCode.present(cb, st.construct(cb, str))
    }

    registerWrappedScalaFunction1("reverse", TString, TString, (_: Type, _: SType) => SJavaString)(thisClass, "reverse")
    registerWrappedScalaFunction1("upper", TString, TString, (_: Type, _: SType) => SJavaString)(thisClass, "upper")
    registerWrappedScalaFunction1("lower", TString, TString, (_: Type, _: SType) => SJavaString)(thisClass, "lower")
    registerWrappedScalaFunction1("strip", TString, TString, (_: Type, _: SType) => SJavaString)(thisClass, "strip")
    registerWrappedScalaFunction2("contains", TString, TString, TBoolean, {
      case (_: Type, _: SType, _: SType) => SBoolean
    })(thisClass, "contains")
    registerWrappedScalaFunction2("translate", TString, TDict(TString, TString), TString, {
      case (_: Type, _: SType, _: SType) => SJavaString
    })(thisClass, "translate")
    registerWrappedScalaFunction2("startswith", TString, TString, TBoolean, {
      case (_: Type, _: SType, _: SType) => SBoolean
    })(thisClass, "startswith")
    registerWrappedScalaFunction2("endswith", TString, TString, TBoolean, {
      case (_: Type, _: SType, _: SType) => SBoolean
    })(thisClass, "endswith")
    registerWrappedScalaFunction2("regexMatch", TString, TString, TBoolean, {
      case (_: Type, _: SType, _: SType) => SBoolean
    })(thisClass, "regexMatch")
    registerWrappedScalaFunction2("regexFullMatch", TString, TString, TBoolean, {
      case (_: Type, _: SType, _: SType) => SBoolean
    })(thisClass, "regexFullMatch")
    registerWrappedScalaFunction2("concat", TString, TString, TString, {
      case (_: Type, _: SType, _: SType) => SJavaString
    })(thisClass, "concat")

    registerWrappedScalaFunction2("split", TString, TString, TArray(TString), {
      case (_: Type, _: SType, _: SType) =>
        SJavaArrayString(true)
    })(thisClass, "split")

    registerWrappedScalaFunction3("split", TString, TString, TInt32, TArray(TString), {
      case (_: Type, _: SType, _: SType, _: SType) =>
        SJavaArrayString(true)
    })(thisClass, "splitLimited")

    registerWrappedScalaFunction3("replace", TString, TString, TString, TString, {
      case (_: Type, _: SType, _: SType, _: SType) => SJavaString
    })(thisClass, "replace")

    registerWrappedScalaFunction2("mkString", TSet(TString), TString, TString, {
      case (_: Type, _: SType, _: SType) => SJavaString
    })(thisClass, "setMkString")

    registerWrappedScalaFunction4("splitQuoted", TString, TString, TArray(TString),  TString, TArray(TString), {
      case (_: Type, _: SType, _: SType, _: SType, _:SType) => SJavaArrayString(false)
    })(thisClass, "splitQuoted")

    registerWrappedScalaFunction2("mkString", TArray(TString), TString, TString, {
      case (_: Type, _: SType, _: SType) => SJavaString
    })(thisClass, "arrayMkString")

    registerIEmitCode2("firstMatchIn", TString, TString, TArray(TString), {
      case (_: Type, _: EmitType, _: EmitType) => EmitType(SJavaArrayString(true), false)
    }) { case (cb: EmitCodeBuilder, region: Value[Region], st: SJavaArrayString, _,
    s: EmitCode, r: EmitCode) =>
      s.toI(cb).flatMap(cb) { case sc: SStringValue =>
        r.toI(cb).flatMap(cb) { case rc: SStringValue =>
          val out = cb.newLocal[Array[String]]("out",
            Code.invokeScalaObject2[String, String, Array[String]](
              thisClass, "firstMatchIn", sc.loadString(cb), rc.loadString(cb)))
          IEmitCode(cb, out.isNull, st.construct(cb, out))
        }
      }
    }

    registerEmitCode2("hamming", TString, TString, TInt32, {
      case (_: Type, _: EmitType, _: EmitType) => EmitType(SInt32, false)
    }) { case (r: EmitRegion, rt, _, e1: EmitCode, e2: EmitCode) =>
      EmitCode.fromI(r.mb) { cb =>
        e1.toI(cb).flatMap(cb) { case sc1: SStringValue =>
          e2.toI(cb).flatMap(cb) { case sc2: SStringValue =>
            val n = cb.newLocal("hamming_n", 0)
            val i = cb.newLocal("hamming_i", 0)

            val v1 = cb.newLocal[String]("hamming_str_1", sc1.loadString(cb))
            val v2 = cb.newLocal[String]("hamming_str_2", sc2.loadString(cb))

            val l1 = cb.newLocal[Int]("hamming_len_1", v1.invoke[Int]("length"))
            val l2 = cb.newLocal[Int]("hamming_len_2", v2.invoke[Int]("length"))
            val m = l1.cne(l2)

            IEmitCode(cb, m, {
              cb.whileLoop(i < l1, {
                cb.ifx(v1.invoke[Int, Char]("charAt", i).toI.cne(v2.invoke[Int, Char]("charAt", i).toI),
                  cb.assign(n, n + 1))
                cb.assign(i, i + 1)
              })
              primitive(n)
            })
          }
        }
      }
    }

    registerWrappedScalaFunction1("escapeString", TString, TString, (_: Type, _: SType) => SJavaString)(thisClass, "escapeString")
    registerWrappedScalaFunction3("strftime", TString, TInt64, TString, TString, {
      case (_: Type, _: SType, _: SType, _: SType) => SJavaString
    })(thisClass, "strftime")
    registerWrappedScalaFunction3("strptime", TString, TString, TString, TInt64, {
      case (_: Type, _: SType, _: SType, _: SType) => SInt64
    })(thisClass, "strptime")

    registerSCode("parse_json", Array(TString), TTuple(tv("T")),
      (rType: Type, _: Seq[SType]) => SType.canonical(rType), typeParameters = Array(tv("T"))
    ) { case (er, cb, _, resultType, Array(s: SStringCode), _) =>

      val warnCtx = cb.emb.genFieldThisRef[mutable.HashSet[String]]("parse_json_context")
      cb.ifx(warnCtx.load().isNull, cb.assign(warnCtx, Code.newInstance[mutable.HashSet[String]]()))

      val row = Code.invokeScalaObject3[String, Type, mutable.HashSet[String], Row](JSONAnnotationImpex.getClass, "irImportAnnotation",
        s.loadString(), er.mb.ecb.getType(resultType.virtualType.asInstanceOf[TTuple].types(0)), warnCtx)

      unwrapReturn(cb, er.region, resultType, row)
    }
  }
}
