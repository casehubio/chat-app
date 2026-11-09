package io.casehub.chat.app;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.MembershipManager;
import io.casehub.qhorus.api.channel.PresenceStatus;
import io.casehub.qhorus.api.channel.PresenceTracker;
import io.casehub.qhorus.api.message.ArtefactRef;
import io.casehub.qhorus.api.message.ConsumerMessaging;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.MembershipReader;
import io.casehub.qhorus.api.store.TopicReader;
import io.casehub.qhorus.push.core.QhorusWebSocketBroadcasterCore;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/chat")
@io.casehub.platform.api.mcp.HandWrittenEndpoint("WebSocket upgrade, SSE streaming, custom auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@ApplicationScoped
@Blocking
@Transactional
public class ChatResource {

    @Inject
    ConsumerMessaging                              messaging;
    @Inject
    PresenceTracker                                presence;
    @Inject
    MembershipManager                              members;
    @Inject
    MembershipReader                               memberReader;
    @Inject
    TopicReader                                    topicReader;
    @Inject
    QhorusWebSocketBroadcasterCore                 broadcaster;
    @Inject
    CurrentPrincipal                               currentPrincipal;
    @Inject
    io.casehub.qhorus.runtime.channel.SpaceService spaceService;


    // --- Messages ---

    @POST
    @Path("/{channelId}/messages")
    public Response postMessage(@PathParam("channelId") String channelId,
                                PostMessageRequest request) {
        var result   = dispatchMessage(channelId, request);
        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("ok", true);
        response.put("messageId", result.messageId());
        if (result.correlationId() != null) {response.put("correlationId", result.correlationId());}
        return Response.ok(response).build();
    }

    public DispatchResult dispatchMessage(String channelId, PostMessageRequest request) {
        var channelUuid = UUID.fromString(channelId);
        var sender      = currentPrincipal.actorId();
        ensureMembership(channelUuid, sender);
        ensurePresence(sender);

        var msgType = request.messageType() != null ? request.messageType() : "QUERY";
        var actType = request.actorType() != null ? request.actorType() : "HUMAN";

        List<ArtefactRef> artefactRefs  = request.artefactRefs() != null ? request.artefactRefs() : List.of();
        String            topicName     = resolveTopicName(channelUuid, request.topicId(), request.topic());
        String            correlationId = "COMMAND".equals(msgType) ? UUID.randomUUID().toString() : null;

        var dispatch = MessageDispatch.builder()
                                      .channelId(channelUuid)
                                      .sender(sender)
                                      .type(MessageType.valueOf(msgType))
                                      .actorType(ActorType.valueOf(actType))
                                      .content(request.text())
                                      .correlationId(correlationId)
                                      .target(request.target())
                                      .artefactRefs(artefactRefs)
                                      .topic(topicName)
                                      .build();

        return messaging.dispatch(dispatch);
    }


    @GET
    @Path("/{channelId}/messages")
    public List<Message> listMessages(@PathParam("channelId") String channelId,
                                      @QueryParam("since") String since) {
        var  channelUuid = UUID.fromString(channelId);
        long afterId     = 0;
        if (since != null) {
            try {
                afterId = Long.parseLong(since);
            } catch (NumberFormatException e) {
                throw new jakarta.ws.rs.BadRequestException("Invalid 'since' parameter: " + since);
            }
        }
        return messaging.history(channelUuid, afterId, 10000);
    }

    public List<Message> getNewMessages(String channelId) {
        var    channelUuid = UUID.fromString(channelId);
        String memberId    = currentPrincipal.actorId();
        ensureMembership(channelUuid, memberId);
        long afterId = members.listMembers(channelUuid).stream()
                              .filter(m -> memberId.equals(m.memberId()))
                              .map(m -> m.lastReadMessageId() != null ? m.lastReadMessageId() : 0L)
                              .findFirst()
                              .orElse(0L);
        return messaging.history(channelUuid, afterId, 10000);
    }


    // --- Replies ---

    @POST
    @Path("/{channelId}/messages/{messageId}/replies")
    public Response postReply(@PathParam("channelId") String channelId,
                              @PathParam("messageId") String messageId,
                              PostMessageRequest request) {
        var result = dispatchReply(channelId, messageId, request);
        return Response.ok(Map.of(
                "ok", true,
                "messageId", result.messageId())).build();
    }

    public DispatchResult dispatchReply(String channelId, String messageId, PostMessageRequest request) {
        var channelUuid = UUID.fromString(channelId);
        var parentId    = Long.parseLong(messageId);
        var sender      = currentPrincipal.actorId();
        ensureMembership(channelUuid, sender);
        ensurePresence(sender);

        var parent = messaging.findById(parentId)
                              .orElseThrow(() -> new jakarta.ws.rs.BadRequestException("Parent message not found"));

        String correlationId = parent.correlationId();

        var msgType = request.messageType() != null ? request.messageType() : "QUERY";
        var actType = request.actorType() != null ? request.actorType() : "HUMAN";

        List<ArtefactRef> artefactRefs = request.artefactRefs() != null ? request.artefactRefs() : List.of();
        String            topicName    = parent.topic() != null ? parent.topic() : "";

        var dispatch = MessageDispatch.builder()
                                      .channelId(channelUuid)
                                      .sender(sender)
                                      .type(MessageType.valueOf(msgType))
                                      .actorType(ActorType.valueOf(actType))
                                      .content(request.text())
                                      .correlationId(correlationId)
                                      .inReplyTo(parentId)
                                      .target(request.target())
                                      .artefactRefs(artefactRefs)
                                      .topic(topicName)
                                      .build();

        return messaging.dispatch(dispatch);
    }


    // --- Read tracking ---

    @PUT
    @Path("/{channelId}/read")
    public ReadMarkerView markRead(@PathParam("channelId") String channelId,
                                   MarkReadRequest request) {
        var channelUuid = UUID.fromString(channelId);
        var memberId    = currentPrincipal.actorId();
        members.updateLastReadMessageId(channelUuid, memberId, request.lastReadMessageId());
        return new ReadMarkerView(channelId, request.lastReadMessageId());
    }

    // --- Private helpers ---


    @PUT
    @Path("/channels/{channelId}/space")
    public ChannelMoveView moveChannelToSpace(@PathParam("channelId") String channelId,
                                              MoveToSpaceRequest request) {
        var channelUuid = UUID.fromString(channelId);
        var spaceUuid   = request.spaceId() != null ? UUID.fromString(request.spaceId()) : null;
        var updated     = spaceService.moveChannelToSpace(channelUuid, spaceUuid, request.position());
        return new ChannelMoveView(updated.id().toString());
    }

    private void ensureMembership(UUID channelId, String memberId) {
        if (memberReader.find(channelId, memberId).isEmpty()) {
            var membership = members.join(channelId, memberId);
            broadcaster.broadcastMemberAppend(channelId, membership);
        }
    }

    private void ensurePresence(String memberId) {
        var p = presence.getPresence(memberId);
        if (p == null || p.status() == PresenceStatus.OFFLINE) {
            presence.heartbeat(PresenceStatus.ONLINE, null);
            broadcaster.broadcastPresenceReplace(memberId, PresenceStatus.ONLINE);
        }
    }

    private String resolveTopicName(UUID channelId, String topicId, String topicName) {
        if (topicId != null && !topicId.isEmpty()) {
            var topic = topicReader.findById(Long.parseLong(topicId));
            if (topic.isPresent() && channelId.equals(topic.get().channelId())) {
                return topic.get().name();
            }
        }
        if (topicName != null && !topicName.trim().isEmpty()) {
            return topicName.trim();
        }
        return "general";
    }

    // --- Request DTOs ---


    public record ReadMarkerView(String channelId, Long lastReadMessageId) {}

    public record ChannelMoveView(String channelId) {}


    public record PostMessageRequest(String text, String messageType, String actorType,
                                     String target, List<ArtefactRef> artefactRefs,
                                     String topic, String topicId) {}

    public record MarkReadRequest(Long lastReadMessageId) {}

    public record MoveToSpaceRequest(String spaceId, Integer position) {}

}
