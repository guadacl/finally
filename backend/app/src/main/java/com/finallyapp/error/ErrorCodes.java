package com.finallyapp.error;

public final class ErrorCodes {
    public static final String INSUFFICIENT_CASH = "INSUFFICIENT_CASH";
    public static final String INSUFFICIENT_SHARES = "INSUFFICIENT_SHARES";
    public static final String UNKNOWN_TICKER = "UNKNOWN_TICKER";
    public static final String INVALID_QUANTITY = "INVALID_QUANTITY";
    public static final String INVALID_TICKER = "INVALID_TICKER";
    public static final String INVALID_PRICE = "INVALID_PRICE";
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String TICKER_EXISTS = "TICKER_EXISTS";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String LLM_ERROR = "LLM_ERROR";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCodes() {
    }
}
