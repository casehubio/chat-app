package io.casehub.chat.app.api;

import io.casehub.chat.app.PresenceResource;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@McpDomain(value = "chat/presence", app = "chat-app", basePath = "/api/chat/presence", summary = "Get presence status for a member; Set presence status for a member")
@ApplicationScoped
public class ChatPresenceApi {

    @Inject PresenceResource resource;

    @PlatformQuery("Get presence status for a member")
    @RestPath("/{memberId}")
    public Map<String, String> getPresence(@PathParam String memberId) {
        return resource.getPresence(memberId);
    }

    @PlatformMutation("Set presence status for a member")
    @RestPath("/{memberId}")
    public Object setPresence(@PathParam String memberId,
                               PresenceResource.SetPresenceRequest request) {
        return resource.setPresence(memberId, request).getEntity();
    }
}
