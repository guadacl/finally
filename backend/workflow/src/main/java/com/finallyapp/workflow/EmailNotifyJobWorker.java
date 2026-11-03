package com.finallyapp.workflow;

import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationEmailer;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Handles every {@code email-notify} service task of the {@code kanban-card} process (card created, card deleted,
 * target price hit; each runs after the matching toast task): builds the {@link Notification} from the job
 * variables and the {@code notificationType} header and hands it to {@link NotificationEmailer}.
 *
 * <p>The job is <em>always</em> completed: a missing address, a down MCP server or an SMTP error is logged by
 * the emailer and never fails or retries the job (no incidents, no retry storms). Re-delivered jobs keep the
 * same notification id (derived from the job key), which the emailer uses to de-duplicate.
 */
@Component
@ConditionalOnProperty(name = "finally.workflow.enabled", havingValue = "true", matchIfMissing = true)
public class EmailNotifyJobWorker {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifyJobWorker.class);

    private final ObjectProvider<NotificationEmailer> emailer;

    public EmailNotifyJobWorker(ObjectProvider<NotificationEmailer> emailer) {
        this.emailer = emailer;
    }

    @JobWorker(type = KanbanProcess.JOB_TYPE_EMAIL, fetchAllVariables = true, autoComplete = true)
    public void handle(ActivatedJob job) {
        Notification notification;
        try {
            notification = NotifyJobWorker.toNotification(job, "email-notify:");
        } catch (Exception e) {
            log.warn("Ignoring malformed email-notify job {}: {}", job.getKey(), e.toString());
            return;
        }
        if (!NotificationEmailer.isEmailed(notification.type())) {
            log.warn("email-notify job {} has type {}; it is not emailed", job.getKey(), notification.type());
            return;
        }
        NotificationEmailer target = emailer.getIfAvailable();
        if (target == null) {
            log.info("No NotificationEmailer bean; completing email-notify job {} without email", job.getKey());
            return;
        }
        try {
            target.send(notification);
        } catch (Exception e) {
            log.warn("Email for job {} failed: {}", job.getKey(), e.toString());
        }
    }
}
