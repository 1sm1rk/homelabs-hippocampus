package de.homelabs.hippocampus.api;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production audience rule: the API accepts only tokens issued for its resource —
 * the gateway's URI. This is resource-location binding, not user authorization, so the
 * rule must hold regardless of who the subject is.
 */
class ApiSecurityAutoConfigurationTest {

    private static final String AUDIENCE = "http://localhost:8090/mcp";

    private final OAuth2TokenValidator<Jwt> validator =
            ApiSecurityAutoConfiguration.audienceValidator(AUDIENCE);

    @Test
    void acceptsATokenForTheConfiguredAudience() {
        assertThat(validator.validate(jwt(List.of("account", AUDIENCE))).hasErrors()).isFalse();
    }

    @Test
    void rejectsATokenForAnotherResource() {
        assertThat(validator.validate(jwt(List.of("http://localhost:9999/other"))).hasErrors()).isTrue();
    }

    @Test
    void rejectsATokenWithoutAnAudienceClaim() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("someone")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    private Jwt jwt(List<String> audience) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("someone")
                .audience(audience)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
