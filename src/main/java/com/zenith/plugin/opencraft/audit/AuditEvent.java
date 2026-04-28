package com.zenith.plugin.opencraft.audit;

import com.zenith.plugin.opencraft.auth.UserIdentity;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Immutable audit log entry.
 * Must not contain secrets (API keys, webhook URLs, raw system prompts).
 */
public record AuditEvent(
    Instant        timestamp,
    String         requestId,
    AuditEventType type,
    /** Redacted label, e.g. "Notch (uuid)". Null for system events. */
    @Nullable String userLabel,
    @Nullable String userRole,
    /** Truncated prompt excerpt — no longer than 200 chars. */
    @Nullable String promptExcerpt,
    /** Truncated response or result excerpt. */
    @Nullable String resultExcerpt,
    /** Command ID if this is a command-related event. */
    @Nullable String commandId,
    /** Additional detail (denial reason, provider name, etc.). */
    @Nullable String detail
) {

    // ── Factory methods ───────────────────────────────────────────────────────

    public static AuditEvent promptReceived(final String requestId,
                                            final UserIdentity identity,
                                            final String promptExcerpt) {
        return new AuditEvent(
            Instant.now(), requestId, AuditEventType.PROMPT_RECEIVED,
            identity.auditLabel(), identity.role().name(),
            excerpt(promptExcerpt), null, null, null);
    }

    public static AuditEvent responseSent(final String requestId,
                                          final UserIdentity identity,
                                          final String responseExcerpt,
                                          final String providerName) {
        return new AuditEvent(
            Instant.now(), requestId, AuditEventType.RESPONSE_SENT,
            identity.auditLabel(), identity.role().name(),
            null, excerpt(responseExcerpt), null, providerName);
    }

    public static AuditEvent requestDenied(final String requestId,
                                           @Nullable final String username,
                                           final String reason) {
        return new AuditEvent(
            Instant.now(), requestId, AuditEventType.REQUEST_DENIED,
            username, null, null, null, null, reason);
    }

    public static AuditEvent rateLimited(final String requestId,
                                         final UserIdentity identity,
                                         final String reason) {
        return new AuditEvent(
            Instant.now(), requestId, AuditEventType.RATE_LIMITED,
            identity.auditLabel(), identity.role().name(), null, null, null, reason);
    }

    public static AuditEvent commandDenied(final String requestId,
                                           final UserIdentity identity,
                                           final String reason,
                                           final String commandId) {
        return new AuditEvent(
            Instant.now(), requestId, AuditEventType.COMMAND_DENIED,
            identity.auditLabel(), identity.role().name(), null, null, commandId, reason);
    }

    public static AuditEvent commandPendingConfirmation(final String requestId,
                                                         final UserIdentity identity,
                                                         final String commandId) {
        return new AuditEvent(
            Instant.now(), requestId, AuditEventType.COMMAND_PENDING_CONFIRMATION,
            identity.auditLabel(), identity.role().name(), null, null, commandId, null);
    }

    public static AuditEvent commandExecuted(final String requestId,
                                              final UserIdentity identity,
                                              final String commandId,
                                              final String resultExcerpt) {
        return new AuditEvent(
            Instant.now(), requestId, AuditEventType.COMMAND_EXECUTED,
            identity.auditLabel(), identity.role().name(), null, excerpt(resultExcerpt), commandId, null);
    }

    public static AuditEvent commandFailed(final String requestId,
                                            final UserIdentity identity,
                                            final String commandId,
                                            final String reason) {
        return new AuditEvent(
            Instant.now(), requestId, AuditEventType.COMMAND_FAILED,
            identity.auditLabel(), identity.role().name(), null, null, commandId, reason);
    }

    public static AuditEvent providerError(final String requestId,
                                            final UserIdentity identity,
                                            final String reason) {
        return new AuditEvent(
            Instant.now(), requestId, AuditEventType.PROVIDER_ERROR,
            identity.auditLabel(), identity.role().name(), null, null, null, reason);
    }

    public static AuditEvent operationStarted(final String requestId,
                                               final UserIdentity identity,
                                               final int stepCount,
                                               final String risk) {
        return new AuditEvent(
            Instant.now(), requestId, AuditEventType.OPERATION_STARTED,
            identity.auditLabel(), identity.role().name(),
            null, null, null, "steps=" + stepCount + " risk=" + risk);
    }

    public static AuditEvent operationCompleted(final String requestId,
                                                 final UserIdentity identity,
                                                 final int stepsCompleted) {
        return new AuditEvent(
            Instant.now(), requestId, AuditEventType.OPERATION_COMPLETED,
            identity.auditLabel(), identity.role().name(),
            null, null, null, "steps=" + stepsCompleted);
    }

    public static AuditEvent operationCancelled(final String requestId,
                                                  final UserIdentity identity) {
        return new AuditEvent(
            Instant.now(), requestId, AuditEventType.OPERATION_CANCELLED,
            identity.auditLabel(), identity.role().name(),
            null, null, null, null);
    }

    public static AuditEvent operationFailed(final String requestId,
                                              final UserIdentity identity,
                                              final String commandId,
                                              final String reason) {
        return new AuditEvent(
            Instant.now(), requestId, AuditEventType.OPERATION_FAILED,
            identity.auditLabel(), identity.role().name(),
            null, null, commandId, reason);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static String excerpt(final String s) {
        if (s == null) return null;
        final String clean = s.replaceAll("[\r\n]+", " ").strip();
        return clean.length() > 200 ? clean.substring(0, 200) + "…" : clean;
    }
}
