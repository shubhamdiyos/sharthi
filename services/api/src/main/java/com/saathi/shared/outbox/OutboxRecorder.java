package com.saathi.shared.outbox;

/**
 * Records a fact the platform intends to deliver (§14.5), inside the transaction that produced it.
 *
 * <p>Nothing here sends anything. The whole value of the row is that the intent survives a crash:
 * a response that says "order placed" and a notification that never left because the process died
 * between the commit and the mailer is how a customer is left waiting for a confirmation they were
 * told to expect. The drainer is a separate job and is allowed to retry.
 */
public interface OutboxRecorder {

    void record(String tenantId, String aggregateType, String aggregateId, String eventType,
                String payloadJson);
}
