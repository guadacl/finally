package com.finallyapp.portfolio;

import com.finallyapp.api.PortfolioApi;
import com.finallyapp.api.model.Portfolio;
import com.finallyapp.api.model.PortfolioSnapshot;
import com.finallyapp.api.model.TradeRequest;
import com.finallyapp.api.model.TradeResult;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PortfolioController implements PortfolioApi {

    private final PortfolioService service;

    public PortfolioController(PortfolioService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<Portfolio> getPortfolio() {
        return ResponseEntity.ok(service.getPortfolio());
    }

    @Override
    public ResponseEntity<TradeResult> executeTrade(TradeRequest req) {
        PortfolioService.TradeOutcome o = service.executeTrade(req.getTicker(), req.getSide(), req.getQuantity());
        return ResponseEntity.ok(new TradeResult(o.trade(), o.portfolio()));
    }

    @Override
    public ResponseEntity<List<PortfolioSnapshot>> getPortfolioHistory() {
        return ResponseEntity.ok(service.history());
    }
}
