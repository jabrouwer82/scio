/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio

import org.scalatest._
import scala.io.AnsiColor._

class VersionUtilTest extends FlatSpec with Matchers {
  private def verifySnapshotVersion(oldVer: String, newVerOpt: Option[String]) =
    VersionUtil.checkVersion(oldVer, newVerOpt, ignore = false) shouldBe Seq(
      s"Using a SNAPSHOT version of Scio: $oldVer"
    )

  "checkVersion" should "warn about snapshot version" in {
    verifySnapshotVersion("0.1.0-SNAPSHOT", Some("0.1.0-alpha"))
    verifySnapshotVersion("0.1.0-SNAPSHOT", Some("0.1.0-beta"))
    verifySnapshotVersion("0.1.0-SNAPSHOT", Some("0.1.0-RC"))
  }

  it should "warn about release version" in {
    VersionUtil.checkVersion("0.1.0-SNAPSHOT", Some("0.1.0"), ignore = false) shouldBe Seq(
      "Using a SNAPSHOT version of Scio: 0.1.0-SNAPSHOT",
      s"""
       | $YELLOW>$BOLD A newer version of Scio is available: 0.1.0-SNAPSHOT -> 0.1.0$RESET
       | $YELLOW>$RESET Use `-Dscio.ignoreVersionWarning=true` to disable this check.$RESET
       |""".stripMargin
    )
  }

  private def verifyNewVersion(oldVer: String, newVer: String) =
    VersionUtil.checkVersion(oldVer, Some(newVer), ignore = false) shouldBe Seq(
      s"""
        | $YELLOW>$BOLD A newer version of Scio is available: $oldVer -> $newVer$RESET
        | $YELLOW>$RESET Use `-Dscio.ignoreVersionWarning=true` to disable this check.$RESET
        |""".stripMargin
    )

  it should "warn about newer version" in {
    val versions = Array(
      "0.1.0",
      "0.1.1-alpha1",
      "0.1.1-alpha2",
      "0.1.1-beta1",
      "0.1.1-beta2",
      "0.1.1-RC1",
      "0.1.1-RC2",
      "0.1.1"
    )
    for (i <- versions.indices) {
      VersionUtil.checkVersion(versions(i), Some(versions(i)), ignore = false) shouldBe Nil
      for (j <- (i + 1) until versions.length) {
        verifyNewVersion(versions(i), versions(j))
      }
    }
  }
}
