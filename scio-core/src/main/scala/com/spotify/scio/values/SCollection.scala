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

package com.spotify.scio.values

import java.io.PrintStream
import java.lang.{Boolean => JBoolean, Double => JDouble, Iterable => JIterable}
import java.util.concurrent.ThreadLocalRandom

import com.google.datastore.v1.Entity
import com.spotify.scio.ScioContext
import com.spotify.scio.annotations.experimental
import com.spotify.scio.coders.{AvroBytesUtil, Coder, CoderMaterializer}
import com.spotify.scio.io._
import com.spotify.scio.schemas.{Schema, SchemaMaterializer, To}
import com.spotify.scio.testing.TestDataManager
import com.spotify.scio.util._
import com.spotify.scio.util.random.{BernoulliSampler, PoissonSampler}
import com.twitter.algebird.{Aggregator, Monoid, Semigroup}
import org.apache.avro.file.CodecFactory
import org.apache.beam.sdk.coders.{Coder => BCoder}
import org.apache.beam.sdk.io.{Compression, FileBasedSink}
import org.apache.beam.sdk.transforms.DoFn.ProcessElement
import org.apache.beam.sdk.transforms._
import org.apache.beam.sdk.transforms.windowing._
import org.apache.beam.sdk.util.SerializableUtils
import org.apache.beam.sdk.values.WindowingStrategy.AccumulationMode
import org.apache.beam.sdk.values._
import org.apache.beam.sdk.{io => beam}
import org.joda.time.{Duration, Instant}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.TreeMap
import scala.reflect.ClassTag
import scala.util.Try

/** Convenience functions for creating SCollections. */
object SCollection {
  private[values] val logger = LoggerFactory.getLogger(this.getClass)

  /**
   * Create a union of multiple [[SCollection]] instances.
   * Will throw an exception if the provided iterable is empty.
   * For a version that accepts empty iterables, see [[ScioContext#unionAll]].
   */
  def unionAll[T: Coder](scs: Iterable[SCollection[T]]): SCollection[T] =
    scs.head.context.unionAll(scs)

  import scala.language.implicitConversions

  /** Implicit conversion from SCollection to DoubleSCollectionFunctions. */
  implicit def makeDoubleSCollectionFunctions(s: SCollection[Double]): DoubleSCollectionFunctions =
    new DoubleSCollectionFunctions(s)

  /** Implicit conversion from SCollection to DoubleSCollectionFunctions. */
  implicit def makeDoubleSCollectionFunctions[T](
    s: SCollection[T]
  )(implicit num: Numeric[T]): DoubleSCollectionFunctions =
    new DoubleSCollectionFunctions(s.map(num.toDouble))

  /** Implicit conversion from SCollection to PairSCollectionFunctions. */
  implicit def makePairSCollectionFunctions[K, V](
    s: SCollection[(K, V)]
  ): PairSCollectionFunctions[K, V] =
    new PairSCollectionFunctions(s)

  implicit def makePairHashSCollectionFunctions[K, V](
    s: SCollection[(K, V)]
  ): PairHashSCollectionFunctions[K, V] =
    new PairHashSCollectionFunctions(s)

  implicit def makePairSkewedSCollectionFunctions[K, V](
    s: SCollection[(K, V)]
  ): PairSkewedSCollectionFunctions[K, V] =
    new PairSkewedSCollectionFunctions(s)

  private[scio] final case class State(postCoGroup: Boolean = false)
}

/**
 * A Scala wrapper for [[org.apache.beam.sdk.values.PCollection PCollection]]. Represents an
 * immutable, partitioned collection of elements that can be operated on in parallel. This class
 * contains the basic operations available on all SCollections, such as `map`, `filter`, and
 * `sum`. In addition, [[PairSCollectionFunctions]] contains operations available only on
 * SCollections of key-value pairs, such as `groupByKey` and `join`;
 * [[DoubleSCollectionFunctions]] contains operations available only on SCollections of `Double`s.
 *
 * @groupname collection Collection Operations
 * @groupname hash Hash Operations
 * @groupname output Output Sinks
 * @groupname side Side Input and Output Operations
 * @groupname transform Transformations
 * @groupname window Windowing Operations
 */
sealed trait SCollection[T] extends PCollectionWrapper[T] {
  self =>

  import TupleFunctions._

  // =======================================================================
  // States
  // =======================================================================

  private var _state: SCollection.State = SCollection.State()

  private[scio] def withState(f: SCollection.State => SCollection.State): SCollection[T] = {
    _state = f(_state)
    this
  }

  private[scio] def state: SCollection.State = _state

  // =======================================================================
  // Delegations for internal PCollection
  // =======================================================================

  /** A friendly name for this SCollection. */
  def name: String = internal.getName

  /** Assign a Coder to this SCollection. */
  def setCoder(coder: org.apache.beam.sdk.coders.Coder[T]): SCollection[T] =
    context.wrap(internal.setCoder(coder))

  def setSchema(schema: Schema[T]): SCollection[T] = {
    if (!internal.hasSchema) {
      val (s, to, from) = SchemaMaterializer.materialize(this.context, schema)
      context.wrap(internal.setSchema(s, to, from))
    } else this
  }

  private def ensureSerializable[A](coder: BCoder[A]): Either[Throwable, BCoder[A]] =
    coder match {
      case c if !context.isTest =>
        Right(c)
      // https://issues.apache.org/jira/browse/BEAM-5645
      case c if c.getClass.getPackage.getName.startsWith("org.apache.beam") =>
        Right(c)
      case _ =>
        ScioUtil.toEither(Try(SerializableUtils.ensureSerializable(coder)))
    }

  /**
   * Apply a [[org.apache.beam.sdk.transforms.PTransform PTransform]] and wrap the output in an
   * [[SCollection]].
   */
  def applyTransform[U: Coder](
    transform: PTransform[_ >: PCollection[T], PCollection[U]]
  ): SCollection[U] = {
    val coder = CoderMaterializer.beam(context, Coder[U])
    ensureSerializable(coder).fold(e => throw e, this.pApply(transform).setCoder)
  }

