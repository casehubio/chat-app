package io.casehub.chat.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelManager;
import io.casehub.qhorus.api.channel.ChannelMembership;
import io.casehub.qhorus.api.channel.ChannelReader;
import io.casehub.qhorus.api.channel.MembershipManager;
import io.casehub.qhorus.api.channel.PresenceStatus;
import io.casehub.qhorus.api.channel.PresenceTracker;
import io.casehub.qhorus.api.channel.ReactionManager;
import io.casehub.qhorus.api.channel.TopicManager;
import io.casehub.qhorus.api.message.ArtefactRef;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.ConsumerMessaging;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.Reaction;
import io.casehub.qhorus.api.message.TopicSummary;
import io.casehub.qhorus.api.store.CommitmentReader;
import io.casehub.qhorus.api.store.MembershipReader;
import io.casehub.qhorus.api.store.ReactionReader;
import io.casehub.qhorus.api.store.TopicReader;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@ApplicationScoped
@Blocking
@Transactional
public class ChatResource {

    @Inject
    ConsumerMessaging        messaging;
    @Inject
    ChannelManager           channels;
    @Inject
    ChannelReader            channelReader;
    @Inject
    ReactionManager          reactions;
    @Inject
    ReactionReader           reactionReader;
    @Inject
    PresenceTracker          presence;
    @Inject
    MembershipManager        members;
    @Inject
    MembershipReader         memberReader;
    @Inject
    CommitmentReader         commitmentReader;
    @Inject
    TopicManager             topics;
    @Inject
    TopicReader              topicReader;
    @Inject
    ChatWebSocketBroadcaster broadcaster;
    @Inject
    CurrentPrincipal         currentPrincipal;
    @Inject
    ObjectMapper             objectMapper;

    // --- Channels ---

    @POST
    @Path("/channels")
    public Response createChannel(CreateChannelRequest request) {
        var channel = channels.create(ChannelCreateRequest.builder(request.name())
                                                          .description(request.description() != null ? request.description() : "")
                                                          .build());
        broadcaster.broadcastChannelAppend(channel);
        return Response.ok(channel).build();
    }

    @DELETE
    @Path("/channels/{channelId}")
    public Response deleteChannel(@PathParam("channelId") String channelId) {
        var uuid = UUID.fromString(channelId);
        channels.delete(uuid, true);
        broadcaster.broadcastChannelRemove(uuid);
        return Response.noContent().build();
    }

    @GET
    @Path("/channels")
    public List<Channel> listChannels() {
        return channelReader.listAll();
    }

    // --- Messages ---

