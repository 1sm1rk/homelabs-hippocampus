package de.homelabs.hippocampus.mcp;

import java.util.List;
import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import de.homelabs.hippocampus.mcp.dto.IngestResultDto;
import de.homelabs.hippocampus.mcp.dto.LtmFindHitDto;
import de.homelabs.hippocampus.mcp.dto.LtmGetResultDto;
import de.homelabs.hippocampus.mcp.dto.LtmSummaryDto;
import io.modelcontextprotocol.server.McpSyncServerExchange;

/**
 * Long-term memory tools: promote an STM event, import a document, then search
 * (hybrid), fetch and list. Read tools return structured results; the ingestion
 * tools reply with the new entry id + chunk count, or a warned no-op. Tool
 * descriptions are unchanged from the canonically approved text.
 *
 * <p>Each call is one authenticated HTTP call to the memory service's REST API; the
 * owner is the token's subject (resolved by the API), {@code source} comes from the
 * MCP handshake.
 */
@Component
public class LtmTools {

    private final HippocampusApiClient api;

    public LtmTools(HippocampusApiClient api) {
        this.api = api;
    }

    @McpTool(name = "promote", description = "Promote a short-term memory event into the user's long-term memory: the content is copied verbatim, split into chunks, and embedded for semantic search. "
            + "Use when the user asks to keep something for good (\"remember this long-term\", \"that belongs in my long-term memory\") or when a weekly review consolidates durable content (decisions, key facts). "
            + "Durable facts about the user (address, preferences, personal circumstances) always belong in long-term memory — promote them even without an explicit request. "
            + "If you are unsure whether an event is durable enough for long-term memory, ask the user instead of promoting. "
            + "Never promote events that are superseded — promote the corrected event instead. "
            + "The short-term event is never changed or deleted; the long-term entry keeps a provenance pointer to it. "
            + "Only events already in short-term memory can be promoted — if the user's content is not an event yet, store it first and promote the stored event. "
            + "Promoting an event that is already in long-term memory changes nothing (reported as a no-op).")
    public String promote(
            McpSyncServerExchange exchange,
            @McpToolParam(description = "Id of the short-term event to promote, as returned by store/find/list", required = true) long stm_id,
            @McpToolParam(description = "Short label for the entry; omit to derive one from the content", required = false) String title) {
        IngestResultDto outcome = api.promote(McpClientIdentity.source(exchange), stm_id, title);
        if (outcome.id() < 0) {
            return "no-op: " + String.join("\n", outcome.warnings());
        }
        return "promoted event #" + stm_id + " → ltm entry #" + outcome.id() + " (" + outcome.chunkCount() + " chunks)";
    }

    @McpTool(name = "import_file", description = "Import a document into the user's long-term memory: the file is read on the server, split into chunks, and embedded for semantic search. "
            + "Use when the user asks to put a document into long-term memory (\"import this PDF\", \"make the service remember this file\"). "
            + "Plain text and Markdown are read as UTF-8; PDFs must have a text layer — scanned PDFs fail with a clear error because OCR is not built yet. "
            + "Always pass an absolute path that the service process can read. "
            + "Importing the same unchanged file again changes nothing (reported as a no-op); if the content changed, a new entry is created and the old one stays.")
    public String import_file(
            McpSyncServerExchange exchange,
            @McpToolParam(description = "Absolute path of the file to import", required = true) String path,
            @McpToolParam(description = "Short label for the entry; omit to use the file name", required = false) String title,
            @McpToolParam(description = "Optional key-value pairs for context; prefer established keys: topic, project, relates_to, supersedes, typ, week, stage, origin", required = false) Map<String, String> meta) {
        IngestResultDto outcome = api.importFile(McpClientIdentity.source(exchange), path, title, meta);
        if (outcome.id() < 0) {
            return "no-op: " + String.join("\n", outcome.warnings());
        }
        StringBuilder reply = new StringBuilder("imported ").append(path)
                .append(" → ltm entry #").append(outcome.id())
                .append(" (").append(outcome.chunkCount()).append(" chunks)");
        for (String warning : outcome.warnings()) {
            reply.append('\n').append(warning);
        }
        return reply.toString();
    }

    @McpTool(name = "ltm_find", description = "Search over the user's long-term memory: semantic similarity on embeddings combined with lexical arms (German full-text, fuzzy trigram, substring), fused into one ranking. "
            + "Use when the user asks about something from earlier weeks or months, or when a short-term find came up empty. "
            + "Query in your own words — meaning matters more than exact keywords; if a query returns nothing, retry with different wording before concluding the memory has no answer. "
            + "Results are short excerpts with provenance (entry id, title, origin), never whole entries — keep limit low (default 5) because every hit fills your context window. "
            + "When an excerpt looks promising but is not enough, fetch the full entry with ltm_get.")
    public List<LtmFindHitDto> ltm_find(
            @McpToolParam(description = "Text to search for", required = true) String query,
            @McpToolParam(description = "Maximum number of results (default 5)", required = false) Integer limit) {
        return api.ltmFind(query, limit);
    }

    @McpTool(name = "ltm_get", description = "Fetch one long-term memory entry by id: the full content, or a slice of its chunks. "
            + "Use after ltm_find when an excerpt looks promising but is not enough. "
            + "Imported documents can be long — fetch only the chunk range you need instead of everything. "
            + "Chunk numbers are 1-based; the matched chunk's number is shown in ltm_find results.")
    public LtmGetResultDto ltm_get(
            @McpToolParam(description = "Id of the long-term entry, as returned by ltm_find/ltm_list", required = true) long ltm_id,
            @McpToolParam(description = "Chunk range (1-based); omit both to return the full entry", required = false) Integer from_seq,
            @McpToolParam(description = "Chunk range (1-based); omit both to return the full entry", required = false) Integer to_seq) {
        return api.ltmGet(ltm_id, from_seq, to_seq);
    }

    @McpTool(name = "ltm_list", description = "The long-term memory index, newest first: id, title, origin, chunk count — no content. "
            + "Use for a broad overview of what long-term memory holds (session start, weekly review, housekeeping). "
            + "Prefer ltm_find when looking for something specific. "
            + "Keep limit low: a growing corpus makes even index lines numerous.")
    public List<LtmSummaryDto> ltm_list(
            @McpToolParam(description = "Maximum number of entries to return (default 20)", required = false) Integer limit) {
        return api.ltmList(limit);
    }
}
