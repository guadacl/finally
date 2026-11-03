package com.finallyapp.workflow;

import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationSms;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Handles every {@code sms-notify} service task of the {@code kanban-card} process (card created, card deleted,
 * target price hit; each runs in parallel with the matching {@code email-notify} task): builds the
 * {@link Notification} from the job variables and the {@code notificationType} header and hands it to
 * {@link NotificationSms}.
 *
 * <p>The job is <em>always</em> completed: a missing phone number, a down MCP server or a provider error is
 * logged by the sender and never fails or retries the job. Re-delivered jobs keep the same notification id
 * (derived from the job key and the {@code sms-notify} channel), which the sender uses to de-duplicate.
 */
@Component
@ConditionalOnProperty(name = "finally.workflow.enabled", havingValue = "true", matchIfMissing = true)
public class SmsNotifyJobWorker {

    private static final Logger log = LoggerFactory.getLogger(SmsNotifyJobWorker.class);
    static final String ID_PREFIX = "sms-notify:";

    private final ObjectProvider<NotificationSms> sms;

    public SmsNotifyJobWorker(ObjectProvider<NotificationSms> sms) {
        this.sms = sms;
    }

    @JobWorker(type = KanbanProcess.JOB_TYPE_SMS, fetchAllVariables = true, autoComplete = true)
    public void handle(ActivatedJob job) {
        Notification notification;
        try {
            notification = NotifyJobWorker.toNotification(job, ID_PREFIX);
        } catch (Exception e) {
            log.warn("Ignoring malformed sms-notify job {}: {}", job.getKey(), e.toString());
            return;
        }
        if (!NotificationSms.isSent(notification.type())) {
            log.warn("sms-notify job {} has type {}; it is not sent as SMS", job.getKey(), notification.type());
            return;
        }
        NotificationSms target = sms.getIfAvailable();
        if (target == null) {
            log.info("No NotificationSms bean; completing sms-notify job {} without SMS", job.getKey());
            return;
        }
        try {
            target.send(notification);
        } catch (Exception e) {
            log.warn("SMS for job {} failed: {}", job.getKey(), e.toString());
        }
    }
}
