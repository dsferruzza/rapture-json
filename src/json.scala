/**********************************************************************************************\
* Rapture JSON Library                                                                         *
* Version 0.9.0                                                                                *
*                                                                                              *
* The primary distribution site is                                                             *
*                                                                                              *
*   http://rapture.io/                                                                         *
*                                                                                              *
* Copyright 2010-2014 Jon Pretty, Propensive Ltd.                                              *
*                                                                                              *
* Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file    *
* except in compliance with the License. You may obtain a copy of the License at               *
*                                                                                              *
*   http://www.apache.org/licenses/LICENSE-2.0                                                 *
*                                                                                              *
* Unless required by applicable law or agreed to in writing, software distributed under the    *
* License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,    *
* either express or implied. See the License for the specific language governing permissions   *
* and limitations under the License.                                                           *
\**********************************************************************************************/
package rapture.json

import rapture.core._

import scala.collection.mutable.{ListBuffer, HashMap}

import language.{dynamics, higherKinds}

object DataCompanion {
  object Empty
}

trait DataType[+T <: DataType[T, ParserType], ParserType[S] <: DataParser[S]] extends Dynamic {
  def companion: DataCompanion[T, ParserType]
  def root: Any
  private[json] def rootNode: Array[Any]
  implicit def parser: ParserType[_]
  def path: Vector[Either[Int, String]]
  private[json] def normalize(orEmpty: Boolean): Any
  
  def format: String = companion.format(Some(rootNode(0)), 0, parser, " ", "\n")

  def serialize: String = companion.format(Some(normalize(false)), 0, parser, "", "")
}

trait DataCompanion[+Type <: DataType[Type, ParserType], ParserType[S] <: DataParser[S]] {
  
  def construct(any: Any, path: Vector[Either[Int, String]])(implicit parser: ParserType[_]): Type = constructRaw(Array(any), path)
  
  def constructRaw(any: Array[Any], path: Vector[Either[Int, String]])(implicit parser: ParserType[_]): Type
  
  def parse[Source: ParserType](s: Source)(implicit eh: ExceptionHandler):
      eh.![Type, ParseException] = eh.wrap {
    construct(try implicitly[ParserType[Source]].parse(s).get catch {
      case e: NoSuchElementException => throw new ParseException(s.toString)
    }, Vector())
  }
  
  def apply[T: Jsonizer](t: T)(implicit parser: ParserType[_]): Type =
    construct(implicitly[Jsonizer[T]].jsonize(t), Vector())
  
  def unapply(value: Any)(implicit parser: ParserType[_]): Option[Type] =
    Some(construct(value, Vector()))

  def format(value: Option[Any], ln: Int, parser: ParserType[_], pad: String,
      brk: String): String
  
}

trait JsonDataCompanion[+Type <: JsonDataType[Type, ParserType],
    ParserType[S] <: JsonParser[S]] extends DataCompanion[Type, ParserType] {

  /** Formats the JSON object for multi-line readability. */
  def format(json: Option[Any], ln: Int, parser: ParserType[_], pad: String = " ",
      brk: String = "\n"): String = {
    val indent = " "*ln
    json match {
      case None => "null"
      case Some(j) =>
        if(parser.isString(j)) {
          "\""+parser.getString(j).replaceAll("\\\\", "\\\\\\\\").replaceAll("\r",
              "\\\\r").replaceAll("\n", "\\\\n").replaceAll("\"", "\\\\\"")+"\""
        } else if(parser.isBoolean(j)) {
          if(parser.getBoolean(j)) "true" else "false"
        } else if(parser.isNumber(j)) {
          val n = parser.getDouble(j)
          if(n == n.floor) n.toInt.toString else n.toString
        } else if(parser.isArray(j)) {
          List("[", parser.getArray(j) map { v =>
            s"${indent}${pad}${format(Some(v), ln + 1, parser)}"
          } mkString s",${brk}", s"${indent}]") mkString brk
        } else if(parser.isObject(j)) {
          List("{", parser.getKeys(j) map { k =>
            val inner = try Some(parser.dereferenceObject(j, k)) catch {
              case e: Exception => None
            }
            s"""${indent}${pad}"${k}":${pad}${format(inner, ln + 1, parser)}"""
          } mkString s",${brk}", s"${indent}}") mkString brk
        } else if(parser.isNull(j)) "null"
        else if(j == DataCompanion.Empty) "empty"
        else "undefined"
    }
  }
  
}
object JsonBuffer extends JsonDataCompanion[JsonBuffer, JsonBufferParser] {
  
