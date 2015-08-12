package com.pagerduty.eris.widerow

import com.pagerduty.eris._
import com.netflix.astyanax.util.RangeBuilder
import com.pagerduty.widerow.{ Entry, EntryColumn, WideRowDriver }
import scala.collection.JavaConversions._
import com.pagerduty.eris.FutureConversions._
import scala.concurrent.{ExecutionContextExecutor, Future}


/**
 * Eris implementation of WideRowDriver interface.
 *
 * @param columnFamilyModel target column family model
 * @param executor executor context for async tasks
 * @tparam RowKey column family row key
 * @tparam ColName column family column name
 * @tparam ColValue column family columna value
 */
class WideRowDriverImpl[RowKey, ColName, ColValue](
    val columnFamilyModel: ColumnFamilyModel[RowKey, ColName, ColValue],
    implicit val executor: ExecutionContextExecutor)
  extends WideRowDriver[RowKey, ColName, ColValue]
{
  /**
   * Example:
   * {{{
   * def instrument(methodName: String): (Future[T] => Future[T]) = {
   *   val start = System.currentTimeMillis()
   *   (future: Future[T]) => {
   *     future.onComplete { _ =>
   *       val duration = System.currentTimeMillis() - start
   *       Stats.recordMetrics(methodName, duration)
   *     }
   *     future
   *   }
   * }
   * }}}
   */
  protected def instrument[T](methodName: String): (Future[T] => Future[T]) = {
    identity
  }

  def fetchData(
      rowKey: RowKey,
      ascending: Boolean,
      from: Option[ColName],
      to: Option[ColName],
      limit: Int)
  : Future[IndexedSeq[Entry[RowKey, ColName, ColValue]]] = {
    val intercept = instrument[IndexedSeq[Entry[RowKey, ColName, ColValue]]]("fetchData")

    val range = {
      val builder = new RangeBuilder().setLimit(limit).setReversed(!ascending)
      if (from.isDefined) builder.setStart(from.get, columnFamilyModel.colNameSerializer)
      if (to.isDefined) builder.setEnd(to.get, columnFamilyModel.colNameSerializer)
      builder.build()
    }

    val futureResult = columnFamilyModel.keyspace
      .prepareQuery(columnFamilyModel.columnFamily)
      .getKey(rowKey)
      .autoPaginate(false)
      .withColumnRange(range)
      .executeAsync()

    intercept(futureResult.map { operationResult =>
      val result = operationResult.getResult.toIndexedSeq
      for (column <- result) yield {
        Entry(
          rowKey,
          EntryColumn(
            column.getName,
            column.getValue(columnFamilyModel.colValueSerializer),
            Option(column.getTtl).filter(_ != 0)))
      }
    })
  }

  def update(
      rowKey: RowKey,
      drop: Boolean,
      remove: Iterable[ColName],
      insert: Iterable[EntryColumn[ColName, ColValue]])
  : Future[Unit] = {
    val intercept = instrument[Unit]("update")
    val serializer = columnFamilyModel.colValueSerializer

    val batch = columnFamilyModel.keyspace.prepareMutationBatch()

    val rowBatch = batch.withRow(columnFamilyModel.columnFamily, rowKey)
    if (drop) rowBatch.delete()
    for (colName <- remove) {
      rowBatch.deleteColumn(colName)
    }
    for (column <- insert) {
      val ttl = column.ttlSeconds.map(seconds => seconds: java.lang.Integer).orNull
      rowBatch.putColumn(column.name, serializer.toByteBuffer(column.value), ttl)
    }

    intercept(batch.executeAsync().map(_ => Unit))
  }
}