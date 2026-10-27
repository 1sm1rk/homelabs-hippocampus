package de.homelabs.hippocampus;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import de.homelabs.hippocampus.api.ApiSecurityAutoConfiguration;

/**
 * Test-only resource-server wiring: a symmetric-HMAC JwtDecoder (so the API accepts
 * test tokens without a running authorization server) and a JwtEncoder to mint them.
 * It replaces the auto-configured issuer-bound decoder, which would otherwise reach
 * for Keycloak.
 *
 * <p>The audience rule is not re-implemented here: it is the production
 * {@link ApiSecurityAutoConfiguration#audienceValidator} predicate, so the integration
 * tests exercise the rule the service actually ships.
 */
@TestConfiguration
public class TestSecurityConfig {

    /** Matches {@code hippocampus.api.audience} in application.yml. */
    public static final String AUDIENCE = "http://localhost:8090/mcp";

    /** A valid token for another resource: proves the audience binding rejects it. */
    public static final String FOREIGN_AUDIENCE = "http://localhost:9999/other";

    private static final String SECRET =
            "test-only-symmetric-secret-for-hs256-must-be-at-least-32-bytes-long-ok";

    private static SecretKey secretKey() {
        return new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                ApiSecurityAutoConfiguration.audienceValidator(AUDIENCE)));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(SECRET.getBytes(StandardCharsets.UTF_8)));
    }
}
