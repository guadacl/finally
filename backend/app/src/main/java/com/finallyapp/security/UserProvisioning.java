package com.finallyapp.security;

import com.finallyapp.market.TickerTracker;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates a new user's starting state the first time they log in: a profile with $10,000 cash and the
 * ten default watchlist tickers. Idempotent and cheap to call on every login.
 */
@Component
public class UserProvisioning {

    private static final Logger log = LoggerFactory.getLogger(UserProvisioning.class);

    public static final List<String> DEFAULT_WATCHLIST =
            List.of("AAPL", "GOOGL", "MSFT", "AMZN", "TSLA", "NVDA", "META", "JPM", "V", "NFLX");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TickerTracker tracker;
    private final Set<String> provisioned = ConcurrentHashMap.newKeySet();

    public UserProvisioning(JdbcClient jdbc, TransactionTemplate tx, TickerTracker tracker) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tracker = tracker;
    }

    /** Ensures the user has a profile; seeds the default watchlist only when the profile is new. */
    public void ensure(String userId) {
        if (userId == null || userId.isBlank() || provisioned.contains(userId)) {
            return;
        }
        Boolean created = tx.execute(status -> {
            int inserted = jdbc.sql("INSERT INTO users_profile (id) VALUES (:u) ON CONFLICT (id) DO NOTHING")
                    .param("u", userId).update();
            if (inserted == 0) {
                return false;
            }
            for (String ticker : DEFAULT_WATCHLIST) {
                jdbc.sql("INSERT INTO watchlist (user_id, ticker) VALUES (:u, :t) ON CONFLICT (user_id, ticker) DO NOTHING")
                        .param("u", userId).param("t", ticker).update();
            }
            return true;
        });
        if (Boolean.TRUE.equals(created)) {
            DEFAULT_WATCHLIST.forEach(tracker::track);
            log.info("Provisioned new user {} with $10,000 and the default watchlist", userId);
        }
        provisioned.add(userId);
    }
}
