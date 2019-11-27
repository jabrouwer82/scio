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

package com.spotify.scio.util

import java.lang.{Iterable => JIterable}
import java.util.{Iterator => JIterator}

import com.spotify.scio.coders._
import com.spotify.scio.options.ScioOptions
import com.spotify.scio.options.ScioOptions.CheckEnabled
import com.spotify.scio.values.SCollection
import org.apache.beam.sdk.transforms.DoFn.ProcessElement
import org.apache.beam.sdk.transforms.join.{CoGbkResult, CoGroupByKey, KeyedPCollectionTuple}
import org.apache.beam.sdk.transforms.{DoFn, ParDo}
import org.apache.beam.sdk.values.{KV, TupleTag}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

private[scio] object ArtisanJoin {
  private val log = LoggerFactory.getLogger(this.getClass)

  private def cogroupImpl[KEY: Coder, A: Coder, B: Coder, A1: Coder, B1: Coder](
    name: String,
    a: SCollection[(KEY, A)],
    b: SCollection[(KEY, B)]
  )(
    fn: (
      KEY,
      JIterable[A],
      JIterable[B],
      DoFn[KV[KEY, CoGbkResult], (KEY, (A1, B1))]#ProcessContext
    ) => Unit
  ): SCollection[(KEY, (A1, B1))] = {
    if (a.state.postCoGroup || b.state.postCoGroup) {
      val msg =
        """
          |Chained cogroup/join detected. Use MultiJoin instead to reduce shuffle.
          |
          |For example:
          |a.cogroup(B).cogroup(c) => MultiJoin.cogroup(a, b, c)
          |a.join(b).join(c) => MultiJoin(a, b, c)
          |a.leftOuterJoin(b).leftOuterJoin(c) => MultiJoin.left(a, b, c)
        """.stripMargin
      a.context.optionsAs[ScioOptions].getChainedCogroups match {
        case CheckEnabled.OFF =>
        case CheckEnabled.WARNING =>
          log.warn(msg)
        case CheckEnabled.ERROR =>
          throw new RuntimeException(msg)
      }
    }
    val (tagA, tagB) = (new TupleTag[A](), new TupleTag[B]())
    val keyed = KeyedPCollectionTuple
      .of(tagA, a.toKV.internal)
      .and(tagB, b.toKV.internal)
      .apply(s"CoGroupByKey@$name", CoGroupByKey.create())

    type DF = DoFn[KV[KEY, CoGbkResult], (KEY, (A1, B1))]
    a.context
      .wrap(keyed)
      .withName(name)
      .applyTransform(ParDo.of(new DF {
        @ProcessElement
        private[util] def processElement(c: DF#ProcessContext): Unit = {
          val kv = c.element()
          val key = kv.getKey
          val result = kv.getValue
          val as = result.getAll(tagA)
          val bs = result.getAll(tagB)
          fn(key, as, bs, c)
        }
      }))
      .withState(_.copy(postCoGroup = true))
  }

  private def joinImpl[KEY: Coder, A: Coder, B: Coder, A1: Coder, B1: Coder](
    name: String,
    a: SCollection[(KEY, A)],
    b: SCollection[(KEY, B)]
  )(
    leftFn: JIterator[A] => JIterator[A1],
    rightFn: JIterator[B] => JIterator[B1]
  ): SCollection[(KEY, (A1, B1))] = {
    cogroupImpl[KEY, A, B, A1, B1](name, a, b) {
      case (key, as, bs, c) =>
        val bi = rightFn(bs.iterator())
        while (bi.hasNext) {
          val b = bi.next()
          val ai = leftFn(as.iterator())
          while (ai.hasNext) {
            val a = ai.next()
            c.output((key, (a, b)))
          }
        }
    }.withState(_.copy(postCoGroup = true))
  }

  def cogroup[KEY: Coder, A: Coder, B: Coder](
    name: String,
    a: SCollection[(KEY, A)],
    b: SCollection[(KEY, B)]
  ): SCollection[(KEY, (Iterable[A], Iterable[B]))] =
    cogroupImpl[KEY, A, B, Iterable[A], Iterable[B]](name, a, b) {
      case (key, a, b, c) =>
        c.output((key, (a.asScala, b.asScala)))
    }

  def apply[KEY: Coder, A: Coder, B: Coder](
    name: String,
    a: SCollection[(KEY, A)],
    b: SCollection[(KEY, B)]
  ): SCollection[(KEY, (A, B))] =
    joinImpl(name, a, b)(identity, identity)

  def left[KEY: Coder, A: Coder, B: Coder](
    name: String,
    a: SCollection[(KEY, A)],
    b: SCollection[(KEY, B)]
  ): SCollection[(KEY, (A, Option[B]))] =
    joinImpl(name, a, b)(identity, toOptions)

  def right[KEY: Coder, A: Coder, B: Coder](
    name: String,
    a: SCollection[(KEY, A)],
    b: SCollection[(KEY, B)]
  ): SCollection[(KEY, (Option[A], B))] =
    joinImpl(name, a, b)(toOptions, identity)

  def outer[KEY: Coder, A: Coder, B: Coder](
    name: String,
    a: SCollection[(KEY, A)],
    b: SCollection[(KEY, B)]
  ): SCollection[(KEY, (Option[A], Option[B]))] =
    joinImpl(name, a, b)(toOptions, toOptions)

  private val emptyList = java.util.Collections.singletonList(Option.empty)

  private def toOptions[A](xs: JIterator[A]): JIterator[Option[A]] =
    if (xs.hasNext) {
      xs.asScala.map(Option(_)).asJava
    } else {
      emptyList.iterator().asInstanceOf[JIterator[Option[A]]]
    }
}
