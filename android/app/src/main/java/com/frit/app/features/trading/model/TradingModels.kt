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
    INDICES("Indices"),
    SYNTHETICS("Synthetics")
}

@Parcelize
data class MarketRecommendation(
    val symbol: String,
    val name: String,
    val market: String, // FX, CRYPTO, STOCKS, INDICES, SYNTHETICS
    val type: String, // TRADE, INVEST
    val horizon: String,
    val action: String, // STRONG BUY, BUY, INVEST, ACCUMULATE, SELL, STRONG SELL, RISE / CALL, FALL / PUT
    val grade: String, // A+, A, B+
    val performanceScore: Int,
    val winRateEst: Int,
    val currentPrice: Double,
    val return24h: Double,
    val rsi: Int,
    val trend: String,
    val entry: Double,
    val sl: Double,
    val tp1: Double,
    val tp2: Double,
    val rr: Double,
    val volatility: String,
    val reasoning: String,
    val isSynthetic: Boolean = false,
    val preferredContract: String? = null
) : Parcelable

@Parcelize
data class MarketNewsItem(
    val id: String,
    val title: String,
    val currency: String,
    val date: String,
    val time: Long,
    val impact: String, // HIGH, MEDIUM, LOW, HOLIDAY
    val forecast: String = "-",
    val previous: String = "-",
    val actual: String? = null,
    val isUpcoming: Boolean = false,
    val outcome: String? = null, // BEAT, MISSED, IN_LINE
    val variance: String? = null,
    val bias: String? = null,
    val volatilityRating: String? = null,
    val marketAnalysis: String? = null,
    val affectedPairs: List<String> = emptyList()
) : Parcelable

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
    val isRead: Boolean = false,
    // Which engine/bot produced it — the notification bar tags by this.
    val botSource: String = "engine",
    // Kelly-based lot-size recommendation (item 9).
    val lotSize: Double? = null,
    val lotReason: String? = null,
    val riskUsd: Double? = null,
    val balance: Double? = null,
    val riskProfile: String? = null
) : Parcelable

enum class SignalDecision {
    BUY, SELL, CALL, PUT, WAIT, WAIT_PULLBACK, WAIT_RANGE, NO_TRADE, DATA_UNAVAILABLE
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
    val maxTradesDay: Int = 2,
    val positionsPerSignal: Int = 1
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
    val read: Boolean = false,
    val mine: Boolean = false,
    val detail: TradeDetail? = null
) : Parcelable

/**
 * Full trade detail behind a notification — shown when the user taps it.
 */
@Parcelize
data class TradeDetail(
    val entry: Double = 0.0,
    val sl: Double = 0.0,
    val tp1: Double = 0.0,
    val tp2: Double = 0.0,
    val rr: Double = 0.0,
    val scenario: String = "",
    val reasoning: String = "",
    val lotSize: Double? = null,
    val lotReason: String? = null,
    val riskUsd: Double? = null
) : Parcelable

/**
 * The user's trading account (server /api/trading/account): the balance they
 * actually trade on, their declared risk profile, and their own broker
 * credentials for users who want the bot to trade THEIR account.
 */
@Parcelize
data class TradingAccount(
    val userId: String = "",
    val balance: Double = 1000.0,
    val riskProfile: String = "MODERATE",
    /** "AUTO" = the lot-size engine (Kelly) picks every lot from balance + profile.
     *  "FIXED" = [fixedLot] is used exactly as the user entered it. */
    val lotMode: String = "AUTO",
    val fixedLot: Double = 0.01,
    /** How many positions the bot opens per signal. 1 = one entry per signal;
     *  2+ = pyramiding / scaling into the same direction. */
    val positionsPerSignal: Int = 1,
    val hasDerivToken: Boolean = false,
    val derivTokenMode: String = "platform",
    val hasMt5: Boolean = false,
    val mt5Server: String? = null,
    val mt5Login: String? = null,
    val mt5Broker: String? = null
) : Parcelable

/**
 * Kelly-based lot-size recommendation for a setup.
 */
@Parcelize
data class LotSizeAdvice(
    val lotSize: Double = 0.0,
    val riskUsd: Double = 0.0,
    val fraction: Double = 0.0,
    val kelly: Double = 0.0,
    val riskProfile: String = "MODERATE",
    val plannedRr: Double = 0.0,
    val balance: Double = 0.0,
    val basis: String = "",
    val reason: String = ""
) : Parcelable

/**
 * One row of the unified paper portfolio: any engine, any bot, any rail.
 */
@Parcelize
data class PaperTrade(
    val bot: String,
    val source: String,
    val symbol: String,
    val side: String,
    val entry: Double,
    val exit: Double,
    val pnl: Double,
    val pnlKind: String,
    val status: String,
    val openedAt: Long,
    val closedAt: Long?,
    val note: String
) : Parcelable

/**
 * Per-bot performance summary for the portfolio tab.
 */
@Parcelize
data class BotPerformance(
    val bot: String,
    val trades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val open: Int = 0,
    val winRate: Double? = null,
    val pnlUsd: Double = 0.0,
    val pnlUnits: Double = 0.0
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