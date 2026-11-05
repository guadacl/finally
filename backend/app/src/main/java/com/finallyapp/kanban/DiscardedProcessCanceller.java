package com.finallyapp.kanban;

import com.finallyapp.contracts.KanbanWorkflow;
import com.finallyapp.persistence.KanbanDiscardedProcessEntity;
import com.finallyapp.persistence.KanbanDiscardedProcessRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * At startup, cancels the Camunda process instances of duplicate cards that migration V7 removed (one card per
 * ticker per column), silently: no "card deleted" toast or email. Each recorded instance is handled once.
 */
@Component
public class DiscardedProcessCanceller {

    private static final Logger log = LoggerFactory.getLogger(DiscardedProcessCanceller.class);

    private final KanbanDiscardedProcessRepository discarded;
    private final KanbanWorkflow workflow;

    public DiscardedProcessCanceller(KanbanDiscardedProcessRepository discarded, KanbanWorkflow workflow) {
        this.discarded = discarded;
        this.workflow = workflow;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cancelDiscarded() {
        List<KanbanDiscardedProcessEntity> pending = discarded.findAll();
        if (pending.isEmpty()) {
            return;
        }
        log.info("Cancelling {} Camunda process instance(s) of removed duplicate cards", pending.size());
        for (KanbanDiscardedProcessEntity p : pending) {
            try {
                workflow.processDiscarded(p.getProcessInstanceKey());
            } catch (RuntimeException e) {
                log.debug("Could not cancel discarded instance {}: {}", p.getProcessInstanceKey(), e.toString());
            }
        }
        discarded.deleteAll(pending);
    }
}
