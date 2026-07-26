package io.casehub.chat.app;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ChatAppCurrentPrincipalTest {

    @Inject
    CurrentPrincipal principal;

    @Test
    void principalBeanIsResolved() {
        assertThat(principal).isNotNull();
    }

    @Test
    void outsideRequestScopeReturnsDefaults() {
        assertThat(principal.tenancyId()).isEqualTo("chat-app");
        assertThat(principal.actorId()).isEqualTo("anonymous");
        assertThat(principal.isCrossTenantAdmin()).isFalse();
    }
}
