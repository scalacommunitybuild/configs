/*
 * Copyright 2013 Tsukasa Kitachi
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

package com.github.kxbmap.configs

import com.typesafe.config.ConfigFactory
import org.scalacheck.Arbitrary
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.prop.PropertyChecks


class BytesSpec extends FlatSpec with ShouldMatchers with PropertyChecks {

  behavior of "Bytes"

  it should "get from Config" in {
    val config = ConfigFactory.load("test-data")
    config.get[Bytes]("bytes.value") should be (Bytes(1024L))
    config.get[List[Bytes]]("bytes.values") should be (List(Bytes(100L), Bytes(1000000L)))
  }

  implicit val BytesArb = Arbitrary {
    Arbitrary.arbitrary[Long].map(Bytes.apply)
  }

  it should "be Ordering" in {
    forAll { (bs: List[Bytes]) =>
      bs.sorted.map(_.value) should be (bs.map(_.value).sorted)
    }
  }

  it should "be Ordered" in {
    forAll { (l: Bytes, r: Bytes) =>
      (l compare r) should be (l.value compare r.value)
    }
  }

  it should "be available the addition operator" in {
    forAll { (l: Bytes, r: Bytes) =>
      (l + r) should be (Bytes(l.value + r.value))
    }
  }

  it should "be available the subtraction operator" in {
    forAll { (l: Bytes, r: Bytes) =>
      (l - r) should be (Bytes(l.value - r.value))
    }
  }

  it should "be available the multiplication operator" in {
    forAll { (l: Bytes, r: Double) =>
      (l * r) should be (Bytes((l.value * r).toLong))
      (r * l) should be (l * r)
    }
  }

  it should "be available the division operator (Double)" in {
    forAll { (l: Bytes, r: Double) =>
      (l / r) should be (Bytes((l.value / r).toLong))
    }
  }

  it should "be available the division operator (Bytes)" in {
    forAll { (l: Bytes, r: Bytes) =>
      (l, r) match {
        case (Bytes(0), Bytes(0)) =>
          (l / r).isNaN should be (true)

        case _ =>
          (l / r) should be (l.value.toDouble / r.value.toDouble)
      }
    }
  }

  it should "be available the unary negation operator" in {
    forAll { (b: Bytes) =>
      -b should be (Bytes(-b.value))
    }
  }

}