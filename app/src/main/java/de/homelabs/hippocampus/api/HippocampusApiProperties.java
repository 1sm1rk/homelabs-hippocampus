package de.homelabs.hippocampus.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the REST API's OAuth 2.0 resource server. The API's caller is a
 * configured server (the MCP gateway), not a browser, so there is no Protected
 * Resource Metadata document and no authorization-server discovery: the issuer and
 * the expected audience are pinned here.
 *
 * <p>The audience is the RESOURCE the token was issued for — the gateway's endpoint
 * URI, not this API's own address (RFC 8707 resource-location binding). It is never
 * user authorization: the user boundary stays {@code owner = token sub} on every
 * read and write path.
 *
 * @param issuerUri the issuer whose metadata resolves the JWK set and whose
 *                  {@code iss} claim tokens must carry
 * @param audience  the {@code aud} value every accepted token must contain
 */
@ConfigurationProperties(prefix = "hippocampus.api")
public record HippocampusApiProperties(
        @DefaultValue("http://localhost:8091/realms/hippocampus") String issuerUri,
        @DefaultValue("http://localhost:8090/mcp") String audience) {
}
