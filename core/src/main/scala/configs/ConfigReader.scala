/*
 * Copyright 2013-2016 Tsukasa Kitachi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package configs

import com.typesafe.config.ConfigException
import java.util.concurrent.TimeUnit
import java.{lang => jl, math => jm, time => jt, util => ju}
import scala.collection.JavaConverters._
import scala.collection.generic.CanBuildFrom
import scala.concurrent.duration.{Duration, FiniteDuration}

trait ConfigReader[A] {

  protected def read0(config: Config, path: String): Result[A]

  def read(config: Config, path: String): Result[A] =
    read0(config, path).pushPath(path)

  @deprecated("use read instead", "0.5.0")
  def get(config: Config, path: String): Result[A] =
    read(config, path)

  def extract(config: Config, key: String = "extract"): Result[A] =
    read(config.atKey(key), key)

  def extractValue(value: ConfigValue, key: String = "extract"): Result[A] =
    read(value.atKey(key), key)

  def map[B](f: A => B): ConfigReader[B] =
    read0(_, _).map(f)

  def rmap[B](f: A => Result[B]): ConfigReader[B] =
    read0(_, _).flatMap(f)

  def flatMap[B](f: A => ConfigReader[B]): ConfigReader[B] =
    (c, p) => read0(c, p).flatMap(f(_).read0(c, p))

  def orElse[B >: A](fallback: ConfigReader[B]): ConfigReader[B] =
    (c, p) => read0(c, p).orElse(fallback.read0(c, p))

  def transform[B](fail: ConfigError => ConfigReader[B], succ: A => ConfigReader[B]): ConfigReader[B] =
    (c, p) => read0(c, p).fold(fail, succ).read0(c, p)

  def as[B >: A]: ConfigReader[B] =
    this.asInstanceOf[ConfigReader[B]]

}

object ConfigReader extends ConfigReaderInstances {

  @inline
  def apply[A](implicit A: ConfigReader[A]): ConfigReader[A] = A


  def derive[A]: ConfigReader[A] =
    macro macros.ConfigReaderMacro.derive[A]

  @deprecated("Use derive[A] or auto derivation", "0.5.0")
  def deriveBean[A]: ConfigReader[A] =
    macro macros.ConfigReaderMacro.derive[A]

  def deriveBeanWith[A](newInstance: => A): ConfigReader[A] =
    macro macros.ConfigReaderMacro.deriveBeanWith[A]


  def from[A](f: (Config, String) => Result[A]): ConfigReader[A] =
    (c, p) => Result.Try(f(c, p)).flatten

  def fromConfig[A](f: Config => Result[A]): ConfigReader[A] =
    ConfigReader[Config].rmap(f)

  def fromTry[A](f: (Config, String) => A): ConfigReader[A] =
    (c, p) => Result.Try(f(c, p))

  def fromConfigTry[A](f: Config => A): ConfigReader[A] =
    fromTry((c, p) => f(c.getConfig(p)))

  def successful[A](a: A): ConfigReader[A] =
    (_, _) => Result.successful(a)

  def failure[A](msg: String): ConfigReader[A] =
    (_, _) => Result.failure(ConfigError(msg))

  def get[A](path: String)(implicit A: ConfigReader[A]): ConfigReader[A] =
    fromConfig(A.read(_, path))

}


sealed abstract class ConfigReaderInstances0 {

  implicit def autoDeriveConfigReader[A]: ConfigReader[A] =
    macro macros.ConfigReaderMacro.derive[A]

}

sealed abstract class ConfigReaderInstances extends ConfigReaderInstances0 {

  implicit def javaListConfigReader[A](implicit A: ConfigReader[A]): ConfigReader[ju.List[A]] =
    ConfigReader[ConfigList].rmap { xs =>
      Result.sequence(
        xs.asScala.zipWithIndex.map {
          case (x, i) => A.extractValue(x, i.toString)
        })
        .map(_.asJava)
    }

  implicit def javaIterableConfigReader[A](implicit C: ConfigReader[ju.List[A]]): ConfigReader[jl.Iterable[A]] =
    C.as[jl.Iterable[A]]

  implicit def javaCollectionConfigReader[A](implicit C: ConfigReader[ju.List[A]]): ConfigReader[ju.Collection[A]] =
    C.as[ju.Collection[A]]

  implicit def javaSetConfigReader[A](implicit C: ConfigReader[ju.List[A]]): ConfigReader[ju.Set[A]] =
    C.map(_.asScala.toSet.asJava)

  implicit def javaMapConfigReader[A, B](implicit A: StringConverter[A], B: ConfigReader[B]): ConfigReader[ju.Map[A, B]] =
    ConfigReader.fromConfig { c =>
      Result.sequence(
        c.root().asScala.keysIterator.map { k =>
          val p = ConfigUtil.joinPath(k)
          Result.tuple2(A.from(k).pushPath(p), B.read(c, p))
        })
        .map(_.toMap.asJava)
    }


  implicit def cbfJListConfigReader[F[_], A](implicit C: ConfigReader[ju.List[A]], cbf: CanBuildFrom[Nothing, A, F[A]]): ConfigReader[F[A]] =
    C.map(_.asScala.to[F])

  implicit def cbfJMapConfigReader[M[_, _], A, B](implicit C: ConfigReader[ju.Map[A, B]], cbf: CanBuildFrom[Nothing, (A, B), M[A, B]]): ConfigReader[M[A, B]] =
    C.map(_.asScala.to[({type F[_] = M[A, B]})#F])


  implicit def optionConfigReader[A](implicit A: ConfigReader[A]): ConfigReader[Option[A]] =
    ConfigReader.from { (c, p) =>
      if (c.hasPathOrNull(p))
        A.read(c, p).map(Some(_)).handle {
          case ConfigError(ConfigError.NullValue(_, `p` :: Nil), es) if es.isEmpty => None
        }.popPath
      else
        Result.successful(None)
    }

  implicit def javaOptionalConfigReader[A: ConfigReader]: ConfigReader[ju.Optional[A]] =
    optionConfigReader[A].map(_.fold(ju.Optional.empty[A]())(ju.Optional.of))

  implicit lazy val javaOptionalIntConfigReader: ConfigReader[ju.OptionalInt] =
    optionConfigReader[Int].map(_.fold(ju.OptionalInt.empty())(ju.OptionalInt.of))

  implicit lazy val javaOptionalLongConfigReader: ConfigReader[ju.OptionalLong] =
    optionConfigReader[Long].map(_.fold(ju.OptionalLong.empty())(ju.OptionalLong.of))

  implicit lazy val javaOptionalDoubleConfigReader: ConfigReader[ju.OptionalDouble] =
    optionConfigReader[Double].map(_.fold(ju.OptionalDouble.empty())(ju.OptionalDouble.of))


  implicit def resultConfigReader[A](implicit A: ConfigReader[A]): ConfigReader[Result[A]] =
    (c, p) => Result.successful(A.read(c, p))


  implicit def readStringConfigReader[A](implicit A: StringConverter[A]): ConfigReader[A] =
    ConfigReader[String].rmap(A.from)


  private[this] def bigDecimal(expected: String): ConfigReader[BigDecimal] =
    ConfigReader.fromTry { (c, p) =>
      val s = c.getString(p)
      try BigDecimal(s) catch {
        case e: NumberFormatException =>
          throw new ConfigException.WrongType(c.origin(), p, expected, s"STRING value '$s'", e)
      }
    }

  private[this] def bigInt(expected: String): ConfigReader[BigInt] =
    bigDecimal(expected).map(_.toBigInt)

  private[this] def integral[A](expected: String, valid: BigInt => Boolean, value: BigInt => A): ConfigReader[A] =
    bigInt(expected).flatMap { n =>
      ConfigReader.fromTry { (c, p) =>
        if (valid(n)) value(n)
        else throw new ConfigException.WrongType(c.origin(), p, expected, s"out-of-range value $n")
      }
    }

  implicit lazy val byteConfigReader: ConfigReader[Byte] =
    integral("byte (8-bit integer)", _.isValidByte, _.toByte)

  implicit lazy val javaByteConfigReader: ConfigReader[jl.Byte] =
    byteConfigReader.asInstanceOf[ConfigReader[jl.Byte]]

  implicit lazy val shortConfigReader: ConfigReader[Short] =
    integral("short (16-bit integer)", _.isValidShort, _.toShort)

  implicit lazy val javaShortConfigReader: ConfigReader[jl.Short] =
    shortConfigReader.asInstanceOf[ConfigReader[jl.Short]]

  implicit lazy val intConfigReader: ConfigReader[Int] =
    integral("int (32-bit integer)", _.isValidInt, _.toInt)

  implicit lazy val javaIntegerConfigReader: ConfigReader[jl.Integer] =
    intConfigReader.asInstanceOf[ConfigReader[jl.Integer]]

  implicit lazy val longConfigReader: ConfigReader[Long] =
    integral("long (64-bit integer)", _.isValidLong, _.toLong)

  implicit lazy val javaLongConfigReader: ConfigReader[jl.Long] =
    longConfigReader.asInstanceOf[ConfigReader[jl.Long]]


  implicit lazy val floatConfigReader: ConfigReader[Float] =
    ConfigReader.fromTry(_.getDouble(_).toFloat)

  implicit lazy val javaFloatConfigReader: ConfigReader[jl.Float] =
    floatConfigReader.asInstanceOf[ConfigReader[jl.Float]]


  implicit lazy val doubleConfigReader: ConfigReader[Double] =
    ConfigReader.fromTry(_.getDouble(_))

  implicit lazy val javaDoubleConfigReader: ConfigReader[jl.Double] =
    doubleConfigReader.asInstanceOf[ConfigReader[jl.Double]]


  implicit lazy val bigIntConfigReader: ConfigReader[BigInt] =
    bigInt("integer")

  implicit lazy val bigIntegerConfigReader: ConfigReader[jm.BigInteger] =
    bigIntConfigReader.map(_.bigInteger)


  implicit lazy val bigDecimalConfigReader: ConfigReader[BigDecimal] =
    bigDecimal("decimal")

  implicit lazy val javaBigDecimalConfigReader: ConfigReader[jm.BigDecimal] =
    bigDecimalConfigReader.map(_.bigDecimal)


  implicit lazy val booleanConfigReader: ConfigReader[Boolean] =
    ConfigReader.fromTry(_.getBoolean(_))

  implicit lazy val javaBooleanConfigReader: ConfigReader[jl.Boolean] =
    booleanConfigReader.asInstanceOf[ConfigReader[jl.Boolean]]


  implicit lazy val charConfigReader: ConfigReader[Char] =
    ConfigReader.fromTry { (c, p) =>
      val s = c.getString(p)
      if (s.length == 1) s(0)
      else throw new ConfigException.WrongType(c.origin(), p, "single BMP char", s"STRING value '$s'")
    }

  implicit lazy val charJListConfigReader: ConfigReader[ju.List[Char]] =
    ConfigReader.fromTry((c, p) => ju.Arrays.asList(c.getString(p).toCharArray: _*))

  implicit lazy val javaCharConfigReader: ConfigReader[jl.Character] =
    charConfigReader.asInstanceOf[ConfigReader[jl.Character]]

  implicit lazy val javaCharListConfigReader: ConfigReader[ju.List[jl.Character]] =
    charJListConfigReader.asInstanceOf[ConfigReader[ju.List[jl.Character]]]


  implicit lazy val stringConfigReader: ConfigReader[String] =
    ConfigReader.fromTry(_.getString(_))


  implicit lazy val javaDurationConfigReader: ConfigReader[jt.Duration] =
    ConfigReader.fromTry(_.getDuration(_))

  implicit lazy val finiteDurationConfigReader: ConfigReader[FiniteDuration] =
    ConfigReader.fromTry(_.getDuration(_, TimeUnit.NANOSECONDS)).map(Duration.fromNanos)

  implicit lazy val durationConfigReader: ConfigReader[Duration] =
    finiteDurationConfigReader.orElse(ConfigReader.fromTry { (c, p) =>
      c.getString(p) match {
        case "Infinity" | "+Infinity" => Duration.Inf
        case "-Infinity" => Duration.MinusInf
        case "Undefined" | "NaN" | "+NaN" | "-NaN" => Duration.Undefined
        case s => throw new ConfigException.BadValue(c.origin(), p, s"Could not parse duration '$s'")
      }
    })


  implicit lazy val configConfigReader: ConfigReader[Config] =
    new ConfigReader[Config] {
      protected def read0(config: Config, path: String): Result[Config] =
        Result.Try(config.getConfig(path))

      override def extract(config: Config, key: String): Result[Config] =
        Result.successful(config)

      override def extractValue(value: ConfigValue, key: String): Result[Config] =
        value match {
          case co: ConfigObject => Result.successful(co.toConfig)
          case _ => super.extractValue(value, key)
        }
    }


  implicit lazy val configValueConfigReader: ConfigReader[ConfigValue] =
    new ConfigReader[ConfigValue] {
      protected def read0(config: Config, path: String): Result[ConfigValue] =
        Result.Try(config.getValue(path))

      override def extract(config: Config, key: String): Result[ConfigValue] =
        Result.successful(config.root())

      override def extractValue(value: ConfigValue, key: String): Result[ConfigValue] =
        Result.successful(value)
    }

  implicit lazy val configListConfigReader: ConfigReader[ConfigList] =
    ConfigReader.fromTry(_.getList(_))

  implicit lazy val configValueJListConfigReader: ConfigReader[ju.List[ConfigValue]] =
    configListConfigReader.as[ju.List[ConfigValue]]


  implicit lazy val configObjectConfigReader: ConfigReader[ConfigObject] =
    ConfigReader.fromTry(_.getObject(_))

  implicit lazy val configValueJMapConfigReader: ConfigReader[ju.Map[String, ConfigValue]] =
    configObjectConfigReader.as[ju.Map[String, ConfigValue]]


  implicit lazy val configMemorySizeConfigReader: ConfigReader[ConfigMemorySize] =
    ConfigReader.fromTry(_.getMemorySize(_))


  implicit lazy val javaPropertiesConfigReader: ConfigReader[ju.Properties] =
    ConfigReader[ju.Map[String, String]].map { m =>
      val p = new ju.Properties()
      p.putAll(m)
      p
    }

  implicit def withOriginConfigReader[A](implicit A: ConfigReader[A]): ConfigReader[(A, ConfigOrigin)] =
    (c, p) => A.read(c, p).flatMap { a =>
      try
        Result.successful((a, c.getValue(p).origin()))
      catch {
        case e: ConfigException.Null => Result.successful((a, e.origin()))
        case _: ConfigException.Missing => Result.failure(ConfigError(s"no origin for '$p'"))
      }
    }

}