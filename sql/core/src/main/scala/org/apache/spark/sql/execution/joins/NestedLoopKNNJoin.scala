package org.apache.spark.sql.execution.joins

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.spatial._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.execution.{SparkPlan, BinaryNode}
import org.apache.spark.sql.partitioner.MapDPartition
import org.apache.spark.util.BoundedPriorityQueue

import scala.collection.mutable
import scala.util.Random

/**
 * Created by Dong Xie on 9/30/15.
 * KNN Join based on Nested Loop Approach
 */
case class NestedLoopKNNJoin(
    left_keys: Seq[Expression],
    right_keys: Seq[Expression],
    l: Literal,
    left: SparkPlan,
    right: SparkPlan
  ) extends BinaryNode {
  override def output: Seq[Attribute] = left.output ++ right.output

  final val num_partitions = sqlContext.conf.numShufflePartitions
  final val k = l.value.asInstanceOf[Number].intValue()

  private class DisOrdering extends Ordering[(InternalRow, Double)] {
    override def compare(x : (InternalRow, Double), y: (InternalRow, Double)) = -x._2.compare(y._2)
  }

  override protected def doExecute(): RDD[InternalRow] = {
    val tot_rdd = left.execute().map((0, _)).union(right.execute().map((1, _)))

    val tot_dup_rdd = tot_rdd.flatMap {x =>
      val rand_no = new Random().nextInt(num_partitions)
      val ans = mutable.ListBuffer[(Int, (Int, InternalRow))]()
      if (x._1 == 0) {
        val base = rand_no * num_partitions
        for (i <- 0 to num_partitions - 1)
          ans += ((base + i, x))
      } else {
        for (i <- 0 to num_partitions - 1)
          ans += ((i * num_partitions + rand_no, x))
      }
      ans
    }

    val tot_dup_partitioned = MapDPartition(tot_dup_rdd, num_partitions * num_partitions)

    tot_dup_partitioned.mapPartitions {iter =>
      var left_data = mutable.ListBuffer[(Point, InternalRow)]()
      var right_data = mutable.ListBuffer[(Point, InternalRow)]()
      while (iter.hasNext) {
        val data = iter.next()
        if (data._2._1 == 0) {
          val tmp_point = new Point (left_keys.map(x => BindReferences.bindReference(x, left.output).eval(data._2._2)
            .asInstanceOf[Number].doubleValue()).toArray)
          left_data += ((tmp_point, data._2._2))
        } else {
          val tmp_point = new Point (right_keys.map(x => BindReferences.bindReference(x, right.output).eval(data._2._2)
            .asInstanceOf[Number].doubleValue()).toArray)
          right_data += ((tmp_point, data._2._2))
        }
      }

      val joined_ans = mutable.ListBuffer[(InternalRow, Array[(InternalRow, Double)])]()

      left_data.foreach(left => {
        var pq = new BoundedPriorityQueue[(InternalRow, Double)](k)(new DisOrdering)
        right_data.foreach(right => pq += ((right._2, right._1.minDist(left._1))))
        joined_ans += ((left._2, pq.toArray))
      })
      joined_ans.iterator
    }.reduceByKey((left, right) => (left ++ right).sortWith(_._2 < _._2).take(k), num_partitions).flatMap {
      now => now._2.map(x => new JoinedRow(now._1, x._1))
    }
  }
}