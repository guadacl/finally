package com.finallyapp.workflow;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.DeployResourceCommandStep1;
import io.camunda.client.api.response.DeploymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deploys every {@code classpath*:bpmn/*.bpmn} (i.e. {@code camunda/bpmn/}) once the app is ready.
 *
 * <p>Deliberately not {@code @Deployment}: that runs during startup and would fail the whole app when
 * Camunda is down. Here deployment runs on a background thread and retries with back-off until it succeeds,
 * so the app starts (and notifications still work via the fallback) without Camunda. Re-deploying an
 * unchanged model is a no-op in Zeebe (no new version).
 */
@Component
@ConditionalOnProperty(name = "finally.workflow.enabled", havingValue = "true", matchIfMissing = true)
public class BpmnDeployer implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BpmnDeployer.class);
    static final String RESOURCE_PATTERN = "classpath*:bpmn/*.bpmn";

    private final ObjectProvider<CamundaClient> clientProvider;
    private final AtomicBoolean deployed = new AtomicBoolean(false);
    private volatile Thread worker;

    public BpmnDeployer(ObjectProvider<CamundaClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void deployInBackground() {
        if (clientProvider.getIfAvailable() == null) {
            log.warn("finally.workflow.enabled=true but no CamundaClient bean; BPMN not deployed");
            return;
        }
        worker = Thread.ofVirtual().name("finally-bpmn-deployer").start(this::deployWithRetry);
    }

    public boolean isDeployed() {
        return deployed.get();
    }

    private void deployWithRetry() {
        Duration delay = Duration.ofSeconds(2);
        int attempt = 0;
        while (!deployed.get() && !Thread.currentThread().isInterrupted()) {
            attempt++;
            try {
                deployOnce();
                return;
            } catch (Exception e) {
                if (attempt == 1 || attempt % 10 == 0) {
                    log.warn("BPMN deployment attempt {} failed ({}); retrying every {}s",
                            attempt, e.toString(), delay.toSeconds());
                }
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            delay = delay.multipliedBy(2).compareTo(Duration.ofSeconds(30)) > 0
                    ? Duration.ofSeconds(30) : delay.multipliedBy(2);
        }
    }

    void deployOnce() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(RESOURCE_PATTERN);
        if (resources.length == 0) {
            log.error("No BPMN resources found at {}", RESOURCE_PATTERN);
            deployed.set(true);
            return;
        }
        DeployResourceCommandStep1 step1 = clientProvider.getObject().newDeployResourceCommand();
        DeployResourceCommandStep1.DeployResourceCommandStep2 step2 = null;
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                byte[] bytes = in.readAllBytes();
                step2 = step2 == null
                        ? step1.addResourceBytes(bytes, resource.getFilename())
                        : step2.addResourceBytes(bytes, resource.getFilename());
            }
        }
        DeploymentEvent event = step2.requestTimeout(Duration.ofSeconds(10)).send().join();
        deployed.set(true);
        event.getProcesses().forEach(p -> log.info("Deployed BPMN process '{}' version {} (key {})",
                p.getBpmnProcessId(), p.getVersion(), p.getProcessDefinitionKey()));
    }

    @Override
    public void destroy() {
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }
}
