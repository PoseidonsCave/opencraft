package com.zenith.plugin.opencraft.intent;

import org.jspecify.annotations.Nullable;

public record ExecutionResult(
    Status  status,
        String  message,
        @Nullable PendingConfirmation pending
) {
    public enum Status {
        SUCCESS,
        NEEDS_CONFIRMATION,
        DENIED,
        FAILED,
        EXPIRED
    }

    public static ExecutionResult success(final String message) {
        return new ExecutionResult(Status.SUCCESS, message, null);
    }

    public static ExecutionResult needsConfirmation(final PendingConfirmation pending,
                                                    final String promptMessage) {
        return new ExecutionResult(Status.NEEDS_CONFIRMATION, promptMessage, pending);
    }

    public static ExecutionResult denied(final String reason) {
        return new ExecutionResult(Status.DENIED, reason, null);
    }

    public static ExecutionResult failed(final String reason) {
        return new ExecutionResult(Status.FAILED, reason, null);
    }

    public static ExecutionResult expired() {
        return new ExecutionResult(Status.EXPIRED, "Confirmation expired. Please re-submit the request.", null);
    }

    public boolean isSuccess()           { return status == Status.SUCCESS; }
    public boolean needsConfirmation()   { return status == Status.NEEDS_CONFIRMATION; }
}
