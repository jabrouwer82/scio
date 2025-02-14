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

package com.spotify.scio.repl

import org.apache.beam.sdk.options.PipelineOptions
import com.spotify.scio.{ScioContext, ScioExecutionContext}

class ReplScioContext(options: PipelineOptions, artifacts: List[String])
    extends ScioContext(options, artifacts) {
  /** Enhanced version that dumps REPL session jar. */
  override def run(): ScioExecutionContext = {
    createJar()
    super.run()
  }

  /** Ensure an operation is called before the pipeline is closed. */
  override private[scio] def requireNotClosed[T](body: => T): T = {
    require(
      !this.isClosed,
      "ScioContext already closed, use :newScio <[context-name] | sc> to create new context"
    )
    super.requireNotClosed(body)
  }

  private def createJar(): Unit = {
    import scala.language.reflectiveCalls
    this.getClass.getClassLoader
      .asInstanceOf[{ def createReplCodeJar: String }]
      .createReplCodeJar
    ()
  }
}
