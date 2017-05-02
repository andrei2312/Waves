package scorex.transaction

import com.wavesplatform.settings.{BlockchainSettings, FunctionalitySettings}
import com.wavesplatform.state2.Validator
import com.wavesplatform.state2.reader.StateReader
import scorex.account.{Account, PrivateKeyAccount, PublicKeyAccount}
import scorex.block.Block
import scorex.consensus.TransactionsOrdering
import scorex.consensus.nxt.NxtLikeConsensusBlockData
import scorex.crypto.encode.Base58
import scorex.crypto.hash.FastCryptographicHash.hash
import scorex.network.ConnectedPeer
import scorex.transaction.SimpleTransactionModule._
import scorex.utils.{ScorexLogging, Time}

import scala.concurrent.duration._
import scala.util.control.NonFatal

trait TransactionModule {

  def onNewOffchainTransaction[T <: Transaction](transaction: T, exceptOf: Option[ConnectedPeer] = None): Either[ValidationError, T]

}

object TransactionModule extends ScorexLogging {

  def blockOrdering(history: History, state: StateReader, fs: FunctionalitySettings, time: Time): Ordering[Block] = Ordering.by {
    block =>
      val parent = history.blockById(block.reference).get
      val blockCreationTime = nextBlockGenerationTime(history, state, fs, time: Time)(parent, block.signerData.generator)
        .getOrElse(block.timestamp)
      (block.blockScore, -blockCreationTime)
  }

  def nextBlockGenerationTime(history: History, state: StateReader, fs: FunctionalitySettings, time: Time)(block: Block, account: PublicKeyAccount): Option[Long] = {
    history.heightOf(block.uniqueId)
      .map(height => (height, generatingBalance(state, fs)(account, height))).filter(_._2 > 0)
      .flatMap {
        case (height, balance) =>
          val cData = block.consensusData
          val hit = calcHit(cData, account)
          val t = cData.baseTarget

          val result =
            Some((hit * 1000) / (BigInt(t) * balance) + block.timestamp)
              .filter(_ > 0).filter(_ < Long.MaxValue)
              .map(_.toLong)

          log.debug({
            val currentTime = time.correctedTime()
            s"Next block gen time: $result " +
              s"in ${
                result.map(t => (t - currentTime) / 1000)
              } seconds, " +
              s"hit: $hit, target: $t, " +
              s"account:  $account, account balance: $balance " +
              s"last block id: ${
                block.encodedId
              }, " +
              s"height: $height"
          })

