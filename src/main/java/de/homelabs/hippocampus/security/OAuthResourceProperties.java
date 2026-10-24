package de.homelabs.hippocampus.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Protected Resource Metadata (RFC 9728) configuration, driven by config so the
 * AS can be swapped without touching code (AS-swap rule 4: PRM points to the
 * issuer — one config value).
 *
 * @param resource             the resource URI (audience); the value the token's
 *                             {@code aud} claim must contain
 * @param authorizationServers the authorization server issuer(s) (RFC 8414 / OIDC discovery)
 * @param scopesSupported      scopes this resource advertises (informational;
 *                             the server does not enforce scopes yet)
 * @param resourceMetadataUrl  the PRM document URL, returned in the 401
 *                             {@code WWW-Authenticate: Bearer resource_metadata="…"}
 */
@ConfigurationProperties(prefix = "hippocampus.oauth")
public record OAuthResourceProperties(
        String resource,
        List<String> authorizationServers,
        List<String> scopesSupported,
        String resourceMetadataUrl) {
}
