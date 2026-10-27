package de.homelabs.hippocampus.api;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Wires the REST API's OAuth 2.0 resource server: issuer and audience validation on
 * every request, one exception for the unauthenticated liveness probe
 * ({@code GET /health}).
 *
 * <p>Deliberately an auto-configuration rather than a plain {@code @Configuration}:
 * the {@code @ConditionalOnMissingBean} rules then let a test slice (or an embedding
 * application) replace the decoder — the same arrangement the service had while the
 * MCP transport and its auth came from a starter.
 *
 * <p>The issuer metadata (and with it the JWK set) is resolved lazily on the first
 * token, so the service starts even while its authorization server is still coming
 * up. There is no Protected Resource Metadata document and no discovery endpoint:
 * the API's caller is a configured server, not an OAuth browser client.
 */
@AutoConfiguration(before = { OAuth2ResourceServerAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiSecurityAutoConfiguration {

    /** JWT validation bound to issuer and audience. */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder hippocampusJwtDecoder(HippocampusApiProperties properties) {
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(properties.issuerUri()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
                    new JwtIssuerValidator(properties.issuerUri()),
                    audienceValidator(properties.audience())));
            return decoder;
        });
    }

    /** The audience binding: the token's {@code aud} claim must contain the configured audience. */
    public static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                values -> values != null && values.contains(audience));
    }

    /**
     * Stateless JWT API: no session, no CSRF (no cookies). Everything under
     * {@code /api/**} requires a valid token; only the liveness probe is open, so a
     * container healthcheck needs no credentials.
     */
    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain hippocampusApiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
