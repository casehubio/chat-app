package io.casehub.chat.app.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.chat.app.MarkReadRequest;
import io.casehub.chat.app.MoveToSpaceRequest;
import io.casehub.chat.app.PostMessageRequest;
import io.casehub.chat.app.PostMessageResult;
import io.casehub.platform.api.mcp.ApiResult;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import io.casehub.qhorus.api.channel.MembershipManager;
import io.casehub.qhorus.api.channel.PresenceStatus;
import io.casehub.qhorus.api.channel.PresenceTracker;
import io.casehub.qhorus.api.message.ArtefactRef;
import io.casehub.qhorus.api.message.ConsumerMessaging;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.MembershipReader;
import io.casehub.qhorus.api.store.TopicReader;
import io.casehub.qhorus.push.QhorusWebSocketBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.QueryParam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@McpDomain(value = "chat/messages", basePath = "/api/chat/messages")
@ApplicationScoped
public class ChatMessageApi {

    @Inject ConsumerMessaging messaging;
    @Inject CurrentPrincipal currentPrincipal;
    @Inject MembershipManager members;
    @Inject MembershipReader memberReader;
    @Inject PresenceTracker presence;
    @Inject QhorusWebSocketBroadcaster broadcaster;
    @Inject TopicReader topicReader;
    @Inject ObjectMapper objectMapper;
    @Inject io.casehub.qhorus.runtime.channel.SpaceService spaceService;



    @PlatformMutation("Send a message to a channel")
    @RestPath("/{channelId}")
    public PostMessageResult postMessage(@PathParam String channelId,
                                         PostMessageRequest request) {
        var channelUuid = UUID.fromString(channelId);
        var sender = currentPrincipal.actorId();
        ensureMembership(channelUuid, sender);
        ensurePresence(sender);

        var msgType = request.messageType() != null ? request.messageType() : "QUERY";
        var actType = request.actorType() != null ? request.actorType() : "HUMAN";

        List<ArtefactRef> artefactRefs = parseArtefactRefs(request.artefactRefs());
        String topicName = resolveTopicName(channelUuid, request.topicId(), request.topic());
        String correlationId = "COMMAND".equals(msgType) ? UUID.randomUUID().toString() : null;

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

        var result = messaging.dispatch(dispatch);
        return new PostMessageResult(true, result.messageId(), result.correlationId());
    }

    @PlatformQuery("List messages in a channel")
    @RestPath("/{channelId}")
    public List<Message> listMessages(@PathParam String channelId,
                                      @QueryParam("since") String since) {
        var channelUuid = UUID.fromString(channelId);
        long afterId = 0;
        if (since != null) {
            try {
                afterId = Long.parseLong(since);
            } catch (NumberFormatException e) {
                throw new jakarta.ws.rs.BadRequestException("Invalid 'since' parameter: " + since);
            }
        }
        return messaging.history(channelUuid, afterId, 10000);
    }

    @PlatformMutation("Reply to a message in a channel")
    @RestPath("/{channelId}/{messageId}/replies")
    public PostMessageResult postReply(@PathParam String channelId,
                                       @PathParam String messageId,
                                       PostMessageRequest request) {
        var channelUuid = UUID.fromString(channelId);
        var parentId = Long.parseLong(messageId);
        var sender = currentPrincipal.actorId();
        ensureMembership(channelUuid, sender);
        ensurePresence(sender);

        var parent = messaging.findById(parentId)
                .orElseThrow(() -> new jakarta.ws.rs.BadRequestException("Parent message not found"));

        var msgType = request.messageType() != null ? request.messageType() : "QUERY";
        var actType = request.actorType() != null ? request.actorType() : "HUMAN";

        List<ArtefactRef> artefactRefs = parseArtefactRefs(request.artefactRefs());
        String topicName = parent.topic() != null ? parent.topic() : "";

        var dispatch = MessageDispatch.builder()
                .channelId(channelUuid)
                .sender(sender)
                .type(MessageType.valueOf(msgType))
                .actorType(ActorType.valueOf(actType))
                .content(request.text())
                .correlationId(parent.correlationId())
                .inReplyTo(parentId)
                .target(request.target())
                .artefactRefs(artefactRefs)
                .topic(topicName)
                .build();

        var result = messaging.dispatch(dispatch);
        return new PostMessageResult(true, result.messageId(), null);
    }

    @PlatformMutation("Mark messages as read in a channel")
    @RestPath("/{channelId}/read")
    public void markRead(@PathParam String channelId,
                         MarkReadRequest request) {
        var channelUuid = UUID.fromString(channelId);
        var memberId = currentPrincipal.actorId();
        members.updateLastReadMessageId(channelUuid, memberId, request.lastReadMessageId());
    }

    @PlatformMutation("Move a channel to a space")
    @RestPath("/channels/{channelId}/space")
    public ApiResult moveChannelToSpace(@PathParam String channelId,
                                        MoveToSpaceRequest request) {
        var channelUuid = UUID.fromString(channelId);
        var spaceUuid = request.spaceId() != null ? UUID.fromString(request.spaceId()) : null;
        var updated = spaceService.moveChannelToSpace(channelUuid, spaceUuid, request.position());
        return new ApiResult(true, updated.id().toString(), null);
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

    private List<ArtefactRef> parseArtefactRefs(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        try {
            var json = objectMapper.writeValueAsString(raw);
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ArtefactRef.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
