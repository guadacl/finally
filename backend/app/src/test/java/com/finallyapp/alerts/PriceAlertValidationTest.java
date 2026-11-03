package com.finallyapp.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finallyapp.api.model.PriceAlertCreate;
import com.finallyapp.error.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PriceAlertValidationTest {

    private static PriceAlertCreate req(String ticker, String upper, String lower, String comment) {
        return new PriceAlertCreate(ticker)
                .upperLimit(upper == null ? null : new BigDecimal(upper))
                .lowerLimit(lower == null ? null : new BigDecimal(lower))
                .comment(comment);
    }

    @Test
    void normalizesTickerLimitsAndComment() {
        var a = PriceAlertService.validate(req(" aapl ", "200", "150.12345", "  watch earnings  "));
        assertThat(a.ticker()).isEqualTo("AAPL");
        assertThat(a.upper()).isEqualByComparingTo("200");
        assertThat(a.lower()).isEqualByComparingTo("150.1235");
        assertThat(a.comment()).isEqualTo("watch earnings");
    }

    @Test
    void oneLimitIsEnoughAndBlankCommentBecomesNull() {
        var upperOnly = PriceAlertService.validate(req("AAPL", "200", null, "   "));
        assertThat(upperOnly.lower()).isNull();
        assertThat(upperOnly.comment()).isNull();
        assertThat(PriceAlertService.validate(req("AAPL", null, "100", null)).upper()).isNull();
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> PriceAlertService.validate(req("AAPL", null, null, "x")))
                .isInstanceOf(ApiException.class).hasMessageContaining("upper limit");
        assertThatThrownBy(() -> PriceAlertService.validate(req("AAPL", "100", "100", null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("below");
        assertThatThrownBy(() -> PriceAlertService.validate(req("AAPL", "0", null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PriceAlertService.validate(req("AAPL", null, "-5", null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PriceAlertService.validate(req("bad ticker!", "10", null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PriceAlertService.validate(req("AAPL", "10", null, "x".repeat(501))))
                .isInstanceOf(ApiException.class).hasMessageContaining("500");
    }
}
