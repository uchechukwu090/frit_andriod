package com.frit.app.features.trading.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class WatchlistItem(
    val id: Long = 0,
    val symbol: String,
    val name: String,
    val assetClass: AssetClass = AssetClass.FOREX,
    val isEnabled: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable

enum class AssetClass(val displayName: String) {
    FOREX("Forex"),
    CRYPTO("Crypto"),
    STOCKS("Stocks"),
    COMMODITIES("Commodities"),
    INDICES("Indices")
}

@Parcelize
data class TradingSignal(
    val id: String,
    val symbol: String,
    val decision: SignalDecision,
    val confidence: Int,
    val entry: Double,
    val sl: Double,
    val tp1: Double,
    val tp2: Double,
    val rr: Double,
    val scenario: SignalScenario,
    val reasoning: String,
    val pullbackHealth: PullbackHealth? = null,
    val rangeInfo: RangeInfo? = null,
    val costs: CostAnalysis? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val isRead: Boolean = false
) : Parcelable

enum class SignalDecision {
    BUY, SELL, WAIT, WAIT_PULLBACK, WAIT_RANGE, NO_TRADE, DATA_UNAVAILABLE
}

enum class SignalScenario {
    IMMEDIATE, PULLBACK, RANGE, REVERSAL, WAIT
}

@Parcelize
data class PullbackHealth(
    val status: String,
    val isHealthy: Boolean,
    val volumeOk: Boolean,
    val pullbackProbability: Double?,
    val reversalProbability: Double?,
    val adxPercentile: Double?,
    val note: String?
) : Parcelable

@Parcelize
data class RangeInfo(
    val upper: Double,
    val lower: Double,
    val mid: Double,
    val widthAtr: Double,
    val touchesUp: Int,
    val touchesLo: Int,
    val side: String,
    val fibConfluence: Boolean = false
) : Parcelable

@Parcelize
data class CostAnalysis(
    val costPrice: Double,
    val grossRisk: Double,
    val grossWin: Double,
    val netRisk: Double,
    val netWin: Double,
    val netRR: Double,
    val costPctOfRisk: Double,
    val blocked: Boolean,
    val weak: Boolean
) : Parcelable

@Parcelize
data class PortfolioPosition(
    val id: String,
    val symbol: String,
    val side: String,
    val entryPrice: Double,
    val currentPrice: Double,
    val lotSize: Double,
    val sl: Double,
    val tp: Double,
    val unrealizedPnl: Double,
    val realizedPnl: Double,
    val status: PositionStatus,
    val openedAt: Long,
    val closedAt: Long? = null
) : Parcelable

enum class PositionStatus {
    OPEN, CLOSED_WIN, CLOSED_LOSS
}

@Parcelize
data class PortfolioSummary(
    val totalPositions: Int,
    val unrealizedPnl: Double,
    val realizedPnl: Double,
    val totalPnl: Double,
    val balance: Double,
    val equity: Double
) : Parcelable

@Parcelize
data class RiskMetrics(
    val openPositions: Int,
    val maxPositions: Int,
    val dailyPnl: Double,
    val dailyLossLimit: Double,
    val killSwitchActive: Boolean,
    val netUsdExposure: Double,
    val maxUsdExposure: Double,
    val groupExposure: Map<String, Int>,
    val riskPerTrade: Double
) : Parcelable

@Parcelize
data class SentimentData(
    val symbol: String,
    val sentiment: Sentiment,
    val score: Int,
    val bullishSignals: Int,
    val bearishSignals: Int,
    val summary: String,
    val cachedAt: Long
) : Parcelable

enum class Sentiment {
    BULLISH, BEARISH, NEUTRAL
}

@Parcelize
data class SignalExplanation(
    val signalId: String,
    val symbol: String,
    val decision: SignalDecision,
    val confidence: Int,
    val plainEnglish: String,
    val factors: List<ExplanationFactor>,
    val riskWarning: String,
    val detailLevel: String,
    val generatedAt: Long
) : Parcelable

@Parcelize
data class ExplanationFactor(
    val name: String,
    val value: String,
    val impact: FactorImpact,
    val description: String
) : Parcelable

enum class FactorImpact {
    LOW, MEDIUM, HIGH, CRITICAL
}

@Parcelize
data class AIRecommendation(
    val symbol: String,
    val action: SignalDecision,
    val confidence: Int,
    val entry: Double,
    val sl: Double,
    val tp1: Double,
    val tp2: Double,
    val rr: Double,
    val reasoning: String,
    val regime: String?,
    val positionSize: Double
) : Parcelable

@Parcelize
data class LearningContent(
    val id: String,
    val title: String,
    val category: String,
    val readTime: Int,
    val content: String
) : Parcelable

@Parcelize
data class NotificationPrefs(
    val signalAlerts: Boolean = true,
    val tpSlAlerts: Boolean = true,
    val newsAlerts: Boolean = true,
    val dailySummary: Boolean = true
) : Parcelable

@Parcelize
data class ScanResult(
    val signals: List<TradingSignal>,
    val scannedAt: Long
) : Parcelable

/**
 * One trading bot (server /api/trading/bots). auto = run on schedule:
 * intraday/longterm place via the MT5 bridge (paper while unset);
 * options only scans + notifies + paper-tracks, never executes.
 */
@Parcelize
data class BotConfig(
    val name: String,
    val label: String,
    val pair: String,
    val timeframe: String,
    val auto: Boolean,
    val minConfidence: Int,
    val maxTradesDay: Int,
    val executes: Boolean,
    val blurb: String,
    val allowedTimeframes: List<String>,
    val lastRun: String = ""
) : Parcelable

@Parcelize
data class BotRun(
    val bot: String,
    val pair: String,
    val decision: String,
    val confidence: Int,
    val note: String,
    val firedAt: Long
) : Parcelable

/**
 * Bot-pair association (server /api/trading/bot-pairs). Each pair can have
 * multiple bots assigned with per-pair config (timeframe, trade/signal toggles).
 */
@Parcelize
data class BotPairConfig(
    val id: Long = 0,
    val userId: String = "",
    val botName: String,
    val pair: String,
    val timeframe: String,
    val tradeEnabled: Boolean = false,
    val signalEnabled: Boolean = true,
    val minConfidence: Int = 60,
    val maxTradesDay: Int = 2
) : Parcelable

/**
 * Aggregated notification from any bot/engine, tagged by source bot name.
 */
@Parcelize
data class BotNotification(
    val id: String,
    val bot: String,
    val type: String,
    val symbol: String,
    val decision: String,
    val confidence: Int,
    val note: String,
    val timestamp: Long,
    val read: Boolean = false
) : Parcelable

/**
 * Options-suite signal (server /options/scan + /options/fx-scan).
 * Equity legs carry credit/max-loss/breakevens; FX levels carry expected-move
 * bands. Title/text are preformatted for the floating banner notification.
 * synthetic = computed from realized vol (no listed chain); provisional =
 * unofficial delayed quotes. Neither ever places orders — signals only.
 */
@Parcelize
data class OptionsSignal(
    val id: String,
    val symbol: String,
    val kind: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val synthetic: Boolean = false,
    val provisional: Boolean = false
) : Parcelable