  /**
   * Apply a [[org.apache.beam.sdk.transforms.PTransform PTransform]] and wrap the output in an
   * [[SCollection]]. This is a special case of [[applyTransform]] for transforms with [[KV]]
   * output.
   */
  def applyKvTransform[K, V](
    transform: PTransform[_ >: PCollection[T], PCollection[KV[K, V]]]
  )(implicit koder: Coder[K], voder: Coder[V]): SCollection[KV[K, V]] = {
    val bcoder = CoderMaterializer.kvCoder[K, V](context)
    ensureSerializable(bcoder).fold(e => throw e, this.pApply(transform).setCoder)
  }

  /** Apply a transform. */
  @experimental
  def transform[U](f: SCollection[T] => SCollection[U]): SCollection[U] = transform(this.tfName)(f)

  @experimental
  def transform[U](name: String)(f: SCollection[T] => SCollection[U]): SCollection[U] =
    context.wrap {
      internal.apply(name, new PTransform[PCollection[T], PCollection[U]]() {
        override def expand(input: PCollection[T]): PCollection[U] =
          f(context.wrap(input)).internal
      })
    }

  /**
   * Go from an SCollection of type [[T]] to an SCollection of [[U]]
   * given the Schemas of both types [[T]] and [[U]].
   *
   * There are two constructors for [[To]]:
   *
   * Type safe (Schema compatibility is verified during compilation)
   * {{{
   *   SCollection[T]#to(To.safe[T, U])
   * }}}
   *
   * Unsafe conversion from [[T]] to [[U]]. Schema compatibility is not checked
   * during compile time.
   * {{{
   *   SCollection[T]#to[U](To.unsafe)
   * }}}
   */
  def to[U](to: To[T, U]): SCollection[U] = transform(to)

  // =======================================================================
  // Collection operations
  // =======================================================================

  /**
   * Convert this SCollection to an [[SCollectionWithFanout]] that uses an intermediate node to
   * combine parts of the data to reduce load on the final global combine step.
   * @param fanout the number of intermediate keys that will be used
   */
  def withFanout(fanout: Int)(implicit coder: Coder[T]): SCollectionWithFanout[T] =
    new SCollectionWithFanout[T](internal, context, fanout)

  /**
   * Return the union of this SCollection and another one. Any identical elements will appear
   * multiple times (use [[distinct]] to eliminate them).
   * @group collection
   */
  def ++(that: SCollection[T]): SCollection[T] = this.union(that)

  /**
   * Return the union of this SCollection and another one. Any identical elements will appear
   * multiple times (use [[distinct]] to eliminate them).
   * @group collection
   */
  def union(that: SCollection[T]): SCollection[T] = {
    val o = PCollectionList
      .of(internal)
      .and(that.internal)
      .apply(this.tfName, Flatten.pCollections())
    context.wrap(o)
  }

  /**
   * Return the intersection of this SCollection and another one. The output will not contain any
   * duplicate elements, even if the input SCollections did.
   *
   * Note that this method performs a shuffle internally.
   * @group collection
   */
  def intersection(that: SCollection[T])(implicit coder: Coder[T]): SCollection[T] =
    this.transform {
      _.map((_, 1)).cogroup(that.map((_, 1))).flatMap { t =>
        if (t._2._1.nonEmpty && t._2._2.nonEmpty) Seq(t._1) else Seq.empty
      }
    }

  /**
   * Partition this SCollection with the provided function.
   *
   * @param numPartitions number of output partitions
   * @param f function that assigns an output partition to each element, should be in the range
   * `[0, numPartitions - 1]`
   * @return partitioned SCollections in a `Seq`
   * @group collection
   */
  def partition(numPartitions: Int, f: T => Int): Seq[SCollection[T]] = {
    require(numPartitions > 0, "Number of partitions should be positive")
    if (numPartitions == 1) {
      Seq(this)
    } else {
      this
        .applyInternal(Partition.of[T](numPartitions, Functions.partitionFn[T](numPartitions, f)))
        .getAll
        .asScala
        .map(p => context.wrap(p))
    }
  }

  /**
   * Partition this SCollection into a pair of SCollections according to a predicate.
   *
   * @param p predicate on which to partition
   * @return a pair of SCollections: the first SCollection consists of all elements that satisfy
   * the predicate p and the second consists of all element that do not.
   * @group collection
   */
  def partition(p: T => Boolean): (SCollection[T], SCollection[T]) = {
    val Seq(left, right) = partition(2, t => if (p(t)) 0 else 1)
    (left, right)
  }

  /**
   * Partition this SCollection into a map from possible key values to an SCollection of
   * corresponding elements based on the provided function .
   *
   * @param partitionKeys The keys for the output partitions
   * @param f function that assigns an output partition to each element, should be in the range
   * of `partitionKeys`
   * @return partitioned SCollections in a `Map`
   * @group collection
   */
  def partitionByKey[U: Coder](partitionKeys: Set[U])(f: T => U): Map[U, SCollection[T]] = {
    val partitionKeysIndexed = partitionKeys.toIndexedSeq

    partitionKeysIndexed
      .zip(partition(partitionKeys.size, (t: T) => partitionKeysIndexed.indexOf(f(t))))
      .toMap
  }

  /**
   * Partition this SCollection using Object.hashCode() into `n` partitions
   *
   * @param numPartitions number of output partitions
   * @return partitioned SCollections in a `Seq`
   * @group collection
   */
  def hashPartition(numPartitions: Int): Seq[SCollection[T]] =
    self.partition(
      numPartitions, { t =>
        Math.floorMod(t.hashCode(), numPartitions)
      }
    )

  // =======================================================================
  // Transformations
  // =======================================================================

  /**
   * Aggregate the elements using given combine functions and a neutral "zero value". This
   * function can return a different result type, `U`, than the type of this SCollection, `T`.
   * Thus, we need one operation for merging a `T` into an `U` and one operation for merging two
   * `U`'s. Both of these functions are allowed to modify and return their first argument instead
   * of creating a new `U` to avoid memory allocation.
   * @group transform
   */
  def aggregate[U: Coder](
    zeroValue: U
  )(seqOp: (U, T) => U, combOp: (U, U) => U)(implicit coder: Coder[T]): SCollection[U] =
    this.pApply(Combine.globally(Functions.aggregateFn(context, zeroValue)(seqOp, combOp)))

