package com.zenith.plugin.opencraft.intent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.zenith.plugin.opencraft.plan.OperationalPlan;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the raw string content from an OpenCraftResponse into one of several
 * structured response types.
 *
 * Supported response types (admin):
 *   {"type": "response",        "content": "..."}
 *   {"type": "command_intent",  "command_id": "...", "arguments": {}, "explanation": "..."}
 *   {"type": "plan",            "steps": [...], "risk": "LOW|MEDIUM|HIGH",
 *                               "confirmation_required": bool, "cost_estimate": int,
 *                               "estimated_duration": "...", "reasoning": "..."}
 *   {"type": "refusal",         "reason": "..."}
 *   {"type": "clarification",   "message": "..."}
 *
 * Member users receive only "response" type.
 *
 * All non-JSON or unrecognised output falls back to PlainResponse.
 * This class never throws; malformed input always produces a safe fallback.
 */
public final class IntentParser {

    private static final String TYPE_RESPONSE       = "response";
    private static final String TYPE_COMMAND_INTENT = "command_intent";
    private static final String TYPE_PLAN           = "plan";
    private static final String TYPE_REFUSAL        = "refusal";
    private static final String TYPE_CLARIFICATION  = "clarification";

    private final Gson            gson;
    private final ComponentLogger logger;

    public IntentParser(final ComponentLogger logger) {
        this.gson   = new Gson();
        this.logger = logger;
    }

    /** Sealed hierarchy of parse results. */
    public sealed interface ParsedResponse
        permits PlainResponse, CommandIntentResponse, PlanResponse,
                RefusalResponse, ClarificationResponse {}

    public record PlainResponse(String content) implements ParsedResponse {}
    public record CommandIntentResponse(CommandIntent intent) implements ParsedResponse {}
    public record PlanResponse(OperationalPlan plan) implements ParsedResponse {}
    public record RefusalResponse(String reason) implements ParsedResponse {}
    public record ClarificationResponse(String message) implements ParsedResponse {}

    /**
     * Parse the raw LLM output. Never throws; malformed input returns a
     * PlainResponse with a safe fallback message.
     */
    public ParsedResponse parse(final String raw, final String requestId) {
        if (raw == null || raw.isBlank()) {
            return new PlainResponse("(no response)");
        }

        final String trimmed = raw.strip();
        if (!trimmed.startsWith("{")) {
            logger.debug("[OpenCraft] req={} LLM did not return JSON; treating as plain text.", requestId);
            return new PlainResponse(sanitise(trimmed));
        }

        try {
            final JsonObject json = gson.fromJson(trimmed, JsonObject.class);
            if (json == null) return new PlainResponse("(empty)");

            final String type = json.has("type") ? json.get("type").getAsString() : TYPE_RESPONSE;

            return switch (type) {
                case TYPE_COMMAND_INTENT -> parseCommandIntent(json, requestId);
                case TYPE_PLAN           -> parsePlan(json, requestId);
                case TYPE_REFUSAL        -> parseRefusal(json, requestId);
                case TYPE_CLARIFICATION  -> parseClarification(json, requestId);
                default                  -> parsePlainResponse(json, requestId);
            };

        } catch (final JsonSyntaxException e) {
            logger.warn("[OpenCraft] req={} Malformed JSON from model: {}", requestId, e.getMessage());
            return new PlainResponse("I encountered an issue processing your request.");
        }
    }

    private ParsedResponse parseCommandIntent(final JsonObject json, final String requestId) {
        if (!json.has("command_id")) {
            logger.warn("[OpenCraft] req={} command_intent missing 'command_id'.", requestId);
            return new PlainResponse("I encountered an issue processing your request.");
        }

        final String commandId   = json.get("command_id").getAsString().strip();
        final String explanation = json.has("explanation")
            ? sanitise(json.get("explanation").getAsString()) : "";

        final Map<String, String> args = new HashMap<>();
        if (json.has("arguments") && json.get("arguments").isJsonObject()) {
            json.getAsJsonObject("arguments").entrySet().forEach(e ->
                args.put(e.getKey(), e.getValue().isJsonNull() ? "" : e.getValue().getAsString())
            );
        }

        return new CommandIntentResponse(new CommandIntent(commandId, Map.copyOf(args), explanation));
    }

    private ParsedResponse parsePlan(final JsonObject json, final String requestId) {
        if (!json.has("steps") || !json.get("steps").isJsonArray()) {
            logger.warn("[OpenCraft] req={} plan missing 'steps' array.", requestId);
            return new PlainResponse("I encountered an issue processing your request.");
        }

        final JsonArray stepsArray = json.getAsJsonArray("steps");
        final List<CommandIntent> steps = new ArrayList<>(stepsArray.size());

        for (final JsonElement el : stepsArray) {
            if (!el.isJsonObject()) continue;
            final JsonObject stepObj = el.getAsJsonObject();
            if (!stepObj.has("command_id")) continue;

            final String commandId   = stepObj.get("command_id").getAsString().strip();
            final String explanation = stepObj.has("explanation")
                ? sanitise(stepObj.get("explanation").getAsString()) : "";

            final Map<String, String> args = new HashMap<>();
            if (stepObj.has("arguments") && stepObj.get("arguments").isJsonObject()) {
                stepObj.getAsJsonObject("arguments").entrySet().forEach(e ->
                    args.put(e.getKey(), e.getValue().isJsonNull() ? "" : e.getValue().getAsString())
                );
            }
            steps.add(new CommandIntent(commandId, Map.copyOf(args), explanation));
        }

        if (steps.isEmpty()) {
            logger.warn("[OpenCraft] req={} plan has no valid steps.", requestId);
            return new PlainResponse("I could not form a valid plan for that request.");
        }

        final String  risk                = json.has("risk")
            ? json.get("risk").getAsString().toUpperCase() : "MEDIUM";
        final boolean confirmationRequired = json.has("confirmation_required")
            && json.get("confirmation_required").getAsBoolean();
        final int     costEstimate        = json.has("cost_estimate")
            ? json.get("cost_estimate").getAsInt() : 0;
        final String  estimatedDuration   = json.has("estimated_duration")
            ? sanitise(json.get("estimated_duration").getAsString()) : "unknown";
        final String  reasoning           = json.has("reasoning")
            ? sanitise(json.get("reasoning").getAsString()) : "";

        return new PlanResponse(new OperationalPlan(
            List.copyOf(steps), risk, confirmationRequired,
            costEstimate, estimatedDuration, reasoning
        ));
    }

    private ParsedResponse parseRefusal(final JsonObject json, final String requestId) {
        final String reason = json.has("reason")
            ? sanitise(json.get("reason").getAsString())
            : "I cannot help with that.";
        logger.debug("[OpenCraft] req={} LLM issued refusal: {}", requestId, reason);
        return new RefusalResponse(reason);
    }

    private ParsedResponse parseClarification(final JsonObject json, final String requestId) {
        final String message = json.has("message")
            ? sanitise(json.get("message").getAsString())
            : "Could you clarify your request?";
        return new ClarificationResponse(message);
    }

    private ParsedResponse parsePlainResponse(final JsonObject json, final String requestId) {
        if (!json.has("content")) {
            logger.debug("[OpenCraft] req={} response JSON missing 'content' field.", requestId);
            return new PlainResponse("(empty)");
        }
        return new PlainResponse(sanitise(json.get("content").getAsString()));
    }

    /**
     * Strip Minecraft colour codes and control characters from model output
     * to prevent chat injection or impersonation.
     */
    private static String sanitise(final String s) {
        if (s == null) return "";
        return s.replaceAll("§[0-9a-fk-or]", "").trim();
    }
}
