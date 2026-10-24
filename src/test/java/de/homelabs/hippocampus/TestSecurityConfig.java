package de.homelabs.hippocampus;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Test-only resource-server wiring: a symmetric-HMAC JwtDecoder (so the
 * resource server accepts test tokens without a running Keycloak) and a
 * JwtEncoder to mint them. The decoder mirrors the production audience binding
 * (the {@code aud} claim must contain the resource URI) and overrides the
 * issuer-uri auto-config, which would otherwise reach for Keycloak.
 */
@TestConfiguration
public class TestSecurityConfig {

    /** Matches {@code spring.security.oauth2.resourceserver.jwt.audiences} in application.yml. */
    public static final String AUDIENCE = "http://localhost:8090/mcp";

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
                jwt -> {
                    List<String> audience = jwt.getAudience();
                    if (audience == null || !audience.contains(AUDIENCE)) {
                        return OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "missing required audience", null));
                    }
                    return OAuth2TokenValidatorResult.success();
                }));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(SECRET.getBytes(StandardCharsets.UTF_8)));
    }
}