  /**
   * Aggregate with [[com.twitter.algebird.Aggregator Aggregator]]. First each item `T` is mapped
   * to `A`, then we reduce with a [[com.twitter.algebird.Semigroup Semigroup]] of `A`, then
   * finally we present the results as `U`. This could be more powerful and better optimized in
   * some cases.
   * @group transform
   */
  def aggregate[A: Coder, U: Coder](
    aggregator: Aggregator[T, A, U]
  )(implicit coder: Coder[T]): SCollection[U] = this.transform { in =>
    val a = aggregator // defeat closure
    in.map(a.prepare).sum(a.semigroup, Coder[A]).map(a.present)
  }

  /**
   * Filter the elements for which the given `PartialFunction` is defined, and then map.
   * @group transform
   */
  def collect[U: Coder](pfn: PartialFunction[T, U]): SCollection[U] =
    this.transform {
      _.filter(pfn.isDefinedAt).map(pfn)
    }

  /**
   * Generic function to combine the elements using a custom set of aggregation functions. Turns
   * an `SCollection[T]` into a result of type `SCollection[C]`, for a "combined type" `C`. Note
   * that `T` and `C` can be different -- for example, one might combine an SCollection of type
   * `Int` into an SCollection of type `Seq[Int]`. Users provide three functions:
   *
   * - `createCombiner`, which turns a `T` into a `C` (e.g., creates a one-element list)
   *
   * - `mergeValue`, to merge a `T` into a `C` (e.g., adds it to the end of a list)
   *
   * - `mergeCombiners`, to combine two `C`'s into a single one.
   * @group transform
   */
  def combine[C: Coder](createCombiner: T => C)(
    mergeValue: (C, T) => C
  )(mergeCombiners: (C, C) => C)(implicit coder: Coder[T]): SCollection[C] = {
    SCollection.logger.warn(
      "combine/sum does not support default value and may fail in some streaming scenarios. " +
        "Consider aggregate/fold instead."
    )
    this.pApply(
      Combine
        .globally(Functions.combineFn(context, createCombiner, mergeValue, mergeCombiners))
        .withoutDefaults()
    )
  }

  /**
   * Count the number of elements in the SCollection.
   * @return a new SCollection with the count
   * @group transform
   */
  def count: SCollection[Long] =
    this.pApply(Count.globally[T]()).asInstanceOf[SCollection[Long]]

  /**
   * Count approximate number of distinct elements in the SCollection.
   * @param sampleSize the number of entries in the statistical sample; the higher this number, the
   * more accurate the estimate will be; should be `>= 16`
   * @group transform
   */
  def countApproxDistinct(sampleSize: Int): SCollection[Long] =
    this
      .pApply(ApproximateUnique.globally[T](sampleSize))
      .asInstanceOf[SCollection[Long]]

  /**
   * Count approximate number of distinct elements in the SCollection.
   * @param maximumEstimationError the maximum estimation error, which should be in the range
   * `[0.01, 0.5]`
   * @group transform
   */
  def countApproxDistinct(maximumEstimationError: Double = 0.02): SCollection[Long] =
    this
      .pApply(ApproximateUnique.globally[T](maximumEstimationError))
      .asInstanceOf[SCollection[Long]]

  /**
   * Count of each unique value in this SCollection as an SCollection of (value, count) pairs.
   * @group transform
   */
  def countByValue(implicit coder: Coder[(T, Long)]): SCollection[(T, Long)] =
    this.transform {
      _.pApply(Count.perElement[T]()).map(TupleFunctions.klToTuple)
    }

  /**
   * Return a new SCollection containing the distinct elements in this SCollection.
   * @group transform
   */
  def distinct: SCollection[T] = this.pApply(Distinct.create[T]())

  /**
   * Returns a new SCollection with distinct elements using given function to obtain a
   * representative value for each input element.
   *
   * @param f The function to use to get representative values.
   * @tparam U The type of representative values used to dedup.
   * @group transform
   */
  // This is simplier than Distinct.withRepresentativeValueFn, and allows us to set Coders
  def distinctBy[U](f: T => U)(implicit toder: Coder[T], uoder: Coder[U]): SCollection[T] =
    this.transform { me =>
      me.keyBy(f).combineByKey(identity) { case (c, _) => c } { case (c, _) => c }.values
    }

  /**
   * Return a new SCollection containing only the elements that satisfy a predicate.
   * @group transform
   */
  def filter(f: T => Boolean): SCollection[T] =
    this.pApply(Filter.by(Functions.processFn(f.asInstanceOf[T => JBoolean])))

  /**
   * Return a new SCollection by first applying a function to all elements of
   * this SCollection, and then flattening the results.
   * @group transform
   */
  def flatMap[U: Coder](f: T => TraversableOnce[U]): SCollection[U] =
    this.parDo(Functions.flatMapFn(f))

  /**
   * Return a new `SCollection[U]` by flattening each element of an `SCollection[Traversable[U]]`.
   * @group transform
   */
  def flatten[U](implicit ev: T => TraversableOnce[U], coder: Coder[U]): SCollection[U] =
    flatMap(ev)

  /**
   * Aggregate the elements using a given associative function and a neutral "zero value". The
   * function op(t1, t2) is allowed to modify t1 and return it as its result value to avoid object
   * allocation; however, it should not modify t2.
   * @group transform
   */
  def fold(zeroValue: T)(op: (T, T) => T)(implicit coder: Coder[T]): SCollection[T] =
    this.pApply(Combine.globally(Functions.aggregateFn(context, zeroValue)(op, op)))

  /**
   * Fold with [[com.twitter.algebird.Monoid Monoid]], which defines the associative function and
   * "zero value" for `T`. This could be more powerful and better optimized in some cases.
   * @group transform
   */
  def fold(implicit mon: Monoid[T], coder: Coder[T]): SCollection[T] =
    this.pApply(Combine.globally(Functions.reduceFn(context, mon)))