  def constructRaw(any: Array[Any], path: Vector[Either[Int, String]])(implicit parser:
      JsonBufferParser[_]): JsonBuffer = new JsonBuffer(any, path)
}

/** Companion object to the `Json` type, providing factory and extractor methods, and a JSON
  * pretty printer. */
object Json extends JsonDataCompanion[Json, JsonParser] {

  def constructRaw(any: Array[Any], path: Vector[Either[Int, String]])(implicit parser: JsonParser[_]): Json =
    new Json(any, path)
  
}

/** Represents some parsed JSON. */
class Json(val rootNode: Array[Any], val path: Vector[Either[Int, String]] = Vector())(implicit
    val parser: JsonParser[_]) extends JsonDataType[Json, JsonParser] {

  val companion = Json
  def root = rootNode(0)
  
  def $accessInnerJsonMap(k: String): Any = parser.dereferenceObject(rootNode(0), k)

  override def equals(any: Any) = any match {
    case any: Json => rootNode(0) == any.rootNode(0)
    case _ => false
  }

  override def hashCode = rootNode(0).hashCode & "json".hashCode

}

trait JsonDataType[+T <: JsonDataType[T, ParserType], ParserType[S] <: JsonParser[S]]
    extends DataType[T, ParserType] {
  
  def apply(i: Int): T =
    companion.constructRaw(rootNode, Left(i) +: path)
  
  /** Combines a `selectDynamic` and an `apply`.  This is necessary due to the way dynamic
    * application is expanded. */
  def applyDynamic(key: String)(i: Int): T = selectDynamic(key).apply(i)
  
  /** Assumes the Json object is wrapping a `T`, and casts (intelligently) to that type. */
  def get[T](implicit ext: Extractor[T], eh: ExceptionHandler): eh.![T, JsonGetException] =
    eh wrap {
      try ext.rawConstruct(normalize(false), parser) catch {
        case TypeMismatchException(f, e, _) => throw TypeMismatchException(f, e, path)
        case e: MissingValueException => throw e
      }
    }
  
  /** Assumes the Json object wraps a `Map`, and extracts the element `key`. */
  def selectDynamic(key: String): T =
    companion.constructRaw(rootNode, Right(key) +: path)

  def extract(sp: Vector[String]): JsonDataType[T, ParserType] =
    if(sp.isEmpty) this else selectDynamic(sp.head).extract(sp.tail)
  
  override def toString =
    try Json.format(Some(normalize(false)), 0, parser) catch {
      case e: JsonGetException => "undefined"
    }
  private[json] def normalize(orEmpty: Boolean): Any =
    yCombinator[(Any, Vector[Either[Int, String]]), Any] { fn => _ match {
      case (j, Vector()) => j: Any
      case (j, t :+ Right(k)) =>
        fn(({
          if(parser.isObject(j)) {
            try parser.dereferenceObject(j, k) catch {
              case TypeMismatchException(f, e, _) =>
                TypeMismatchException(f, e, path.drop(t.length))
              case e: Exception =>
                if(orEmpty) DataCompanion.Empty
                else throw MissingValueException(path.drop(t.length))
            }
          } else {
            throw TypeMismatchException(parser.getType(j), JsonTypes.Object,
              path.drop(t.length))
          }
        }, t))
      case (j, t :+ Left(i)) =>
        fn((
          if(parser.isArray(j)) {
            try parser.dereferenceArray(j, i) catch {
              case TypeMismatchException(f, e, _) =>
                TypeMismatchException(f, e, path.drop(t.length))
              case e: Exception =>
                if(orEmpty) DataCompanion.Empty
                else throw MissingValueException(path.drop(t.length))
            }
          } else {
            throw TypeMismatchException(parser.getType(j), JsonTypes.Array, path.drop(t.length))
          }
        , t))
    } } (root -> path)
}

