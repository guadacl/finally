package com.finallyapp.market;

/** Called synchronously on the market data thread for every price written to the {@link PriceCache}. Keep it fast. */
@FunctionalInterface
public interface PriceListener {
    void onPrice(PriceTick tick);
}