  /**
   * Return an SCollection of grouped items. Each group consists of a key and a sequence of
   * elements mapping to that key. The ordering of elements within each group is not guaranteed,
   * and may even differ each time the resulting SCollection is evaluated.
   *
   * Note: This operation may be very expensive. If you are grouping in order to perform an
   * aggregation (such as a sum or average) over each key, using
   * [[PairSCollectionFunctions.aggregateByKey[U]* PairSCollectionFunctions.aggregateByKey]] or
   * [[PairSCollectionFunctions.reduceByKey]] will provide much better performance.
   * @group transform
   */
  def groupBy[K](
    f: T => K
  )(implicit kcoder: Coder[K], vcoder: Coder[T]): SCollection[(K, Iterable[T])] =
    this.transform {
      val coder = CoderMaterializer.kvCoder[K, T](context)
      _.pApply(WithKeys.of(Functions.serializableFn(f)))
        .setCoder(coder)
        .pApply(GroupByKey.create[K, T]())
        .map(kvIterableToTuple)
    }

  /**
   * Return a new SCollection containing only the elements that also exist in the SideSet.
   *
   * @group transform
   */
  @deprecated("use SCollection[T]#hashFilter(right.asSetSingletonSideInput) instead", "0.8.0")
  def hashFilter(that: SideSet[T])(implicit coder: Coder[T]): SCollection[T] =
    self.map((_, ())).hashIntersectByKey(that.side).keys

  /**
   * Return a new SCollection containing only the elements that also exist in the `SideInput`.
   *
   * @group transform
   */
  def hashFilter(sideInput: SideInput[Set[T]])(implicit coder: Coder[T]): SCollection[T] =
    self.map((_, ())).hashIntersectByKey(sideInput).keys

  /**
   * Create tuples of the elements in this SCollection by applying `f`.
   * @group transform
   */
  // Scala lambda is simpler than transforms.WithKeys
  def keyBy[K](f: T => K)(implicit coder: Coder[(K, T)]): SCollection[(K, T)] =
    this.map(v => (f(v), v))

  /**
   * Return a new SCollection by applying a function to all elements of this SCollection.
   * @group transform
   */
  def map[U: Coder](f: T => U): SCollection[U] = this.parDo(Functions.mapFn(f))

  /**
   * Return the max of this SCollection as defined by the implicit `Ordering[T]`.
   * @return a new SCollection with the maximum element
   * @group transform
   */
  // Scala lambda is simpler and more powerful than transforms.Max
  def max(implicit ord: Ordering[T], coder: Coder[T], dummy: DummyImplicit): SCollection[T] =
    max(ord)(coder)

  def max(ord: Ordering[T])(implicit coder: Coder[T]): SCollection[T] =
    this.reduce(ord.max)

  /**
   * Return the mean of this SCollection as defined by the implicit `Numeric[T]`.
   * @return a new SCollection with the mean of elements
   * @group transform
   */
  def mean(implicit ev: Numeric[T]): SCollection[Double] = this.transform { in =>
    val e = ev // defeat closure
    in.map(e.toDouble)
      .asInstanceOf[SCollection[JDouble]]
      .pApply(Mean.globally())
      .asInstanceOf[SCollection[Double]]
  }

  /**
   * Return the min of this SCollection as defined by the implicit `Ordering[T]`.
   * @return a new SCollection with the minimum element
   * @group transform
   */
  // Scala lambda is simpler and more powerful than transforms.Min
  def min(implicit ord: Ordering[T], coder: Coder[T], dummy: DummyImplicit): SCollection[T] =
    min(ord)(coder)

  def min(ord: Ordering[T])(implicit coder: Coder[T]): SCollection[T] =
    this.reduce(ord.min)

  /**
   * Compute the SCollection's data distribution using approximate `N`-tiles.
   * @return a new SCollection whose single value is an `Iterable` of the approximate `N`-tiles of
   * the elements
   * @group transform
   */
  def quantilesApprox(
    numQuantiles: Int
  )(implicit ord: Ordering[T], coder: Coder[T], dummy: DummyImplicit): SCollection[Iterable[T]] =
    quantilesApprox(numQuantiles, ord)

  def quantilesApprox(numQuantiles: Int, ord: Ordering[T])(
    implicit coder: Coder[T]
  ): SCollection[Iterable[T]] = this.transform {
    _.pApply(ApproximateQuantiles.globally(numQuantiles, ord))
      .map(_.asInstanceOf[JIterable[T]].asScala)
  }

  /**
   * Randomly splits this SCollection with the provided weights.
   *
   * @param weights weights for splits, will be normalized if they don't sum to 1
   * @return split SCollections in an array
   * @group transform
   */
  def randomSplit(
    weights: Array[Double]
  )(implicit coder: Coder[T], ct: ClassTag[T]): Array[SCollection[T]] = {
    val sum = weights.sum
    val normalizedCumWeights = weights.map(_ / sum).scanLeft(0.0d)(_ + _)
    val m = TreeMap(normalizedCumWeights.zipWithIndex: _*) // Map[lower bound, split]

    val sides = (1 until weights.length).map(_ => SideOutput[T]())
    val (head, tail) = this
      .withSideOutputs(sides: _*)
      .flatMap { (x, c) =>
        val i = m.to(ThreadLocalRandom.current().nextDouble()).last._2
        if (i == 0) {
          Seq(x) // Main output
        } else {
          c.output(sides(i - 1), x) // Side output
          Nil
        }
      }
    (head +: sides.map(tail(_))).toArray
  }

  /**
   * Randomly splits this SCollection into two parts.
   *
   * @param weight weight for left hand side SCollection, should be in the range `(0, 1)`
   * @return split SCollections in a Tuple2
   * @group transform
   */
  def randomSplit(
    weight: Double
  )(implicit coder: Coder[T], ct: ClassTag[T]): (SCollection[T], SCollection[T]) = {
    require(weight > 0.0 && weight < 1.0)
    val splits = randomSplit(Array(weight, 1d - weight))
    (splits(0), splits(1))
  }

