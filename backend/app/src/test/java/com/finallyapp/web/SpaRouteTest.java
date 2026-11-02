package com.finallyapp.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.finallyapp.config.Tickers;
import com.finallyapp.config.WebConfigAccess;
import org.junit.jupiter.api.Test;

class SpaRouteTest {

    @Test
    void spaRoutes() {
        assertThat(WebConfigAccess.isSpaRoute("markets")).isTrue();
        assertThat(WebConfigAccess.isSpaRoute("portfolio")).isTrue();
        assertThat(WebConfigAccess.isSpaRoute("watchlists/AAPL")).isTrue();
        assertThat(WebConfigAccess.isSpaRoute("")).isTrue();
        assertThat(WebConfigAccess.isSpaRoute("main-ABC.js")).isFalse();
        assertThat(WebConfigAccess.isSpaRoute("assets/logo.svg")).isFalse();
        assertThat(WebConfigAccess.isSpaRoute("api/unknown")).isFalse();
        assertThat(WebConfigAccess.isSpaRoute("api")).isFalse();
    }

    @Test
    void tickerValidation() {
        assertThat(Tickers.isValid("aapl")).isTrue();
        assertThat(Tickers.isValid("BRK.B")).isTrue();
        assertThat(Tickers.isValid("ABCDEFGHIJ")).isTrue();
        assertThat(Tickers.isValid("ABCDEFGHIJK")).isFalse();
        assertThat(Tickers.isValid("1ABC")).isFalse();
        assertThat(Tickers.isValid("A B")).isFalse();
        assertThat(Tickers.isValid("")).isFalse();
        assertThat(Tickers.normalize("  pypl ")).isEqualTo("PYPL");
    }
}
