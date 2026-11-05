package com.finallyapp.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finallyapp.config.FinallyProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Company names per ticker, shown next to the ticker in the tables and used by their search.
 *
 * <p>A built-in list covers the default watchlist and other common US stocks, so names work with the simulator
 * and without any API key. Other tickers are looked up once, from Finnhub's company profile
 * ({@code /stock/profile2}) when {@code FINNHUB_API_KEY} is set, else from Massive's ticker details
 * ({@code /v3/reference/tickers/{ticker}}) when {@code MASSIVE_API_KEY} is set, and cached in memory. A ticker the
 * provider doesn't know is remembered as unknown for a day; a failed call (rate limit, network) is retried after
 * a few minutes.
 */
@Component
public class CompanyNames {

    private static final Logger log = LoggerFactory.getLogger(CompanyNames.class);
    static final String FINNHUB_PROFILE_PATH = "/stock/profile2";
    static final String MASSIVE_DETAILS_PATH = "/v3/reference/tickers/{ticker}";
    static final Duration UNKNOWN_TTL = Duration.ofHours(24);
    static final Duration FAILURE_TTL = Duration.ofMinutes(5);

    static final Map<String, String> BUILT_IN = Map.ofEntries(
            Map.entry("AAPL", "Apple Inc."),
            Map.entry("GOOGL", "Alphabet Inc. (Class A)"),
            Map.entry("GOOG", "Alphabet Inc. (Class C)"),
            Map.entry("MSFT", "Microsoft Corporation"),
            Map.entry("AMZN", "Amazon.com, Inc."),
            Map.entry("TSLA", "Tesla, Inc."),
            Map.entry("NVDA", "NVIDIA Corporation"),
            Map.entry("META", "Meta Platforms, Inc."),
            Map.entry("JPM", "JPMorgan Chase & Co."),
            Map.entry("V", "Visa Inc."),
            Map.entry("NFLX", "Netflix, Inc."),
            Map.entry("AMD", "Advanced Micro Devices, Inc."),
            Map.entry("INTC", "Intel Corporation"),
            Map.entry("AVGO", "Broadcom Inc."),
            Map.entry("ORCL", "Oracle Corporation"),
            Map.entry("CRM", "Salesforce, Inc."),
            Map.entry("ADBE", "Adobe Inc."),
            Map.entry("CSCO", "Cisco Systems, Inc."),
            Map.entry("IBM", "International Business Machines Corporation"),
            Map.entry("QCOM", "QUALCOMM Incorporated"),
            Map.entry("TXN", "Texas Instruments Incorporated"),
            Map.entry("MU", "Micron Technology, Inc."),
            Map.entry("PLTR", "Palantir Technologies Inc."),
            Map.entry("UBER", "Uber Technologies, Inc."),
            Map.entry("SHOP", "Shopify Inc."),
            Map.entry("PYPL", "PayPal Holdings, Inc."),
            Map.entry("SQ", "Block, Inc."),
            Map.entry("XYZ", "Block, Inc."),
            Map.entry("COIN", "Coinbase Global, Inc."),
            Map.entry("SNOW", "Snowflake Inc."),
            Map.entry("SPOT", "Spotify Technology S.A."),
            Map.entry("ABNB", "Airbnb, Inc."),
            Map.entry("DIS", "The Walt Disney Company"),
            Map.entry("BRK.B", "Berkshire Hathaway Inc. (Class B)"),
            Map.entry("MA", "Mastercard Incorporated"),
            Map.entry("BAC", "Bank of America Corporation"),
            Map.entry("WFC", "Wells Fargo & Company"),
            Map.entry("GS", "The Goldman Sachs Group, Inc."),
            Map.entry("MS", "Morgan Stanley"),
            Map.entry("C", "Citigroup Inc."),
            Map.entry("AXP", "American Express Company"),
            Map.entry("WMT", "Walmart Inc."),
            Map.entry("COST", "Costco Wholesale Corporation"),
            Map.entry("HD", "The Home Depot, Inc."),
            Map.entry("NKE", "NIKE, Inc."),
            Map.entry("MCD", "McDonald's Corporation"),
            Map.entry("SBUX", "Starbucks Corporation"),
            Map.entry("KO", "The Coca-Cola Company"),
            Map.entry("PEP", "PepsiCo, Inc."),
            Map.entry("PG", "The Procter & Gamble Company"),
            Map.entry("JNJ", "Johnson & Johnson"),
            Map.entry("PFE", "Pfizer Inc."),
            Map.entry("MRK", "Merck & Co., Inc."),
            Map.entry("LLY", "Eli Lilly and Company"),
            Map.entry("UNH", "UnitedHealth Group Incorporated"),
            Map.entry("ABBV", "AbbVie Inc."),
            Map.entry("XOM", "Exxon Mobil Corporation"),
            Map.entry("CVX", "Chevron Corporation"),
            Map.entry("BA", "The Boeing Company"),
            Map.entry("CAT", "Caterpillar Inc."),
            Map.entry("GE", "GE Aerospace"),
            Map.entry("F", "Ford Motor Company"),
            Map.entry("GM", "General Motors Company"),
            Map.entry("T", "AT&T Inc."),
            Map.entry("VZ", "Verizon Communications Inc."),
            Map.entry("SPY", "SPDR S&P 500 ETF Trust"),
            Map.entry("QQQ", "Invesco QQQ Trust"));