    @POST
    @Path("/channels/{channelId}/messages")
    public Response postMessage(@PathParam("channelId") String channelId,
                                PostMessageRequest request) {
        var channelUuid = UUID.fromString(channelId);
        var sender      = currentPrincipal.actorId();
        ensureMembership(channelUuid, sender);
        ensurePresence(sender);

        var msgType = request.messageType() != null ? request.messageType() : "QUERY";
        var actType = request.actorType() != null ? request.actorType() : "HUMAN";

        List<ArtefactRef> artefactRefs = parseArtefactRefs(request.artefactRefs());
        String            topicName    = resolveTopicName(channelUuid, request.topicId(), request.topic());
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
        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("ok", true);
        response.put("messageId", result.messageId());
        if (result.correlationId() != null) response.put("correlationId", result.correlationId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/channels/{channelId}/messages")
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

    // --- Replies ---

    @POST
    @Path("/channels/{channelId}/messages/{messageId}/replies")
    public Response postReply(@PathParam("channelId") String channelId,
                              @PathParam("messageId") String messageId,
                              PostMessageRequest request) {
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

        List<ArtefactRef> artefactRefs = parseArtefactRefs(request.artefactRefs());
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

        var result = messaging.dispatch(dispatch);
        return Response.ok(Map.of(
                "ok", true,
                "messageId", result.messageId())).build();
    }

    // --- Reactions ---

    @POST
    @Path("/channels/{channelId}/messages/{messageId}/reactions")
    public Response addReaction(@PathParam("channelId") String channelId,
                                @PathParam("messageId") String messageId,
                                ReactionRequest request) {
        reactions.react(Long.parseLong(messageId), request.emoji());
        broadcaster.broadcastReactionAppend(Long.parseLong(messageId), request.emoji());
        return Response.ok().build();
    }

    @DELETE
    @Path("/channels/{channelId}/messages/{messageId}/reactions/{emoji}")
    public Response removeReaction(@PathParam("channelId") String channelId,
                                   @PathParam("messageId") String messageId,
                                   @PathParam("emoji") String emoji) {
        reactions.unreact(Long.parseLong(messageId), emoji);
        broadcaster.broadcastReactionRemove(Long.parseLong(messageId), emoji);
        return Response.ok().build();
    }

    @GET
    @Path("/channels/{channelId}/messages/{messageId}/reactions")
    public List<String> listReactions(@PathParam("channelId") String channelId,
                                      @PathParam("messageId") String messageId) {
        return reactionReader.findByMessage(Long.parseLong(messageId)).stream()
                             .map(Reaction::emoji)
                             .toList();
    }

    // --- Members ---

    @GET
    @Path("/channels/{channelId}/members")
    public List<ChannelMembership> listMembers(@PathParam("channelId") String channelId) {
        return memberReader.findByChannel(UUID.fromString(channelId));
    }

    @POST
    @Path("/channels/{channelId}/members")
    public Response addMember(@PathParam("channelId") String channelId,
                              AddMemberRequest request) {
        var channelUuid = UUID.fromString(channelId);
        var membership  = members.join(channelUuid, request.memberId());
        broadcaster.broadcastMemberAppend(channelUuid, membership);
        return Response.ok().build();
    }

    @DELETE
    @Path("/channels/{channelId}/members/{memberId}")
    public Response removeMember(@PathParam("channelId") String channelId,
                                 @PathParam("memberId") String memberId) {
        var channelUuid = UUID.fromString(channelId);
        members.leave(channelUuid, memberId);
        broadcaster.broadcastMemberRemove(channelUuid, memberId);
        return Response.ok().build();
    }

    // --- Presence ---

    @GET
    @Path("/presence/{memberId}")
    public Map<String, String> getPresence(@PathParam("memberId") String memberId) {
        var p = presence.getPresence(memberId);
        return Map.of("memberId", memberId, "status", p.status().name());
    }

    @PUT
    @Path("/presence/{memberId}")
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

    // --- Read tracking ---

    @PUT
    @Path("/channels/{channelId}/read")
    public Response markRead(@PathParam("channelId") String channelId,
                             MarkReadRequest request) {
        var channelUuid = UUID.fromString(channelId);
        var memberId    = currentPrincipal.actorId();
        members.updateLastReadMessageId(channelUuid, memberId, request.lastReadMessageId());
        return Response.ok().build();
    }

    // --- Commitments ---

    @GET
    @Path("/channels/{channelId}/commitments")
    public List<Commitment> listCommitments(@PathParam("channelId") String channelId) {
        return commitmentReader.findByChannel(UUID.fromString(channelId));
    }

    // --- Correlation ---

    @GET
    @Path("/channels/{channelId}/correlation/{correlationId}")
    public List<Message> correlationChain(@PathParam("channelId") String channelId,
                                          @PathParam("correlationId") String correlationId) {
        return messaging.findAllByCorrelationId(correlationId);
    }

    // --- Topics ---

    @POST
    @Path("/channels/{channelId}/topics")
    public Response createTopic(@PathParam("channelId") String channelId,
                                CreateTopicRequest request) {
        var channelUuid = UUID.fromString(channelId);
        var name        = request.name() != null ? request.name().trim() : "";
        if (name.isEmpty()) {
            return Response.status(400).entity(Map.of("error", "Topic name must not be empty")).build();
        }
        if (name.length() > 100) {
            return Response.status(400).entity(Map.of("error", "Topic name must be 100 characters or less")).build();
        }
        if ("General".equals(name) || "general".equals(name)) {
            return Response.status(409).entity(Map.of("error", "\"General\" is reserved")).build();
        }
        var existing = topicReader.find(channelUuid, name);
        if (existing.isPresent()) {
            return Response.status(409).entity(Map.of("error", "Topic already exists")).build();
        }
        var topic = topics.create(channelUuid, name);
        broadcaster.broadcastTopicAppend(channelUuid, topic);
        return Response.ok(Map.of("id", String.valueOf(topic.id()), "name", topic.name())).build();
    }

    @GET
    @Path("/channels/{channelId}/topics")
    public List<TopicSummary> listTopics(@PathParam("channelId") String channelId) {
        return topics.listTopics(UUID.fromString(channelId));
    }

    @PUT
    @Path("/channels/{channelId}/topics/{topicId}")
    public Response updateTopic(@PathParam("channelId") String channelId,
                                @PathParam("topicId") String topicId,
                                UpdateTopicRequest request) {
        var channelUuid = UUID.fromString(channelId);
        var topicLongId = Long.parseLong(topicId);
        var existing    = topicReader.findById(topicLongId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Topic not found")).build();
        }
        if (!channelUuid.equals(existing.get().channelId())) {
            return Response.status(400).entity(Map.of("error", "Topic does not belong to this channel")).build();
        }
        if (request.name() != null) {
            var trimmed = request.name().trim();
            if (trimmed.isEmpty() || trimmed.length() > 100) {
                return Response.status(400).entity(Map.of("error", "Invalid topic name")).build();
            }
            topics.rename(channelUuid, existing.get().name(), trimmed);
        }
        if (request.state() != null) {
            if ("RESOLVED".equals(request.state())) {
                topics.resolve(channelUuid, existing.get().name());
            } else if ("ACTIVE".equals(request.state()) && existing.get().resolved()) {
                topics.unresolve(channelUuid, existing.get().name());
            }
        }
        var updated = topicReader.findById(topicLongId).orElse(existing.get());
        broadcaster.broadcastTopicReplace(channelUuid, updated);
        return Response.ok(Map.of("ok", true)).build();
    }

    @POST
    @Path("/channels/{channelId}/topics/{topicId}/merge")
    public Response mergeTopic(@PathParam("channelId") String channelId,
                               @PathParam("topicId") String topicId,
                               MergeTopicRequest request) {
        var channelUuid   = UUID.fromString(channelId);
        var sourceTopicId = Long.parseLong(topicId);
        var source        = topicReader.findById(sourceTopicId);
        if (source.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Source topic not found")).build();
        }
        if (!channelUuid.equals(source.get().channelId())) {
            return Response.status(400).entity(Map.of("error", "Source topic does not belong to this channel")).build();
        }
        if ("general".equalsIgnoreCase(source.get().name())) {
            return Response.status(400).entity(Map.of("error", "Cannot merge the default topic")).build();
        }
        var targetTopicId = Long.parseLong(request.targetTopicId());
        var target        = topicReader.findById(targetTopicId);
        if (target.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Target topic not found")).build();
        }
        if (!channelUuid.equals(target.get().channelId())) {
            return Response.status(400).entity(Map.of("error", "Target topic does not belong to this channel")).build();
        }
        topics.merge(channelUuid, source.get().name(), target.get().name());
        broadcaster.broadcastTopicRemove(channelUuid, sourceTopicId);
        var updatedTarget = topicReader.findById(targetTopicId).orElse(target.get());
        broadcaster.broadcastTopicReplace(channelUuid, updatedTarget);
        return Response.ok(Map.of("ok", true)).build();
    }

    // --- Private helpers ---

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
        }}

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
        if (raw == null || raw.isEmpty()) {return List.of();}
        try {
            var json = objectMapper.writeValueAsString(raw);
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                                                            .constructCollectionType(List.class, ArtefactRef.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    // --- Request DTOs ---

    public record CreateChannelRequest(String name, String topic, String description, boolean isPrivate) {}

    public record PostMessageRequest(String text, String messageType, String actorType,
                                     String target, List<Map<String, Object>> artefactRefs,
                                     String topic, String topicId) {}

    public record ReactionRequest(String emoji) {}

    public record AddMemberRequest(String memberId) {}

    public record SetPresenceRequest(String status) {}

    public record MarkReadRequest(Long lastReadMessageId) {}

    public record CreateTopicRequest(String name) {}

    public record UpdateTopicRequest(String name, String state) {}

    public record MergeTopicRequest(String targetTopicId) {}
}