trait MutableJsonDataType[+T <: MutableJsonDataType[T, ParserType], ParserType[S] <: JsonParser[S]] extends JsonDataType[T, ParserType] {
}

class JsonBuffer(private[json] val rootNode: Array[Any], val path: Vector[Either[Int, String]] = Vector())
    (implicit val parser: JsonBufferParser[_]) extends MutableJsonDataType[JsonBuffer, JsonBufferParser] {
 
  val companion = JsonBuffer
  def root = rootNode(0)
  
  /** Updates the element `key` of the JSON object with the value `v` */
  def updateDynamic(key: String)(v: Jsonized): Unit =
    updateParents(path, parser.setObjectValue(normalizeOrEmpty, key, v.value))
 
  /** Updates the `i`th element of the JSON array with the value `v` */
  def update[T: Jsonizer](i: Int, v: T): Unit =
    updateParents(path, parser.setArrayValue(normalizeOrNil, i,
        implicitly[Jsonizer[T]].jsonize(v)))

  protected def updateParents(p: Vector[Either[Int, String]], newVal: Any): Unit =
    p match {
      case Vector() =>
        rootNode(0) = newVal
      case Left(idx) +: init =>
        val jb = companion.constructRaw(rootNode, init)
        updateParents(init, parser.setArrayValue(jb.normalizeOrNil, idx, newVal))
      case Right(key) +: init =>
        val jb = companion.constructRaw(rootNode, init)
        updateParents(init, parser.setObjectValue(jb.normalizeOrEmpty, key, newVal))
    }

  /** Removes the specified key from the JSON object */
  def -=(k: String): Unit = updateParents(path, parser.removeObjectValue(normalize(true), k))

  /** Adds the specified value to the JSON array */
  def +=[T: Jsonizer](v: T): Unit = {
    val r = normalize(true)
    val insert = if(r == DataCompanion.Empty) parser.fromArray(Nil) else r
    updateParents(path, parser.addArrayValue(insert, implicitly[Jsonizer[T]].jsonize(v)))
  }
  
  /** Navigates the JSON using the `List[String]` parameter, and returns the element at that
    * position in the tree. */
  private[json] def normalizeOrNil: Any =
    try normalize(false) catch { case e: Exception => parser.fromArray(List()) }

  private[json] def normalizeOrEmpty: Any =
    try normalize(false) catch { case e: Exception => parser.fromObject(Map()) }
}

class AnyExtractor[T](cast: Json => T) extends BasicExtractor[T](x => cast(x))

case class BasicExtractor[T](val cast: Json => T) extends Extractor[T] {
  def construct(js: Json) = cast(js)
}

case class CascadeExtractor[T](casts: (Json => T)*) extends Extractor[T] {
  def construct(js: Json) = {
    (casts.foldLeft(None: Option[T]) { case (v, next) =>
      v orElse { try Some(next(js)) catch { case e: Exception => None } }
    }).get
  }
}

object JsonGetException {
  def stringifyPath(path: Vector[Either[Int, String]]) = path.reverse map {
    case Left(i) => s"($i)"
    case Right(s) => s".$s"
  } mkString ""
}

sealed class JsonGetException(msg: String) extends RuntimeException(msg)

case class TypeMismatchException(foundType: JsonTypes.JsonType,
    expectedType: JsonTypes.JsonType, path: Vector[Either[Int, String]]) extends
    JsonGetException(s"Type mismatch: Expected ${expectedType.name} but found "+
    s"${foundType.name} at json${JsonGetException.stringifyPath(path)}")

case class MissingValueException(path: Vector[Either[Int, String]])
  extends JsonGetException(s"Missing value: json${JsonGetException.stringifyPath(path)}")

object Jsonized {
  implicit def toJsonized[T: Jsonizer](t: T) = Jsonized(implicitly[Jsonizer[T]].jsonize(t))
}
case class Jsonized(private[json] var value: Any)

