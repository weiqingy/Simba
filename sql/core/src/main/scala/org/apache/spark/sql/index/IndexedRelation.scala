package org.apache.spark.sql.index

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.MultiInstanceRelation
import org.apache.spark.sql.catalyst.expressions.{BindReferences, Attribute}
import org.apache.spark.sql.catalyst.plans.logical.{Statistics, LogicalPlan}
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.partitioner.{HashPartition, STRPartition, RangePartition}
import org.apache.spark.sql.spatial.Point
import org.apache.spark.sql.types.{IntegerType, DoubleType}
import org.apache.spark.storage.StorageLevel

/**
 * Created by Dong Xie on 9/28/15.
 * Index Relation Structures for SparkSpatial
 */


private[sql] case class PackedPartitionWithIndex(data: Array[InternalRow], index: Index)

private[sql] object IndexedRelation {
  def apply(child: SparkPlan, table_name: Option[String], index_type: IndexType,
            column_keys: List[Attribute], index_name: String): IndexedRelation = {
    index_type match {
      case TreeMapType => new TreeMapIndexedRelation(child.output, child, table_name, column_keys, index_name)()
      case RTreeType => new RTreeIndexedRelation(child.output, child, table_name, column_keys, index_name)()
      case HashMapType => new HashMapIndexedRelation(child.output, child, table_name, column_keys, index_name)()
      case _ => null
    }
  }
}

private[sql] abstract class IndexedRelation extends LogicalPlan {
  self: Product =>
  var _indexedRDD: RDD[PackedPartitionWithIndex]
  def indexedRDD = _indexedRDD
  def sqlContext = SparkPlan.currentContext.get()
  def numShufflePartitions = sqlContext.conf.numShufflePartitions
  def maxEntriesPerNode = sqlContext.conf.maxEntriesPerNode
  def sampleRate = sqlContext.conf.sampleRate
  def transferThreshold = sqlContext.conf.transferThreshold

  override def children = Seq.empty
  def output: Seq[Attribute]

  def withOutput(newOutput: Seq[Attribute]): IndexedRelation

  @transient override lazy val statistics = Statistics(
    // TODO: Instead of returning a default value here, find a way to return a meaningful size
    // estimate for RDDs. See PR 1238 for more discussions.
    sizeInBytes = BigInt(sqlContext.conf.defaultSizeInBytes)
  )
}

private[sql] case class HashMapIndexedRelation(
    output: Seq[Attribute],
    child: SparkPlan,
    table_name: Option[String],
    column_keys: List[Attribute],
    index_name: String)(var _indexedRDD: RDD[PackedPartitionWithIndex] = null)
  extends IndexedRelation with MultiInstanceRelation {
  require(column_keys.length == 1)

  if (_indexedRDD == null) {
    buildIndex()
  }

  private[sql] def buildIndex(): Unit = {
    val dataRDD = child.execute().map(row => {
      val key = BindReferences.bindReference(column_keys.head, child.output).eval(row)
      (key, row)
    })

    val partitionedRDD = HashPartition(dataRDD, numShufflePartitions)
    val indexed = partitionedRDD.mapPartitions(iter => {
      val data = iter.toArray
      val index = HashMapIndex(data)
      Array(PackedPartitionWithIndex(data.map(_._2), index)).iterator
    }).persist(StorageLevel.MEMORY_AND_DISK_SER)

    indexed.setName(table_name.map(n => s"$n $index_name").getOrElse(child.toString))
    _indexedRDD = indexed
  }

  override def newInstance() = {
    new HashMapIndexedRelation(output.map(_.newInstance()), child, table_name, column_keys, index_name)(_indexedRDD)
      .asInstanceOf[this.type]
  }

  override def withOutput(new_output: Seq[Attribute]) = {
    new HashMapIndexedRelation(new_output, child, table_name, column_keys, index_name)(_indexedRDD)
  }
}

