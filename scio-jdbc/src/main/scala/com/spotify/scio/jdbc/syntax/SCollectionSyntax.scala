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

package com.spotify.scio.jdbc.syntax

import com.spotify.scio.values.SCollection
import com.spotify.scio.coders.Coder

import scala.reflect.ClassTag
import com.spotify.scio.ScioContext
import com.spotify.scio.jdbc.{JdbcReadOptions, JdbcSelect}

import scala.language.implicitConversions

/** Enhanced version of [[ScioContext]] with JDBC methods. */
final class JdbcScioContextOps(private val self: ScioContext) extends AnyVal {
  /** Get an SCollection for a JDBC query. */
  def jdbcSelect[T: ClassTag: Coder](readOptions: JdbcReadOptions[T]): SCollection[T] =
    self.read(JdbcSelect(readOptions))
}

trait SCollectionSyntax {
  implicit def jdbcScioContextOps(sc: ScioContext): JdbcScioContextOps = new JdbcScioContextOps(sc)
}
