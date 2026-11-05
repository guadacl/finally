package com.finallyapp.alerts;

import com.finallyapp.api.AlertsApi;
import com.finallyapp.api.model.PriceAlert;
import com.finallyapp.api.model.PriceAlertCreate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PriceAlertController implements AlertsApi {

    private final PriceAlertService service;

    public PriceAlertController(PriceAlertService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<List<PriceAlert>> getPriceAlerts() {
        return ResponseEntity.ok(service.list());
    }

    @Override
    public ResponseEntity<PriceAlert> createPriceAlert(PriceAlertCreate req) {
        PriceAlertService.Upserted r = service.create(req);
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r.alert());
    }

    @Override
    public ResponseEntity<Void> deletePriceAlert(UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