          result
      }
  }

  def generatingBalance(state: StateReader, fs: FunctionalitySettings)(account: Account, atHeight: Int): Long = {
    val generatingBalanceDepth = if (atHeight >= fs.generatingBalanceDepthFrom50To1000AfterHeight) 1000 else 50
    state.effectiveBalanceAtHeightWithConfirmations(account, atHeight, generatingBalanceDepth)
  }

  def generateNextBlocks(history: History, state: StateReader, bcs: BlockchainSettings, utx: UnconfirmedTransactionsStorage, time: Time)(accounts: Seq[PrivateKeyAccount]): Seq[Block] =
    accounts.flatMap(generateNextBlock(history, state, bcs, utx, time) _)

  def generateNextBlock(history: History, state: StateReader, bcs: BlockchainSettings, utx: UnconfirmedTransactionsStorage, time: Time)
                       (account: PrivateKeyAccount): Option[Block] = try {

    val lastBlock = history.lastBlock
    val height = history.heightOf(lastBlock).get
    val balance = generatingBalance(state, bcs.functionalitySettings)(account, height)

    if (balance < MinimalEffectiveBalanceForGenerator) {
      throw new IllegalStateException(s"Effective balance $balance is less that minimal ($MinimalEffectiveBalanceForGenerator)")
    }

    val lastBlockKernelData = lastBlock.consensusData
    val currentTime = time.correctedTime()

    val h = calcHit(lastBlockKernelData, account)
    val t = calcTarget(lastBlock, currentTime, balance)

    val eta = (currentTime - lastBlock.timestamp) / 1000

    log.debug(s"hit: $h, target: $t, generating ${
      h < t
    }, eta $eta, " +
      s"account:  $account " +
      s"account balance: $balance " +
      s"last block id: ${
        lastBlock.encodedId
      }, " +
      s"height: $height, " +
      s"last block target: ${
        lastBlockKernelData.baseTarget
      }"
    )

    if (h < t) {

      val avgBlockDelay = bcs.genesisSettings.averageBlockDelay
      val btg = calcBaseTarget(history)(avgBlockDelay, lastBlock, currentTime)
      val gs = calcGeneratorSignature(lastBlockKernelData, account)
      val consensusData = NxtLikeConsensusBlockData(btg, gs)

      val unconfirmed = packUnconfirmed(bcs.functionalitySettings, utx, state, time)(Some(height))
      log.debug(s"Build block with ${
        unconfirmed.size
      } transactions")
      log.debug(s"Block time interval is $eta seconds ")

      Some(Block.buildAndSign(Version,
        currentTime,
        lastBlock.uniqueId,
        consensusData,
        unconfirmed,
        account))
    } else None
  } catch {
    case e: UnsupportedOperationException =>
      log.debug(s"DB can't find last block because of unexpected modification")
      None
    case e: IllegalStateException =>
      log.debug(s"Failed to generate new block: ${
        e.getMessage
      }")
      None
  }


  def packUnconfirmed(fs: FunctionalitySettings, utx: UnconfirmedTransactionsStorage, state: StateReader, time: Time)
                     (heightOpt: Option[Int]): Seq[Transaction] = synchronized {
    clearIncorrectTransactions(fs, state, utx, time)
    val txs = utx.all()
      .sorted(TransactionsOrdering.InUTXPool)
      .take(MaxTransactionsPerBlock)
      .sorted(TransactionsOrdering.InBlock)
    Validator.validate(fs, state, txs, heightOpt, time.correctedTime())._2
  }

  def clearIncorrectTransactions(fs: FunctionalitySettings, stateReader: StateReader, utx: UnconfirmedTransactionsStorage, time: Time): Unit = {
    val currentTime = time.correctedTime()
    val txs = utx.all()
    val notExpired = txs.filter { tx => (currentTime - tx.timestamp).millis <= MaxTimeUtxPast }
    val notFromFuture = notExpired.filter { tx => (tx.timestamp - currentTime).millis <= MaxTimeUtxFuture }
    val inOrder = notFromFuture.sorted(TransactionsOrdering.InUTXPool)
    val valid = Validator.validate(fs, stateReader, inOrder, None, currentTime)._2
    txs.diff(valid).foreach(utx.remove)
  }

  private def calcBaseTarget(history: History)(avgBlockDelay: FiniteDuration, prevBlock: Block, timestamp: Long): Long = {
    val avgDelayInSeconds = avgBlockDelay.toSeconds

    def normalize(value: Long): Double = value * avgDelayInSeconds / (60: Double)

    val height = history.heightOf(prevBlock).get
    val prevBaseTarget = prevBlock.consensusDataField.value.baseTarget
    if (height % 2 == 0) {
      val blocktimeAverage = history.parent(prevBlock, AvgBlockTimeDepth - 1)
        .map(b => (timestamp - b.timestamp) / AvgBlockTimeDepth)
        .getOrElse(timestamp - prevBlock.timestamp) / 1000

      val minBlocktimeLimit = normalize(53)
      val maxBlocktimeLimit = normalize(67)
      val baseTargetGamma = normalize(64)
      val maxBaseTarget = Long.MaxValue / avgDelayInSeconds

      val baseTarget = (if (blocktimeAverage > avgDelayInSeconds) {
        prevBaseTarget * Math.min(blocktimeAverage, maxBlocktimeLimit) / avgDelayInSeconds
      } else {
        prevBaseTarget - prevBaseTarget * baseTargetGamma *
          (avgDelayInSeconds - Math.max(blocktimeAverage, minBlocktimeLimit)) / (avgDelayInSeconds * 100)
      }).toLong

      scala.math.min(baseTarget, maxBaseTarget)
    } else {
      prevBaseTarget
    }
  }

  private def calcTarget(prevBlock: Block, timestamp: Long, balance: Long): BigInt = {
    val eta = (timestamp - prevBlock.timestamp) / 1000
    BigInt(prevBlock.consensusData.baseTarget) * eta * balance
  }

  private def calcHit(lastBlockData: NxtLikeConsensusBlockData, generator: PublicKeyAccount): BigInt =
    BigInt(1, calcGeneratorSignature(lastBlockData, generator).take(8).reverse)

  private def calcGeneratorSignature(lastBlockData: NxtLikeConsensusBlockData, generator: PublicKeyAccount) =
    hash(lastBlockData.generationSignature ++ generator.publicKey)

  def clearFromUnconfirmed(fs: FunctionalitySettings, stateReader: StateReader, utx: UnconfirmedTransactionsStorage, time: Time)(data: Seq[Transaction]): Unit = {
    data.foreach(tx => utx.getBySignature(tx.id) match {
      case Some(unconfirmedTx) => utx.remove(unconfirmedTx)
      case None =>
    })

    clearIncorrectTransactions(fs, stateReader, utx, time) // todo makes sence to remove expired only at this point
  }

  def isValid(history: History, state: StateReader, bcs: BlockchainSettings, time: Time)(block: Block): Boolean = try {

    val fs = bcs.functionalitySettings

    val blockTime = block.timestampField.value

    require((blockTime - time.correctedTime()).millis < MaxTimeDrift, s"Block timestamp $blockTime is from future")


    if (blockTime > fs.requireSortedTransactionsAfter) {
      require(block.transactionDataField.asInstanceOf[TransactionsBlockField].value.sorted(TransactionsOrdering.InBlock) == block.transactionDataField.asInstanceOf[TransactionsBlockField].value, "Transactions must be sorted correctly")
    }

    val parentOpt = history.parent(block)
    require(parentOpt.isDefined || history.height() == 1, s"Can't find parent block with id '${
      Base58.encode(block.referenceField.value)
    }' of block " +
      s"'${
        Base58.encode(block.uniqueId)
      }'")

    val parent = parentOpt.get
    val parentHeightOpt = history.heightOf(parent.uniqueId)
    require(parentHeightOpt.isDefined, s"Can't get parent block with id '${
      Base58.encode(block.referenceField.value)
    }' height")
    val parentHeight = parentHeightOpt.get

    val prevBlockData = parent.consensusDataField.value
    val blockData = block.consensusDataField.value

    val cbt = calcBaseTarget(history)(bcs.genesisSettings.averageBlockDelay, parent, blockTime)
    val bbt = blockData.baseTarget
    require(cbt == bbt, s"Block's basetarget is wrong, calculated: $cbt, block contains: $bbt")

    val generator = block.signerDataField.value.generator

    //check generation signature
    val calcGs = calcGeneratorSignature(prevBlockData, generator)
    val blockGs = blockData.generationSignature
    require(calcGs.sameElements(blockGs),
      s"Block's generation signature is wrong, calculated: ${
        calcGs.mkString
      }, block contains: ${
        blockGs.mkString
      }")

    val effectiveBalance = generatingBalance(state, fs)(generator, parentHeight)

    if (blockTime >= fs.minimalGeneratingBalanceAfterTimestamp) {
      require(effectiveBalance >= MinimalEffectiveBalanceForGenerator, s"Effective balance $effectiveBalance is less that minimal ($MinimalEffectiveBalanceForGenerator)")
    }

    //check hit < target
    calcHit(prevBlockData, generator) < calcTarget(parent, blockTime, effectiveBalance)
  } catch {
    case e: IllegalArgumentException =>
      log.error("Error while checking a block", e)
      false
    case NonFatal(t) =>
      log.error("Fatal error while checking a block", t)
      throw t
  }
}

