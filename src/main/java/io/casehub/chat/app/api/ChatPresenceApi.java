package io.casehub.chat.app.api;

import io.casehub.chat.app.SetPresenceRequest;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.qhorus.api.channel.PresenceStatus;
import io.casehub.qhorus.api.channel.PresenceTracker;
import io.casehub.qhorus.push.QhorusWebSocketBroadcaster;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@McpDomain(value = "chat/presence", basePath = "/api/chat/presence")
@ApplicationScoped
public class ChatPresenceApi {

    @Inject
    PresenceTracker presence;
    @Inject
    QhorusWebSocketBroadcaster broadcaster;


    @PlatformQuery("Get presence status for a member")
    @RestPath("/{memberId}")
    public Map<String, String> getPresence(@PathParam String memberId) {
        var p = presence.getPresence(memberId);
        return Map.of("memberId", memberId, "status", p.status().name());
    }

    @PlatformMutation("Set presence status for a member")
    @RestPath("/{memberId}")
    public void setPresence(@PathParam String memberId,
                            SetPresenceRequest request) {
        var status = PresenceStatus.valueOf(request.status());
        presence.heartbeat(status, null);
        broadcaster.broadcastPresenceReplace(memberId, status);
    }
}
