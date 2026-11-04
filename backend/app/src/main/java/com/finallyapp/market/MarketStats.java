package com.finallyapp.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * The per-ticker market columns shown in the Alerts page tables (price alerts and Support / Resistance cards):
 * day low / high, last-session volume and RSI(14). Values are null when not (yet) known.
 */
@Component
public class MarketStats {

    /** One ticker's columns; {@code price} is the live price the RSI was computed with. */
    public record Stats(BigDecimal price, BigDecimal dayHigh, BigDecimal dayLow, Long lastSessionVolume,
                        LocalDate lastSessionDate, BigDecimal rsi) {
    }

    private final PriceCache cache;
    private final DayRangeCache dayRanges;
    private final LastSessionVolumes volumes;
    private final DailyCloses dailyCloses;

    public MarketStats(PriceCache cache, DayRangeCache dayRanges, LastSessionVolumes volumes, DailyCloses dailyCloses) {
        this.cache = cache;
        this.dayRanges = dayRanges;
        this.volumes = volumes;
        this.dailyCloses = dailyCloses;
    }

    public Stats of(String ticker) {
        BigDecimal price = cache.price(ticker);
        BigDecimal high = null;
        BigDecimal low = null;
        DayRangeCache.DayRange range = dayRanges.get(ticker).orElse(null);
        if (range != null) {
            high = range.high();
            low = range.low();
        }
        LastSessionVolumes.SessionVolume v = volumes.get(ticker).orElse(null);
        BigDecimal rsi = null;
        var r = dailyCloses.rsi(ticker, price);
        if (r.isPresent()) {
            rsi = BigDecimal.valueOf(r.getAsDouble()).setScale(1, RoundingMode.HALF_UP);
        }
        return new Stats(price, high, low, v == null ? null : v.volume(), v == null ? null : v.session(), rsi);
    }
}