private[sql] case class TreeMapIndexedRelation(
    output: Seq[Attribute],
    child: SparkPlan,
    table_name: Option[String],
    column_keys: List[Attribute],
    index_name: String)(var _indexedRDD: RDD[PackedPartitionWithIndex] = null,
                        var range_bounds: Array[Double] = null)
  extends IndexedRelation with MultiInstanceRelation {
  require(column_keys.length == 1)

  if (_indexedRDD == null) {
    buildIndex()
  }

  private[sql] def buildIndex(): Unit = {
    val dataRDD = child.execute().map(row => {
      val key = BindReferences.bindReference(column_keys.head, child.output).eval(row).asInstanceOf[Number].doubleValue
      (key, row)
    })

    val (partitionedRDD, tmp_bounds) = RangePartition.rowPartition(dataRDD, numShufflePartitions)
    range_bounds = tmp_bounds
    val indexed = partitionedRDD.mapPartitions(iter => {
      val data = iter.toArray
      val index = TreeMapIndex(data)
      Array(PackedPartitionWithIndex(data.map(_._2), index)).iterator
    }).persist(StorageLevel.MEMORY_AND_DISK_SER)

    indexed.setName(table_name.map(n => s"$n $index_name").getOrElse(child.toString))
    _indexedRDD = indexed
  }

  override def newInstance() = {
    new TreeMapIndexedRelation(output.map(_.newInstance()), child, table_name, column_keys, index_name)(_indexedRDD)
      .asInstanceOf[this.type]
  }

  override def withOutput(new_output: Seq[Attribute]) = {
    new TreeMapIndexedRelation(new_output, child, table_name, column_keys, index_name)(_indexedRDD, range_bounds)
  }
}

private[sql] case class RTreeIndexedRelation(
    output: Seq[Attribute],
    child: SparkPlan,
    table_name: Option[String],
    column_keys: List[Attribute],
    index_name: String)(var _indexedRDD: RDD[PackedPartitionWithIndex] = null,
                        var global_rtree: RTree = null)
  extends IndexedRelation with MultiInstanceRelation {

  private def checkKeys: Boolean = {
    for (i <- column_keys.indices)
      if (!(column_keys(i).dataType.isInstanceOf[DoubleType] || column_keys(i).dataType.isInstanceOf[IntegerType]))
        return false
    true
  }

  require(checkKeys)

  if (_indexedRDD == null) {
    buildIndex()
  }

  private[sql] def buildIndex(): Unit = {
    val dataRDD = child.execute().map(row => {
      val now = column_keys.map(x =>
        BindReferences.bindReference(x, child.output).eval(row).asInstanceOf[Number].doubleValue()
      ).toArray
      (new Point(now), row)
    })

    val dimension = column_keys.length
    val max_entries_per_node = maxEntriesPerNode
    val (partitionedRDD, mbr_bounds) = STRPartition(dataRDD, dimension, numShufflePartitions,
                                                    sampleRate, transferThreshold, max_entries_per_node)


    val indexed = partitionedRDD.mapPartitions { iter =>
      val data = iter.toArray
      var index: RTree = null
      if (data.length > 0) index = RTree(data.map(_._1).zipWithIndex, max_entries_per_node)
      Array(PackedPartitionWithIndex(data.map(_._2), index)).iterator
    }.persist(StorageLevel.MEMORY_AND_DISK_SER)

    val partitionSize = indexed.mapPartitions(iter => iter.map(_.data.length)).collect()

    global_rtree = RTree(mbr_bounds.zip(partitionSize).map(x => (x._1._1, x._1._2, x._2)), max_entries_per_node)
    indexed.setName(table_name.map(n => s"$n $index_name").getOrElse(child.toString))
    _indexedRDD = indexed
  }

  override def newInstance() = {
    new RTreeIndexedRelation(output.map(_.newInstance()), child, table_name, column_keys, index_name)(_indexedRDD)
      .asInstanceOf[this.type]
  }

  override def withOutput(new_output: Seq[Attribute]): IndexedRelation = {
    RTreeIndexedRelation(new_output, child, table_name, column_keys, index_name)(_indexedRDD, global_rtree)
  }
}