  /**
   * Randomly splits this SCollection into three parts.
   * Note: `0 < weightA + weightB < 1`
   *
   * @param weightA weight for first SCollection, should be in the range `(0, 1)`
   * @param weightB weight for second SCollection, should be in the range `(0, 1)`
   * @return split SCollections in a Tuple3
   * @group transform
   */
  def randomSplit(
    weightA: Double,
    weightB: Double
  )(implicit coder: Coder[T], ct: ClassTag[T]): (SCollection[T], SCollection[T], SCollection[T]) = {
    require(weightA > 0.0 && weightB > 0.0 && (weightA + weightB) < 1.0)
    val splits = randomSplit(Array(weightA, weightB, 1d - (weightA + weightB)))
    (splits(0), splits(1), splits(2))
  }

  /**
   * Reduce the elements of this SCollection using the specified commutative and associative
   * binary operator.
   * @group transform
   */
  def reduce(op: (T, T) => T)(implicit coder: Coder[T]): SCollection[T] =
    this.pApply(Combine.globally(Functions.reduceFn(context, op)).withoutDefaults())

  /**
   * Return a sampled subset of this SCollection.
   * @return a new SCollection whose single value is an `Iterable` of the samples
   * @group transform
   */
  def sample(sampleSize: Int)(implicit coder: Coder[T]): SCollection[Iterable[T]] = this.transform {
    _.pApply(Sample.fixedSizeGlobally(sampleSize)).map(_.asScala)
  }

  /**
   * Return a sampled subset of this SCollection.
   * @group transform
   */
  def sample(withReplacement: Boolean, fraction: Double)(
    implicit coder: Coder[T]
  ): SCollection[T] = {
    if (withReplacement) {
      this.parDo(new PoissonSampler[T](fraction))
    } else {
      this.parDo(new BernoulliSampler[T](fraction))
    }
  }

  /**
   * Return an SCollection with the elements from `this` that are not in `other`.
   * @group transform
   */
  def subtract(that: SCollection[T])(implicit coder: Coder[T]): SCollection[T] =
    this.transform {
      _.map((_, ())).subtractByKey(that).keys
    }

  /**
   * Reduce with [[com.twitter.algebird.Semigroup Semigroup]]. This could be more powerful and
   * better optimized than [[reduce]] in some cases.
   * @group transform
   */
  def sum(implicit sg: Semigroup[T], coder: Coder[T]): SCollection[T] = {
    SCollection.logger.warn(
      "combine/sum does not support default value and may fail in some streaming scenarios. " +
        "Consider aggregate/fold instead."
    )
    this.pApply(Combine.globally(Functions.reduceFn(context, sg)).withoutDefaults())
  }

  /**
   * Return a sampled subset of any `num` elements of the SCollection.
   * @group transform
   */
  def take(num: Long): SCollection[T] = this.pApply(Sample.any(num))

  /**
   * Return the top k (largest) elements from this SCollection as defined by the specified
   * implicit `Ordering[T]`.
   * @return a new SCollection whose single value is an `Iterable` of the top k
   * @group transform
   */
  def top(
    num: Int
  )(implicit ord: Ordering[T], coder: Coder[T], d: DummyImplicit): SCollection[Iterable[T]] =
    top(num, ord)

  def top(num: Int, ord: Ordering[T])(implicit coder: Coder[T]): SCollection[Iterable[T]] =
    this.transform {
      _.pApply(Top.of(num, ord)).map((l: JIterable[T]) => l.asScala)
    }

  // =======================================================================
  // Hash operations
  // =======================================================================

  /**
   * Return the cross product with another SCollection by replicating `that` to all workers. The
   * right side should be tiny and fit in memory.
   * @group hash
   */
  def cross[U: Coder](that: SCollection[U])(implicit tcoder: Coder[T]): SCollection[(T, U)] =
    this.transform { in =>
      val side = that.asListSideInput
      in.withSideInputs(side)
        .flatMap((t, s) => s(side).map((t, _)))
        .toSCollection
    }

  /**
   * Look up values in an `SCollection[(T, V)]` for each element `T` in this SCollection by
   * replicating `that` to all workers. The right side should be tiny and fit in memory.
   * @group hash
   */
  def hashLookup[V: Coder](
    that: SCollection[(T, V)]
  )(implicit coder: Coder[T]): SCollection[(T, Iterable[V])] = this.transform { in =>
    val side = that.asMultiMapSideInput
    in.withSideInputs(side)
      .map((t, s) => (t, s(side).getOrElse(t, Iterable())))
      .toSCollection
  }

  /**
   * Print content of an SCollection to `out()`.
   * @param out where to write the debug information. Default: stdout
   * @param prefix prefix for each logged entry. Default: empty string
   * @param enabled if debugging is enabled or not. Default: true.
   *                It can be useful to set this to sc.isTest to avoid
   *                debugging when running in production.
   * @group debug
   */
  def debug(
    out: () => PrintStream = () => Console.out,
    prefix: String = "",
    enabled: Boolean = true
  ): SCollection[T] =
    if (enabled) {
      this.filter { e =>
        out().println(prefix + e)

        // filter that never removes
        true
      }
    } else {
      this
    }

  // =======================================================================
  // Side input operations
  // =======================================================================

  /**
   * Convert this SCollection of a single value per window to a [[SideInput]], to be used with
   * [[withSideInputs]].
   * @group side
   */
  def asSingletonSideInput: SideInput[T] =
    new SingletonSideInput[T](this.applyInternal(View.asSingleton()))

  /**
   * Convert this SCollection of a single value per window to a [[SideInput]] with a default value,
   * to be used with [[withSideInputs]].
   * @group side
   */
  def asSingletonSideInput(defaultValue: T): SideInput[T] =
    new SingletonSideInput[T](this.applyInternal(View.asSingleton().withDefaultValue(defaultValue)))

  /**
   * Convert this SCollection to a [[SideInput]], mapping each window to a `Seq`, to be used with
   * [[withSideInputs]].
   *
   * The resulting `Seq` is required to fit in memory.
   * @group side
   */
  // j.u.List#asScala returns s.c.mutable.Buffer which has an O(n) .toList method
  // returning Seq[T] here to avoid copying
  def asListSideInput: SideInput[Seq[T]] =
    new ListSideInput[T](this.applyInternal(View.asList()))

