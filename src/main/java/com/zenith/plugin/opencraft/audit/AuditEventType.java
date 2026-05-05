package com.zenith.plugin.opencraft.audit;

public enum AuditEventType {
        PROMPT_RECEIVED,
        RESPONSE_SENT,
        REQUEST_DENIED,
        RATE_LIMITED,
        COMMAND_INTENT_PARSED,
        COMMAND_PENDING_CONFIRMATION,
        COMMAND_EXECUTED,
        COMMAND_DENIED,
        COMMAND_FAILED,
        PROVIDER_ERROR,
        INJECTION_ATTEMPT,
        UPDATE_EVENT,
        OPERATION_STARTED,
        OPERATION_COMPLETED,
        OPERATION_CANCELLED,
        OPERATION_FAILED
}
