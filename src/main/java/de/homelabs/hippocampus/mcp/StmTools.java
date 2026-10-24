package de.homelabs.hippocampus.mcp;

import java.util.List;
import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import de.homelabs.hippocampus.stm.StmEvent;
import de.homelabs.hippocampus.stm.StmKind;
import de.homelabs.hippocampus.stm.StmService;
import io.modelcontextprotocol.server.McpSyncServerExchange;

/**
 * Short-term memory tools: store events as they are, correct them via update,
 * find by keyword, list the most recent. No embeddings, no transformation
 * (ADR 0002).
 */
@Component
public class StmTools {

    private final StmService service;

    public StmTools(StmService service) {
        this.service = service;
    }

    @McpTool(description = "Personal short-term memory for the user: facts, tasks, notes, decisions — project knowledge and everyday knowledge. "
            + "Store only when the user explicitly asks to remember something, or when the content is a durable fact about the user (address, appointment, preference, decision) — even without an explicit request. "
            + "Self-directed musings ('I should write this down', 'do I need to note this?') are not store requests — ask before storing. "
            + "Content that expires within days (today's weather, transient state) is not worth storing unless the user explicitly asks. "
            + "If you are unsure whether something is worth storing, ask the user instead of storing. "
            + "Never store the conversation itself, your own intermediate reasoning, or content you merely derived from existing events. "
            + "Store the fact itself, never your justification for storing — content carries no meta-commentary. "
            + "Keep content short, plain text, self-contained — it will be returned whole on every find. "
            + "actor: 'user' if the content originates from the user (even if you phrased it), 'ai' for your own findings, 'tool' for tool output. "
            + "meta: prefer established keys (topic, project, relates_to, supersedes, typ, week, stage, origin); keep values short.")
    public String store(
            McpSyncServerExchange exchange,
            @McpToolParam(description = "Who authored this content: 'user' (later also a person's name or email), an AI agent name, or a tool name", required = true) String actor,
            @McpToolParam(description = "Type of memory", required = true) StmKind kind,
            @McpToolParam(description = "The information to remember, as plain text", required = true) String content,
            @McpToolParam(description = "Optional key-value pairs for context; prefer established keys: topic, project, relates_to, supersedes, typ, week, stage, origin", required = false) Map<String, String> meta) {
        StmService.StoreOutcome outcome = service.store(owner(), clientName(exchange), actor, kind.value(), content, meta);
        StringBuilder reply = new StringBuilder("stored event #").append(outcome.id());
        for (String warning : outcome.warnings()) {
            reply.append('\n').append(warning);
        }
        return reply.toString();
    }

    @McpTool(description = "Correct, complete, or finish an already-stored memory event — never store a corrected version as a new event. "
            + "Use when the user asks to fix or update something they told you earlier (\"that's wrong\", \"also…\", \"mark it done\"). "
            + "The original event is kept and marked superseded, not overwritten or deleted. "
            + "Pass only the fields that change; fields you leave out are inherited from the original, and meta is merged per key. "
            + "The event id is returned by store or visible in find/list results.")
    public String update(
            McpSyncServerExchange exchange,
            @McpToolParam(description = "Id of the event to correct", required = true) long id,
            @McpToolParam(description = "Replacement content; omit to keep the original", required = false) String content,
            @McpToolParam(description = "Replacement type of memory; omit to keep the original", required = false) StmKind kind,
            @McpToolParam(description = "Replacement author; omit to keep the original", required = false) String actor,
            @McpToolParam(description = "Meta keys to add or override; other keys are inherited", required = false) Map<String, String> meta) {
        long newId = service.update(owner(), clientName(exchange), id, content, kind, actor, meta);
        return "updated: event #" + newId + " supersedes #" + id;
    }

    /**
     * The authenticated principal (JWT {@code sub}) becomes the owner of every
     * written event (AP-7, write side only). The /mcp endpoint requires a JWT,
     * so there is always a principal in practice; 'unknown' is a defensive
     * fallback for direct service calls outside the MCP filter chain.
     */
    private String owner() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return "unknown";
    }

    /**
     * source is derived server-side from the MCP handshake clientInfo, so it cannot
     * be spoofed through tool arguments. Falls back to 'unknown' if the exchange
     * or clientInfo is missing (defensive; SYNC server always provides it).
     */
    private String clientName(McpSyncServerExchange exchange) {
        if (exchange != null && exchange.getClientInfo() != null && exchange.getClientInfo().name() != null
                && !exchange.getClientInfo().name().isBlank()) {
            return exchange.getClientInfo().name();
        }
        return "unknown";
    }

    @McpTool(description = "Keyword search over short-term memory (case-insensitive substring, German full-text, fuzzy trigram), newest first. "
            + "Use when the user refers to something remembered earlier (\"what did we decide about X\"). "
            + "Superseded events are excluded; set includeSuperseded to true to see them. "
            + "Results are whole events — keep limit low (default 10) because every hit fills your context window. "
            + "The search is lexical: if a query returns nothing, retry with synonyms or other word forms before concluding the memory has no answer.")
    public List<StmEvent> find(
            @McpToolParam(description = "Text to search for", required = true) String query,
            @McpToolParam(description = "Maximum number of results (default 10)", required = false) Integer limit,
            @McpToolParam(description = "Include superseded events (default false)", required = false) Boolean includeSuperseded) {
        return service.find(query, clamp(limit, 10, 50), Boolean.TRUE.equals(includeSuperseded));
    }

    @McpTool(description = "The most recent short-term memory events, newest first. "
            + "Use for a broad overview of what the memory holds (session start, weekly review, housekeeping). "
            + "Prefer find with a keyword when looking for something specific. "
            + "Superseded events are excluded; set includeSuperseded to true to see them. "
            + "Keep limit low: events are returned whole and fill your context window.")
    public List<StmEvent> list(
            @McpToolParam(description = "Maximum number of events to return (default 20)", required = false) Integer limit,
            @McpToolParam(description = "Include superseded events (default false)", required = false) Boolean includeSuperseded) {
        return service.recent(clamp(limit, 20, 100), Boolean.TRUE.equals(includeSuperseded));
    }

    private int clamp(Integer value, int defaultValue, int maxValue) {
        int limit = (value == null) ? defaultValue : value;
        return Math.min(Math.max(limit, 1), maxValue);
    }
}