  /**
   * Convert this SCollection to a [[SideInput]], mapping each window to an `Iterable`, to be used
   * with [[withSideInputs]].
   *
   * The values of the `Iterable` for a window are not required to fit in memory, but they may also
   * not be effectively cached. If it is known that every window fits in memory, and stronger
   * caching is desired, use [[asListSideInput]].
   * @group side
   */
  def asIterableSideInput: SideInput[Iterable[T]] =
    new IterableSideInput[T](this.applyInternal(View.asIterable()))

  /**
   * Convert this SCollection to a [[SideInput]], mapping each window to a `Set[T]`, to be used
   * with [[withSideInputs]].
   *
   * The resulting [[SideInput]] is a one element singleton which is a `Set` of all elements in
   * the SCollection for the given window. The complete Set must fit in memory of the worker.
   *
   * @group side
   */
  // Find the distinct elements in parallel and then convert to a Set and SingletonSideInput.
  // This is preferred over aggregating as we want to map each window to a Set.
  def asSetSingletonSideInput(implicit coder: Coder[T]): SideInput[Set[T]] =
    self
      .transform(
        _.distinct
          .groupBy(_ => ())
          .values
          .map(_.toSet)
      )
      .asSingletonSideInput(Set.empty[T])

  @deprecated("Use SCollection[T]#asSetSingletonSideInput instead", "0.8.0")
  def toSideSet(implicit coder: Coder[T]): SideSet[T] = SideSet(asSetSingletonSideInput)

  /**
   * Convert this SCollection to an [[SCollectionWithSideInput]] with one or more [[SideInput]]s,
   * similar to Spark broadcast variables. Call [[SCollectionWithSideInput.toSCollection]] when
   * done with side inputs.
   *
   * {{{
   * val s1: SCollection[Int] = // ...
   * val s2: SCollection[String] = // ...
   * val s3: SCollection[(String, Double)] = // ...
   *
   * // Prepare side inputs
   * val side1 = s1.asSingletonSideInput
   * val side2 = s2.asIterableSideInput
   * val side3 = s3.asMapSideInput
   * val side4 = s4.asMultiMapSideInput
   *
   * val p: SCollection[MyRecord] = // ...
   * p.withSideInputs(side1, side2, side3).map { (x, s) =>
   *   // Extract side inputs from context
   *   val s1: Int = s(side1)
   *   val s2: Iterable[String] = s(side2)
   *   val s3: Map[String, Double] = s(side3)
   *   val s4: Map[String, Iterable[Double]] = s(side4)
   *   // ...
   * }
   * }}}
   * @group side
   */
  def withSideInputs(sides: SideInput[_]*)(implicit coder: Coder[T]): SCollectionWithSideInput[T] =
    new SCollectionWithSideInput[T](internal, context, sides)

  // =======================================================================
  // Side output operations
  // =======================================================================

  /**
   * Convert this SCollection to an [[SCollectionWithSideOutput]] with one or more
   * [[SideOutput]]s, so that a single transform can write to multiple destinations.
   *
   * {{{
   * // Prepare side inputs
   * val side1 = SideOutput[String]()
   * val side2 = SideOutput[Int]()
   *
   * val p: SCollection[MyRecord] = // ...
   * p.withSideOutputs(side1, side2).map { (x, s) =>
   *   // Write to side outputs via context
   *   s.output(side1, "word").output(side2, 1)
   *   // ...
   * }
   * }}}
   * @group side
   */
  def withSideOutputs(sides: SideOutput[_]*): SCollectionWithSideOutput[T] =
    new SCollectionWithSideOutput[T](internal, context, sides)

  // =======================================================================
  // Windowing operations
  // =======================================================================

  /**
   * Convert this SCollection to an [[WindowedSCollection]].
   * @group window
   */
  def toWindowed(implicit coder: Coder[T]): WindowedSCollection[T] =
    new WindowedSCollection[T](internal, context)

  /**
   * Window values with the given function.
   * @group window
   */
  def withWindowFn[W <: BoundedWindow](
    fn: WindowFn[AnyRef, W],
    options: WindowOptions = WindowOptions()
  ): SCollection[T] = {
    var transform = Window.into(fn).asInstanceOf[Window[T]]
    if (options.trigger != null) {
      transform = transform.triggering(options.trigger)
    }
    if (options.accumulationMode != null) {
      if (options.accumulationMode == AccumulationMode.ACCUMULATING_FIRED_PANES) {
        transform = transform.accumulatingFiredPanes()
      } else if (options.accumulationMode == AccumulationMode.DISCARDING_FIRED_PANES) {
        transform = transform.discardingFiredPanes()
      } else {
        throw new RuntimeException(s"Unsupported accumulation mode ${options.accumulationMode}")
      }
    }
    if (options.allowedLateness != null) {
      transform = if (options.closingBehavior == null) {
        transform.withAllowedLateness(options.allowedLateness)
      } else {
        transform.withAllowedLateness(options.allowedLateness, options.closingBehavior)
      }
    }
    if (options.timestampCombiner != null) {
      transform = transform.withTimestampCombiner(options.timestampCombiner)
    }
    this.pApply(transform)
  }

  /**
   * Window values into fixed windows.
   * @group window
   */
  def withFixedWindows(
    duration: Duration,
    offset: Duration = Duration.ZERO,
    options: WindowOptions = WindowOptions()
  ): SCollection[T] =
    this.withWindowFn(FixedWindows.of(duration).withOffset(offset), options)

  /**
   * Window values into sliding windows.
   * @group window
   */
  def withSlidingWindows(
    size: Duration,
    period: Duration = null,
    offset: Duration = Duration.ZERO,
    options: WindowOptions = WindowOptions()
  ): SCollection[T] = {
    var transform = SlidingWindows.of(size).withOffset(offset)
    if (period != null) {
      transform = transform.every(period)
    }
    this.withWindowFn(transform, options)
  }

  /**
   * Window values based on sessions.
   * @group window
   */
  def withSessionWindows(
    gapDuration: Duration,
    options: WindowOptions = WindowOptions()
  ): SCollection[T] =
    this.withWindowFn(Sessions.withGapDuration(gapDuration), options)

  /**
   * Group values in to a single global window.
   * @group window
   */
  def withGlobalWindow(options: WindowOptions = WindowOptions()): SCollection[T] =
    this.withWindowFn(new GlobalWindows(), options)

