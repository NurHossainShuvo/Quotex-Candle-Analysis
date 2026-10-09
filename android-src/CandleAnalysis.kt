package com.example.quotexanalysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Candle(val open: Double, val high: Double, val low: Double, val close: Double) {
    val body get() = abs(close - open)
    val range get() = high - low
    val upperWick get() = high - max(open, close)
    val lowerWick get() = min(open, close) - low
    val bullish get() = close > open
    val bearish get() = close < open
}

object CandleAnalysis {
    fun analyze(raw: String): String {
        val candles = raw.lines().mapNotNull { line ->
            val s = line.trim()
            if (s.isBlank() || s.startsWith("#")) null else {
                val p = s.split(",", ";", " ", "\t").filter { it.isNotBlank() }
                if (p.size != 4) throwIfBad(line) else {
                    val v = p.mapNotNull { it.toDoubleOrNull() }
                    if (v.size != 4) throwIfBad(line)
                    else {
                        val c = Candle(v[0], v[1], v[2], v[3])
                        if (c.high < max(c.open, c.close) || c.low > min(c.open, c.close) ||
                            c.high < c.low) throwIfBad(line)
                        c
                    }
                }
            }
        }
        if (candles.size < 5) return "কমপক্ষে ৫টি সঠিক OHLC candle দিন।"
        val closes = candles.map { it.close }
        val last = candles.last()
        val ema9 = ema(closes.takeLast(60), 9)
        val ema21 = ema(closes.takeLast(100), 21)
        val rsi = rsi(closes, 14)
        val recent = candles.takeLast(min(20, candles.size))
        val support = recent.minOf { it.low }
        val resistance = recent.maxOf { it.high }
        val avgRange = recent.map { it.range }.average()
        val trend = when {
            ema9 > ema21 -> "EMA bias: UP"
            ema9 < ema21 -> "EMA bias: DOWN"
            else -> "EMA bias: NEUTRAL"
        }
        val rsiText = when {
            rsi >= 70 -> "RSI overbought zone"
            rsi <= 30 -> "RSI oversold zone"
            else -> "RSI neutral zone"
        }
        val previous = candles.getOrNull(candles.lastIndex - 1)
        val candleText = when {
            last.range <= 0 -> "Last candle invalid/flat"
            last.lowerWick > last.body * 2 && last.upperWick < last.body ->
                "Possible hammer-like candle (context required)"
            last.upperWick > last.body * 2 && last.lowerWick < last.body ->
                "Possible shooting-star-like candle (context required)"
            previous != null && previous.bearish && last.bullish &&
                last.open <= previous.close && last.close >= previous.open ->
                "Possible bullish engulfing"
            previous != null && previous.bullish && last.bearish &&
                last.open >= previous.close && last.close <= previous.open ->
                "Possible bearish engulfing"
            else -> if (last.bullish) "Last candle bullish" else if (last.bearish) "Last candle bearish" else "Last candle doji/flat"
        }
        val nearSupport = abs(last.close - support) <= max(avgRange * 0.25, 1e-9)
        val nearResistance = abs(resistance - last.close) <= max(avgRange * 0.25, 1e-9)
        val bodyPct = if (last.range > 0) last.body / last.range * 100 else 0.0
        val upperPct = if (last.range > 0) last.upperWick / last.range * 100 else 0.0
        val lowerPct = if (last.range > 0) last.lowerWick / last.range * 100 else 0.0
        val candleBias = when {
            last.bullish && last.body > last.upperWick -> 1
            last.bearish && last.body > last.lowerWick -> -1
            last.lowerWick > last.body * 1.5 -> 1
            last.upperWick > last.body * 1.5 -> -1
            else -> 0
        }
        var score = 0
        if (ema9 > ema21) score += 2 else if (ema9 < ema21) score -= 2
        if (rsi in 50.0..69.9) score++ else if (rsi >= 30.0 && rsi < 50.0) score--
        score += candleBias
        if (nearSupport) score++
        if (nearResistance) score--
        val direction = if (score >= 0) "UP" else "DOWN"
        val strength = when {
            abs(score) >= 4 -> "Strong rule-based bias"
            abs(score) >= 2 -> "Moderate rule-based bias"
            else -> "Weak rule-based bias — direction shown by score"
        }
        val wickText = when {
            upperPct > 55 -> "Long upper wick: possible rejection from higher prices"
            lowerPct > 55 -> "Long lower wick: possible rejection from lower prices"
            bodyPct > 70 -> "Large body / momentum candle"
            else -> "No dominant wick pattern"
        }
        return """
            Candles parsed: ${candles.size}
            Last candle OHLC: O ${fmt(last.open)} | H ${fmt(last.high)} | L ${fmt(last.low)} | C ${fmt(last.close)}
            Candle type: $candleText
            Body: ${fmt(last.body)} (${fmt(bodyPct)}% of range)
            Upper wick: ${fmt(last.upperWick)} (${fmt(upperPct)}% of range)
            Lower wick: ${fmt(last.lowerWick)} (${fmt(lowerPct)}% of range)
            Wick reading: $wickText

            EMA 9: ${fmt(ema9)} | EMA 21: ${fmt(ema21)}
            $trend
            RSI 14: ${fmt(rsi)} — $rsiText
            Recent support (last ${recent.size}): ${fmt(support)}
            Recent resistance (last ${recent.size}): ${fmt(resistance)}
            Average recent range: ${fmt(avgRange)}

            NEXT CANDLE BIAS: $direction
            Signal strength: $strength
            Score: $score (heuristic, not a probability)

            This is not a guaranteed prediction. Screen capture does not extract real OHLC; enter verified candle data for these calculations.
        """.trimIndent()
    }

    private fun throwIfBad(line: String): Nothing =
        throw IllegalArgumentException("Invalid OHLC row: $line. Format: open,high,low,close")

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return Double.NaN
        val alpha = 2.0 / (period + 1.0)
        var result = values.first()
        for (v in values.drop(1)) result = alpha * v + (1 - alpha) * result
        return result
    }

    private fun rsi(closes: List<Double>, period: Int): Double {
        if (closes.size <= period) return 50.0
        var gains = 0.0
        var losses = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d >= 0) gains += d else losses -= d
        }
        var avgGain = gains / period
        var avgLoss = losses / period
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            avgGain = (avgGain * (period - 1) + max(d, 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + max(-d, 0.0)) / period
        }
        if (avgLoss == 0.0) return if (avgGain == 0.0) 50.0 else 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    private fun fmt(v: Double) = if (v.isFinite()) "%.6f".format(java.util.Locale.US, v) else "n/a"
}