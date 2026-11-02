package com.finallyapp.config;

import com.finallyapp.error.ApiException;
import com.finallyapp.error.ErrorCodes;
import java.util.Locale;
import java.util.regex.Pattern;

/** Ticker normalization and validation shared by all features. */
public final class Tickers {

    private static final Pattern VALID = Pattern.compile("^[A-Z][A-Z0-9.\\-]{0,9}$");

    private Tickers() {
    }

    /** Trims and upper-cases; throws INVALID_TICKER (400) when the result is not a valid symbol. */
    public static String normalize(String raw) {
        String t = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID.matcher(t).matches()) {
            throw ApiException.badRequest(ErrorCodes.INVALID_TICKER,
                    "Invalid ticker symbol: \"" + (raw == null ? "" : raw.trim()) + "\"");
        }
        return t;
    }

    public static boolean isValid(String raw) {
        return raw != null && VALID.matcher(raw.trim().toUpperCase(Locale.ROOT)).matches();
    }
}