  /**
   * Window values into by years.
   * @group window
   */
  def windowByYears(number: Int, options: WindowOptions = WindowOptions()): SCollection[T] =
    this.withWindowFn(CalendarWindows.years(number), options)

  /**
   * Window values into by months.
   * @group window
   */
  def windowByMonths(number: Int, options: WindowOptions = WindowOptions()): SCollection[T] =
    this.withWindowFn(CalendarWindows.months(number), options)

  /**
   * Window values into by weeks.
   * @group window
   */
  def windowByWeeks(
    number: Int,
    startDayOfWeek: Int,
    options: WindowOptions = WindowOptions()
  ): SCollection[T] =
    this.withWindowFn(CalendarWindows.weeks(number, startDayOfWeek), options)

  /**
   * Window values into by days.
   * @group window
   */
  def windowByDays(number: Int, options: WindowOptions = WindowOptions()): SCollection[T] =
    this.withWindowFn(CalendarWindows.days(number), options)

  /**
   * Convert values into pairs of (value, window).
   * @group window
   */
  def withPaneInfo(implicit coder: Coder[(T, PaneInfo)]): SCollection[(T, PaneInfo)] =
    this.parDo(new DoFn[T, (T, PaneInfo)] {
      @ProcessElement
      private[scio] def processElement(c: DoFn[T, (T, PaneInfo)]#ProcessContext): Unit =
        c.output((c.element(), c.pane()))
    })

  /**
   * Convert values into pairs of (value, timestamp).
   * @group window
   */
  def withTimestamp(implicit coder: Coder[(T, Instant)]): SCollection[(T, Instant)] =
    this.parDo(new DoFn[T, (T, Instant)] {
      @ProcessElement
      private[scio] def processElement(c: DoFn[T, (T, Instant)]#ProcessContext): Unit =
        c.output((c.element(), c.timestamp()))
    })

  /**
   * Convert values into pairs of (value, window).
   * @tparam W window type, must be
   *           [[org.apache.beam.sdk.transforms.windowing.BoundedWindow BoundedWindow]] or one of
   *           it's sub-types, e.g.
   *           [[org.apache.beam.sdk.transforms.windowing.GlobalWindow GlobalWindow]] if this
   *           SCollection is not windowed or
   *           [[org.apache.beam.sdk.transforms.windowing.IntervalWindow IntervalWindow]] if it is
   *           windowed.
   * @group window
   */
  def withWindow[W <: BoundedWindow](implicit tcoder: Coder[T]): SCollection[(T, W)] =
    this
      .parDo(new DoFn[T, (T, BoundedWindow)] {
        @ProcessElement
        private[scio] def processElement(
          c: DoFn[T, (T, BoundedWindow)]#ProcessContext,
          window: BoundedWindow
        ): Unit =
          c.output((c.element(), window))
      })
      .asInstanceOf[SCollection[(T, W)]]

  /**
   * Assign timestamps to values.
   * With a optional skew
   * @group window
   */
  def timestampBy(f: T => Instant, allowedTimestampSkew: Duration = Duration.ZERO)(
    implicit coder: Coder[T]
  ): SCollection[T] =
    this.applyTransform(
      WithTimestamps
        .of(Functions.serializableFn(f))
        .withAllowedTimestampSkew(allowedTimestampSkew)
    )

  // =======================================================================
  // Read operations
  // =======================================================================

  /**
   * Read files represented by elements of this [[SCollection]] as file patterns.
   *
   * {{{
   * sc.parallelize("a.txt").readAll(TextIO.readAll())
   * }}}
   */
  def readAll[U: Coder](
    read: PTransform[PCollection[String], PCollection[U]]
  )(implicit ev: T <:< String): SCollection[U] =
    if (context.isTest) {
      val id = context.testId.get
      this
        .asInstanceOf[SCollection[String]]
        .flatMap(s => TestDataManager.getInput(id)(ReadIO(s)).asIterable.get)
    } else {
      this.asInstanceOf[SCollection[String]].applyTransform(read)
    }

  /**
   * Read files as byte arrays represented by elements of this [[SCollection]] as file patterns.
   */
  def readAllBytes(implicit ev: T <:< String): SCollection[Array[Byte]] =
    if (context.isTest) {
      val id = context.testId.get
      this
        .asInstanceOf[SCollection[String]]
        .flatMap(s => TestDataManager.getInput(id)(ReadIO(s)).asIterable.get)
    } else {
      this
        .asInstanceOf[SCollection[String]]
        .applyTransform(new PTransform[PCollection[String], PCollection[Array[Byte]]]() {
          override def expand(input: PCollection[String]): PCollection[Array[Byte]] =
            input
              .apply(beam.FileIO.matchAll())
              .apply(beam.FileIO.readMatches())
              .apply(
                ParDo.of(Functions.mapFn((f: beam.FileIO.ReadableFile) => f.readFullyAsBytes()))
              )
        })
    }

  // =======================================================================
  // Write operations
  // =======================================================================

  /**
   * Extract data from this SCollection as a `Future`. The `Future` will be completed once the
   * pipeline completes successfully.
   * @group output
   */
  def materialize(implicit coder: Coder[T]): ClosedTap[T] =
    materialize(ScioUtil.getTempFile(context), isCheckpoint = false)

  private[scio] def materialize(path: String, isCheckpoint: Boolean)(
    implicit coder: Coder[T]
  ): ClosedTap[T] =
    if (context.isTest) {
      // Do not run assertions on materialized value but still access test context to trigger
      // the test checking if we're running inside a JobTest
      if (!isCheckpoint) TestDataManager.getOutput(context.testId.get)
      saveAsInMemoryTap
    } else {
      val elemCoder = CoderMaterializer.beam(context, coder)
      val schema = AvroBytesUtil.schema
      val avroCoder = Coder.avroGenericRecordCoder(schema)
      val write = beam.AvroIO
        .writeGenericRecords(schema)
        .to(ScioUtil.pathWithShards(path))
        .withSuffix(".obj.avro")
        .withCodec(CodecFactory.deflateCodec(6))
        .withMetadata(Map.empty[String, AnyRef].asJava)

      this
        .map(c => AvroBytesUtil.encode(elemCoder, c))(avroCoder)
        .applyInternal(write)
      ClosedTap(MaterializeTap[T](path, context))
    }

  private[scio] def textOut(
    path: String,
    suffix: String,
    numShards: Int,
    compression: Compression
  ) = {
    beam.TextIO
      .write()
      .to(ScioUtil.pathWithShards(path))
      .withSuffix(suffix)
      .withNumShards(numShards)
      .withWritableByteChannelFactory(FileBasedSink.CompressionType.fromCanonical(compression))
  }

  /**
   * Save this SCollection as a Datastore dataset. Note that elements must be of type `Entity`.
   * @group output
   */
  def saveAsDatastore(projectId: String)(implicit ev: T <:< Entity): ClosedTap[Nothing] =
    this.asInstanceOf[SCollection[Entity]].write(DatastoreIO(projectId))

  /**
   * Save this SCollection as a Pub/Sub topic.
   * @group output
   */
  def saveAsPubsub(
    topic: String,
    idAttribute: String = null,
    timestampAttribute: String = null,
    maxBatchSize: Option[Int] = None,
    maxBatchBytesSize: Option[Int] = None
  )(implicit ct: ClassTag[T], coder: Coder[T]): ClosedTap[Nothing] = {
    val io = PubsubIO[T](topic, idAttribute, timestampAttribute)
    this.write(io)(PubsubIO.WriteParam(maxBatchSize, maxBatchBytesSize))
  }

  /**
   * Save this SCollection as a Pub/Sub topic using the given map as message attributes.
   * @group output
   */
  def saveAsPubsubWithAttributes[V: ClassTag: Coder](
    topic: String,
    idAttribute: String = null,
    timestampAttribute: String = null,
    maxBatchSize: Option[Int] = None,
    maxBatchBytesSize: Option[Int] = None
  )(implicit ev: T <:< (V, Map[String, String])): ClosedTap[Nothing] = {
    val io = PubsubIO.withAttributes[V](topic, idAttribute, timestampAttribute)
    this
      .asInstanceOf[SCollection[(V, Map[String, String])]]
      .write(io)(PubsubIO.WriteParam(maxBatchSize, maxBatchBytesSize))
  }

  /**
   * Save this SCollection as a text file. Note that elements must be of type `String`.
   * @group output
   */
  def saveAsTextFile(
    path: String,
    numShards: Int = 0,
    suffix: String = ".txt",
    compression: Compression = Compression.UNCOMPRESSED
  )(implicit ct: ClassTag[T]): ClosedTap[String] = {
    val s = if (classOf[String] isAssignableFrom ct.runtimeClass) {
      this.asInstanceOf[SCollection[String]]
    } else {
      this.map(_.toString)
    }
    s.write(TextIO(path))(TextIO.WriteParam(suffix, numShards, compression))
  }

  /**
   * Save this SCollection as raw bytes. Note that elements must be of type `Array[Byte]`.
   * @group output
   */
  def saveAsBinaryFile(
    path: String,
    numShards: Int = BinaryIO.WriteParam.DefaultNumShards,
    suffix: String = BinaryIO.WriteParam.DefaultSuffix,
    compression: Compression = BinaryIO.WriteParam.DefaultCompression,
    header: Array[Byte] = BinaryIO.WriteParam.DefaultHeader,
    footer: Array[Byte] = BinaryIO.WriteParam.DefaultFooter,
    framePrefix: Array[Byte] => Array[Byte] = BinaryIO.WriteParam.DefaultFramePrefix,
    frameSuffix: Array[Byte] => Array[Byte] = BinaryIO.WriteParam.DefaultFrameSuffix
  )(implicit ev: T <:< Array[Byte]): ClosedTap[Nothing] =
    this
      .asInstanceOf[SCollection[Array[Byte]]]
      .write(BinaryIO(path))(
        BinaryIO
          .WriteParam(suffix, numShards, compression, header, footer, framePrefix, frameSuffix)
      )

  /**
   * Save this SCollection with a custom output transform. The transform should have a unique name.
   * @group output
   */
  def saveAsCustomOutput[O <: POutput](
    name: String,
    transform: PTransform[PCollection[T], O]
  ): ClosedTap[Nothing] = {
    if (context.isTest) {
      TestDataManager.getOutput(context.testId.get)(CustomIO[T](name))(this)
    } else {
      this.internal.apply(name, transform)
    }

    ClosedTap[Nothing](EmptyTap)
  }

  /**
   * Save this SCollection with a custom output transform.
   * @group output
   */
  def saveAsCustomOutput[O <: POutput](
    transform: PTransform[PCollection[T], O]
  ): ClosedTap[Nothing] = {
    if (context.isTest) {
      TestDataManager.getOutput(context.testId.get)(CustomIO[T](this.tfName))(this)
    } else {
      this.applyInternal(transform)
    }

    ClosedTap[Nothing](EmptyTap)
  }

  private[scio] def saveAsInMemoryTap(implicit coder: Coder[T]): ClosedTap[T] = {
    val tap = new InMemoryTap[T]
    InMemorySink.save(tap.id, this)
    ClosedTap(tap)
  }

  /**
   * Generic write method for all `ScioIO[T]` implementations, if it is test pipeline this will
   * evaluate pre-registered output IO implementation which match for the passing `ScioIO[T]`
   * implementation. if not this will invoke [[com.spotify.scio.io.ScioIO[T]#write]] method along
   * with write configurations passed by.
   *
   * @param io     an implementation of `ScioIO[T]` trait
   * @param params configurations need to pass to perform underline write implementation
   */
  def write(io: ScioIO[T])(params: io.WriteP)(implicit coder: Coder[T]): ClosedTap[io.tapT.T] =
    io.writeWithContext(this, params)

  def write(io: ScioIO[T] { type WriteP = Unit })(implicit coder: Coder[T]): ClosedTap[io.tapT.T] =
    io.writeWithContext(this, ())
}

private[scio] class SCollectionImpl[T](val internal: PCollection[T], val context: ScioContext)
    extends SCollection[T] {}
