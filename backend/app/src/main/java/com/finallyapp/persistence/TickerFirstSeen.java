package com.finallyapp.persistence;

import java.time.OffsetDateTime;

/** JPQL projection: a ticker referenced by a table and when it was first referenced there. */
public record TickerFirstSeen(String ticker, OffsetDateTime firstSeen) {
}
