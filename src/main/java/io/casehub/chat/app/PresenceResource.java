package io.casehub.chat.app;

import io.casehub.qhorus.api.channel.PresenceStatus;
import io.casehub.qhorus.api.channel.PresenceTracker;
import io.casehub.qhorus.push.core.QhorusWebSocketBroadcasterCore;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/presence")
@io.casehub.platform.api.mcp.HandWrittenEndpoint("Direct presence tracking, not suitable for McpDomain generation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@ApplicationScoped
@Blocking
public class PresenceResource {

    @Inject
    PresenceTracker presence;

    @Inject
    QhorusWebSocketBroadcasterCore broadcaster;

    @GET
    @Path("/{memberId}")
    public Map<String, String> getPresence(@PathParam("memberId") String memberId) {
        var p = presence.getPresence(memberId);
        return Map.of("memberId", memberId, "status", p.status().name());
    }

    @PUT
    @Path("/{memberId}")
    public Response setPresence(@PathParam("memberId") String memberId,
                                SetPresenceRequest request) {
        try {
            var status = PresenceStatus.valueOf(request.status());
            presence.heartbeat(status, null);
            broadcaster.broadcastPresenceReplace(memberId, status);
        } catch (IllegalArgumentException e) {
            throw new jakarta.ws.rs.BadRequestException("Invalid status: " + request.status());
        }
        return Response.ok().build();
    }

    public record SetPresenceRequest(String status) {}
}
