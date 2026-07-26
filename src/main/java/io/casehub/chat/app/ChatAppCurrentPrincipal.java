package io.casehub.chat.app;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Collections;
import java.util.Set;

@ApplicationScoped
public class ChatAppCurrentPrincipal implements CurrentPrincipal {

    private static final String DEFAULT_TENANT = "chat-app";

    @Inject
    Instance<SecurityIdentity> identityInstance;

    private SecurityIdentity identity() {
        try {
            if (identityInstance.isResolvable()) {
                var id = identityInstance.get();
                id.isAnonymous();
                return id;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    @Override
    public String actorId() {
        var id = identity();
        return (id != null && !id.isAnonymous()) ? id.getPrincipal().getName() : "anonymous";
    }

    @Override
    public Set<String> groups() {
        var id = identity();
        return (id != null && !id.isAnonymous()) ? id.getRoles() : Collections.emptySet();
    }

    @Override
    public String tenancyId() {
        var id = identity();
        if (id != null && !id.isAnonymous() && id.getPrincipal() instanceof JsonWebToken jwt) {
            Object tenant = jwt.getClaim("tenant_id");
            if (tenant instanceof String s && !s.isEmpty()) return s;
        }
        return DEFAULT_TENANT;
    }

    @Override
    public boolean isCrossTenantAdmin() {
        return false;
    }
}
