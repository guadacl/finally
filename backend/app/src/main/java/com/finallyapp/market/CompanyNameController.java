package com.finallyapp.market;

import com.finallyapp.api.ReferenceApi;
import com.finallyapp.api.model.CompanyName;
import com.finallyapp.config.Tickers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CompanyNameController implements ReferenceApi {

    static final int MAX_TICKERS = 100;
    /** Provider lookups per request, so one call never waits on many slow lookups; the rest come back null. */
    static final int MAX_LOOKUPS = 10;

    private final CompanyNames names;

    public CompanyNameController(CompanyNames names) {
        this.names = names;
    }

    @Override
    public ResponseEntity<List<CompanyName>> getCompanyNames(String tickers) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String raw : Arrays.asList((tickers == null ? "" : tickers).split(","))) {
            if (Tickers.isValid(raw) && wanted.size() < MAX_TICKERS) {
                wanted.add(raw.trim().toUpperCase(Locale.ROOT));
            }
        }
        List<CompanyName> out = new ArrayList<>(wanted.size());
        int lookups = 0;
        for (String t : wanted) {
            String name = names.cached(t).orElse(null);
            if (name == null && lookups < MAX_LOOKUPS) {
                lookups++;
                name = names.lookup(t).orElse(null);
            }
            out.add(new CompanyName(t).name(name));
        }
        return ResponseEntity.ok(out);
    }
}
