package de.homelabs.hippocampus.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OAuth 2.1 resource server (AP-7): every MCP request carries a JWT in the
 * Authorization header; only the Protected Resource Metadata (RFC 9728)
 * discovery document is public. JWT validation (issuer + audience binding) is
 * configured via application.yml ({@code spring.security.oauth2.resourceserver.jwt.*}).
 * PRM and the 401 {@code WWW-Authenticate: Bearer resource_metadata="…"} entry
 * point use Spring Security 7's native RFC 9728 support. No AS-specific logic —
 * standard artifacts only (AS-swap rules 1-2).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, OAuthResourceProperties properties) throws Exception {
        BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
        entryPoint.setResourceMetadataParameterResolver(request -> properties.resourceMetadataUrl());

        http
                // Stateless JWT API: no session, no CSRF (no cookies).
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/.well-known/oauth-protected-resource").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .protectedResourceMetadata(metadata -> metadata
                                .protectedResourceMetadataCustomizer(builder -> builder
                                        .resource(properties.resource())
                                        .authorizationServers(servers -> servers.addAll(properties.authorizationServers()))
                                        .scopes(scopes -> scopes.addAll(properties.scopesSupported()))))
                        .authenticationEntryPoint(entryPoint));
        return http.build();
    }
}
