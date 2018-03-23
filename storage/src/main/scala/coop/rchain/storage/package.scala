package coop.rchain

import cats.implicits._
import com.typesafe.scalalogging.Logger

import scala.annotation.tailrec

package object storage {

  private val logger: Logger = Logger[this.type]

  /* Consume */

  @tailrec
  private[storage] final def findMatchingDataCandidate[C, P, A](
      channel: C,
      data: List[(Datum[A], Int)],
      pattern: P
  )(implicit m: Match[P, A]): Option[DataCandidate[C, A]] =
    data match {
      case Nil => None
      case (Datum(matchCandidate, persist), dataIndex) :: remaining =>
        m.get(pattern, matchCandidate) match {
          case None      => findMatchingDataCandidate(channel, remaining, pattern)
          case Some(mat) => Some(DataCandidate(channel, Datum(mat, persist), dataIndex))
        }
    }

  private[storage] def extractDataCandidates[C, P, A, K](store: IStore[C, P, A, K],
                                                         channels: List[C],
                                                         patterns: List[P])(txn: store.T)(
      implicit m: Match[P, A]): Option[List[DataCandidate[C, A]]] = {
    val options: List[Option[DataCandidate[C, A]]] =
      channels.zip(patterns).map {
        case (channel, pattern) =>
          val indexedData: List[(Datum[A], Int)] = store.getAs(txn, List(channel)).zipWithIndex
          findMatchingDataCandidate(channel, indexedData, pattern)
      }
    options.sequence[Option, DataCandidate[C, A]]
  }

  private[storage] def extractDataCandidates[C, P, A, K](
      store: IStore[C, P, A, K],
      channels: List[C],
      patterns: List[P],
      batChannel: C,
      data: Datum[A])(txn: store.T)(implicit m: Match[P, A]): Option[List[DataCandidate[C, A]]] = {
    val options: List[Option[DataCandidate[C, A]]] =
      channels.zip(patterns).map {
        case (channel, pattern) if channel == batChannel =>
          val indexedData: List[(Datum[A], Int)] = List((data, -1))
          findMatchingDataCandidate(channel, indexedData, pattern)
        case (channel, pattern) =>
          val indexedData: List[(Datum[A], Int)] = store.getAs(txn, List(channel)).zipWithIndex
          findMatchingDataCandidate(channel, indexedData, pattern)
      }
    options.sequence[Option, DataCandidate[C, A]]
  }

  /** `consume` does the "consume" thing
    *
    * @param store
    * @param channels
    * @param patterns
    * @param continuation
    * @param persist
    * @tparam C a type representing a channel
    * @tparam P a type representing a pattern
    * @tparam A a type representing a piece of data
    * @tparam K a type representing a continuation
    */
  def consume[C, P, A, K](store: IStore[C, P, A, K],
                          channels: List[C],
                          patterns: List[P],
                          continuation: K,
                          persist: Boolean)(implicit m: Match[P, A]): Option[(K, List[A])] = {
    if (channels.length =!= patterns.length) {
      val msg = "channels.length must equal patterns.length"
      logger.error(msg)
      throw new IllegalArgumentException(msg)
    }
    store.withTxn(store.createTxnWrite()) { txn =>
      logger.info(
        s"consume: searching for data matching <patterns: $patterns> at <channels: $channels>")
      extractDataCandidates(store, channels, patterns)(txn) match {
        case None =>
          store.putK(txn, channels, WaitingContinuation(patterns, continuation, persist))
          for (channel <- channels) store.addJoin(txn, channel, channels)
          logger.info(
            s"consume: no data found, storing <(patterns, continuation): ($patterns, $continuation)> at <channels: $channels>")
          None
        case Some(dataCandidates) =>
          dataCandidates.foreach {
            case DataCandidate(candidateChannel, Datum(_, persistData), dataIndex)
                if !persistData =>
              store.removeA(txn, candidateChannel, dataIndex)
            case _ =>
              ()
          }
          logger.info(s"consume: data found for <patterns: $patterns> at <channels: $channels>")
          Some((continuation, dataCandidates.map(_.datum.a)))
      }
    }
  }

  /* Produce */
  @tailrec
  private[storage] final def extractFirstMatch[C, P, A, K](
      store: IStore[C, P, A, K],
      channels: List[C],
      matchCandidates: List[(WaitingContinuation[P, K], Int)],
      channel: C,
      data: Datum[A])(txn: store.T)(implicit m: Match[P, A]): Option[ProduceCandidate[C, P, A, K]] =
    matchCandidates match {
      case Nil => None
      case (p @ WaitingContinuation(patterns, _, _), index) :: remaining =>
        extractDataCandidates(store, channels, patterns, channel, data)(txn) match {
          case None =>
            extractFirstMatch(store, channels, remaining, channel, data)(txn)
          case Some(dataCandidates) =>
            Some(ProduceCandidate(channels, p, index, dataCandidates))
        }
    }

  @tailrec
  private[storage] final def extractProduceCandidateAlt[C, P, A, K](
      store: IStore[C, P, A, K],
      groupedChannels: List[List[C]],
      channel: C,
      data: Datum[A])(txn: store.T)(implicit m: Match[P, A]): Option[ProduceCandidate[C, P, A, K]] =
    groupedChannels match {
      case Nil => None
      case channels :: remaining =>
        val matchCandidates: List[(WaitingContinuation[P, K], Int)] =
          store.getPsK(txn, channels).zipWithIndex
        extractFirstMatch(store, channels, matchCandidates, channel, data)(txn) match {
          case None             => extractProduceCandidateAlt(store, remaining, channel, data)(txn)
          case produceCandidate => produceCandidate
        }
    }

  /** `produce` does the "produce" thing
    *
    * @param store
    * @param channel
    * @param data
    * @param persist
    * @tparam C a type representing a channel
    * @tparam P a type representing a pattern
    * @tparam A a type representing a piece of data
    * @tparam K a type representing a continuation
    */
  def produce[C, P, A, K](store: IStore[C, P, A, K], channel: C, data: A, persist: Boolean)(
      implicit m: Match[P, A]): Option[(K, List[A])] =
    store.withTxn(store.createTxnWrite()) { txn =>
      val groupedChannels: List[List[C]] = store.getJoin(txn, channel)
      logger.info(
        s"produce: searching for matching continuations at <groupedChannels: $groupedChannels>")
      extractProduceCandidateAlt(store, groupedChannels, channel, Datum(data, persist))(txn) match {
        case Some(
            ProduceCandidate(channels,
                             WaitingContinuation(_, continuation, persistK),
                             continuationIndex,
                             dataCandidates)) =>
          if (!persistK) {
            store.removePsK(txn, channels, continuationIndex)
          }
          dataCandidates.foreach {
            case DataCandidate(candidateChannel, Datum(_, persistData), dataIndex) =>
              if (!persistData && dataIndex >= 0) {
                store.removeA(txn, candidateChannel, dataIndex)
              }
              store.removeJoin(txn, candidateChannel, channels)
            case _ =>
              ()
          }
          logger.info(s"produce: matching continuation found at <channels: $channels>")
          Some(continuation, dataCandidates.map(_.datum.a))
        case None =>
          logger.info(s"produce: no matching continuation found")
          store.putA(txn, List(channel), Datum(data, persist))
          logger.info(s"produce: persisted <data: $data> at <channel: $channel>")
          None

      }
    }
}
