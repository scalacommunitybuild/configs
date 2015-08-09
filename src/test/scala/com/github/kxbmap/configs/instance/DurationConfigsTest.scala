/*
 * Copyright 2013-2015 Tsukasa Kitachi
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

package com.github.kxbmap.configs.instance

import com.github.kxbmap.configs.ConfigProp
import com.github.kxbmap.configs.util._
import java.{util => ju}
import scala.concurrent.duration._
import scalaprops.{Gen, Scalaprops}

object DurationConfigsTest extends Scalaprops with ConfigProp {

  val finiteDuration = check[FiniteDuration]

  val finiteDurationJList = {
    implicit val h = hideConfigs[FiniteDuration]
    check[ju.List[FiniteDuration]]
  }

  val duration = check[Duration]

  val durationJList = {
    implicit val h = hideConfigs[Duration]
    check[ju.List[Duration]]
  }


  implicit lazy val finiteDurationGen: Gen[FiniteDuration] =
    Gen.nonNegativeLong.map(Duration.fromNanos)

  implicit lazy val finiteDurationValue: ConfigVal[FiniteDuration] =
    ConfigVal[String].contramap(d => s"${d.toNanos}ns")


  implicit lazy val durationGen: Gen[Duration] =
    finiteDurationGen.asInstanceOf[Gen[Duration]]

  implicit lazy val durationValue: ConfigVal[Duration] =
    finiteDurationValue.asInstanceOf[ConfigVal[Duration]]

}
