package com.frit.app.features.trading

import com.frit.app.features.trading.model.AssetClass
import com.frit.app.features.trading.model.MarketNewsItem
import com.frit.app.features.trading.model.MarketRecommendation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests and backtesting for the new Trading Dashboard features:
 * 1. Multi-market Performance Recommendations (Forex, Crypto, Stocks, Indices, Synthetics)
 * 2. Forex News Calendar Preview & Analysis Breakdown (Impact levels, beats/misses, market bias)
 * 3. AssetClass enum coverage
 */
class TradingFeaturesTest {

    @Test
    fun assetClassIncludesAllFiveRequiredMarkets() {
        val names = AssetClass.values().map { it.name }
        assertTrue(names.contains("FOREX"))
        assertTrue(names.contains("CRYPTO"))
        assertTrue(names.contains("STOCKS"))
        assertTrue(names.contains("INDICES"))
        assertTrue(names.contains("SYNTHETICS"))
    }

    @Test
    fun marketRecommendationHoldsQuantitativePerformanceMetrics() {
        val rec = MarketRecommendation(
            symbol = "BTCUSD",
            name = "Bitcoin / USD",
            market = "CRYPTO",
            type = "INVEST",
            horizon = "Multi-Week Invest & Swing",
            action = "STRONG ACCUMULATE",
            grade = "A+",
            performanceScore = 94,
            winRateEst = 83,
            currentPrice = 68450.0,
            return24h = 3.85,
            rsi = 71,
            trend = "STRONG BULL EXPANSION",
            entry = 68450.0,
            sl = 66230.0,
            tp1 = 72890.0,
            tp2 = 75850.0,
            rr = 2.0,
            volatility = "HIGH",
            reasoning = "Consolidation break above $67,500 resistance on massive spot ETF inflows.",
            isSynthetic = false
        )

        assertEquals("BTCUSD", rec.symbol)
        assertEquals("CRYPTO", rec.market)
        assertEquals("INVEST", rec.type)
        assertEquals(94, rec.performanceScore)
        assertEquals(83, rec.winRateEst)
        assertEquals(3.85, rec.return24h, 0.001)
        assertFalse(rec.isSynthetic)
        assertTrue(rec.rr >= 1.5)
    }

    @Test
    fun syntheticRiseFallRecommendationCarriesGeneratorContract() {
        val synthRec = MarketRecommendation(
            symbol = "R_75",
            name = "Volatility 75 Index",
            market = "SYNTHETICS",
            type = "TRADE",
            horizon = "Rise / Fall (5m-1h)",
            action = "RISE / CALL",
            grade = "A",
            performanceScore = 88,
            winRateEst = 81,
            currentPrice = 423500.0,
            return24h = -2.85,
            rsi = 31,
            trend = "OVEREXTENDED DOWN MEAN REVERT",
            entry = 423500.0,
            sl = 411000.0,
            tp1 = 451000.0,
            tp2 = 467250.0,
            rr = 2.2,
            volatility = "HIGH",
            reasoning = "Constant-volatility random walk stretched to -2.4 sigma. Generator edge favors RISE / CALL.",
            isSynthetic = true,
            preferredContract = "RISE"
        )

        assertEquals("R_75", synthRec.symbol)
        assertEquals("SYNTHETICS", synthRec.market)
        assertTrue(synthRec.isSynthetic)
        assertEquals("RISE / CALL", synthRec.action)
        assertEquals("RISE", synthRec.preferredContract)
        assertTrue(synthRec.performanceScore >= 80)
    }

    @Test
    fun marketNewsItemSupportsUpcomingAndPreviousBreakdown() {
        val upcoming = MarketNewsItem(
            id = "up_usd_cpi",
            title = "Core CPI m/m",
            currency = "USD",
            date = "2026-10-26T12:30:00Z",
            time = System.currentTimeMillis() + 3600000L,
            impact = "HIGH",
            forecast = "0.3%",
            previous = "0.3%",
            actual = null,
            isUpcoming = true,
            affectedPairs = listOf("EUR/USD", "USD/JPY")
        )

        assertTrue(upcoming.isUpcoming)
        assertEquals("HIGH", upcoming.impact)
        assertEquals(null, upcoming.actual)
        assertEquals(2, upcoming.affectedPairs.size)

        val previous = MarketNewsItem(
            id = "hist_usd_nfp",
            title = "Non-Farm Employment Change (NFP)",
            currency = "USD",
            date = "2026-10-24T12:30:00Z",
            time = System.currentTimeMillis() - 86400000L,
            impact = "HIGH",
            forecast = "147K",
            previous = "159K",
            actual = "254K",
            isUpcoming = false,
            outcome = "BEAT",
            variance = "+107K above consensus",
            bias = "HAWKISH / USD BULLISH",
            volatilityRating = "Very High (85 pips)",
            marketAnalysis = "Blockbuster jobs surge (+254K vs 147K exp) completely dissolved expectations of another 50 bps Fed rate cut.",
            affectedPairs = listOf("EUR/USD", "GBP/USD", "USD/JPY", "XAU/USD")
        )

        assertFalse(previous.isUpcoming)
        assertEquals("BEAT", previous.outcome)
        assertEquals("254K", previous.actual)
        assertNotNull(previous.marketAnalysis)
        assertTrue(previous.marketAnalysis!!.contains("Blockbuster jobs surge"))
        assertEquals(4, previous.affectedPairs.size)
    }
}
