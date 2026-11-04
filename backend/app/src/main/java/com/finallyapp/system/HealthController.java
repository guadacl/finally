package com.finallyapp.system;

import com.finallyapp.api.SystemApi;
import com.finallyapp.api.model.Health;
import com.finallyapp.market.MarketDataSource;
import com.finallyapp.persistence.UserProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController implements SystemApi {

    private final UserProfileRepository profiles;
    private final MarketDataSource source;
    private final boolean workflowEnabled;

    public HealthController(UserProfileRepository profiles, MarketDataSource source,
                            @Value("${finally.workflow.enabled:true}") boolean workflowEnabled) {
        this.profiles = profiles;
        this.source = source;
        this.workflowEnabled = workflowEnabled;
    }

    @Override
    public ResponseEntity<Health> getHealth() {
        String db;
        try {
            profiles.ping();
            db = "up";
        } catch (RuntimeException e) {
            db = "down";
        }
        Health h = new Health("ok")
                .database(db)
                .workflow(workflowEnabled ? "enabled" : "disabled")
                .marketData(source.name());
        return ResponseEntity.ok(h);
    }
}