    /** A looked-up name ({@code null} = unknown) and when to look it up again. */
    private record Entry(String name, Instant retryAfter) {
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final RestClient finnhub;
    private final RestClient massive;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public CompanyNames(FinallyProperties props, RestClient.Builder builder, ObjectMapper mapper) {
        this(props.market().finnhubApiKey(), props.market().finnhubBaseUrl(),
                props.market().massiveApiKey(), props.market().massiveBaseUrl(), builder, mapper, Clock.systemUTC());
    }

    CompanyNames(String finnhubKey, String finnhubBaseUrl, String massiveKey, String massiveBaseUrl,
                 RestClient.Builder builder, ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
        this.finnhub = isSet(finnhubKey)
                ? builder.clone().baseUrl(finnhubBaseUrl).defaultHeader("X-Finnhub-Token", finnhubKey.trim()).build()
                : null;
        this.massive = isSet(massiveKey)
                ? builder.clone().baseUrl(massiveBaseUrl).defaultHeader("Authorization", "Bearer " + massiveKey.trim()).build()
                : null;
    }

    /** The cached or built-in name, without calling any provider. */
    public Optional<String> cached(String ticker) {
        String t = ticker.trim().toUpperCase(Locale.ROOT);
        Entry e = cache.get(t);
        if (e != null && e.name() != null) {
            return Optional.of(e.name());
        }
        return Optional.ofNullable(BUILT_IN.get(t));
    }

    /** The company name, looking it up at the provider when it is neither built in nor cached. */
    public Optional<String> lookup(String ticker) {
        String t = ticker.trim().toUpperCase(Locale.ROOT);
        Optional<String> known = cached(t);
        if (known.isPresent() || (finnhub == null && massive == null)) {
            return known;
        }
        Entry e = cache.get(t);
        Instant now = clock.instant();
        if (e != null && now.isBefore(e.retryAfter())) {
            return Optional.empty(); // known to be unknown, or a recent failure
        }
        try {
            String name = finnhub != null ? fetchFinnhub(t) : fetchMassive(t);
            cache.put(t, new Entry(name, name == null ? now.plus(UNKNOWN_TTL) : Instant.MAX));
            return Optional.ofNullable(name);
        } catch (HttpClientErrorException.NotFound ex) {
            cache.put(t, new Entry(null, now.plus(UNKNOWN_TTL)));
            return Optional.empty();
        } catch (Exception ex) {
            log.debug("Company name lookup failed for {}: {}", t, ex.toString());
            cache.put(t, new Entry(null, now.plus(FAILURE_TTL)));
            return Optional.empty();
        }
    }

    String fetchFinnhub(String ticker) throws Exception {
        String body = finnhub.get().uri(u -> u.path(FINNHUB_PROFILE_PATH).queryParam("symbol", ticker).build())
                .retrieve().body(String.class);
        return text(body == null ? null : mapper.readTree(body).path("name"));
    }

    String fetchMassive(String ticker) throws Exception {
        String body = massive.get().uri(MASSIVE_DETAILS_PATH, ticker).retrieve().body(String.class);
        return text(body == null ? null : mapper.readTree(body).path("results").path("name"));
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText().trim();
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }
}
