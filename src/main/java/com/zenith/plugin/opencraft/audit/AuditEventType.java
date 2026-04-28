package com.zenith.plugin.opencraft.audit;

/**
 * Categorises audit log entries for filtering and analysis.
 */
public enum AuditEventType {
    /** A prompt was received from an authorised user. */
    PROMPT_RECEIVED,
    /** A response was whispered back to the user. */
    RESPONSE_SENT,
    /** An unauthorised user was silently rejected. */
    REQUEST_DENIED,
    /** Rate limit was applied. */
    RATE_LIMITED,
    /** Admin command intent was parsed from LLM output. */
    COMMAND_INTENT_PARSED,
    /** Admin command is awaiting explicit confirmation. */
    COMMAND_PENDING_CONFIRMATION,
    /** Admin command was approved and dispatched. */
    COMMAND_EXECUTED,
    /** Admin command was denied by allowlist or role check. */
    COMMAND_DENIED,
    /** Admin command execution failed at the ZenithProxy level. */
    COMMAND_FAILED,
    /** LLM provider returned an error. */
    PROVIDER_ERROR,
    /** Suspected prompt-injection attempt detected. */
    INJECTION_ATTEMPT,
    /** Plugin update event. */
    UPDATE_EVENT,
    /** A multi-step operation was started. */
    OPERATION_STARTED,
    /** A multi-step operation completed all steps successfully. */
    OPERATION_COMPLETED,
    /** A multi-step operation was cancelled by the user. */
    OPERATION_CANCELLED,
    /** A multi-step operation failed mid-execution. */
    OPERATION_FAILED
}
