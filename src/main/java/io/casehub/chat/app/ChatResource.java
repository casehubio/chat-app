package io.casehub.chat.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.connectors.chat.model.Channel;
import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.casehub.connectors.chat.model.Member;
import io.casehub.connectors.chat.model.MemberRef;
import io.casehub.connectors.chat.model.PresenceStatus;
import io.casehub.connectors.chat.model.ReceivedMessage;
import io.casehub.connectors.chat.spi.ChatPlatform;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ChatResource {

    private static final String PLATFORM_ID = "ref";

    @Inject
    ChatPlatform chatPlatform;

    @Inject
    ChatWebSocketBroadcaster broadcaster;

    @Inject
    SecurityIdentity identity;

    @Inject
    SqliteChatBackend chatBackend;
    @Inject
    ObjectMapper      objectMapper;


    // --- Channels ---

    @POST
    @Path("/channels")
    public Response createChannel(final CreateChannelRequest request) {
        final Channel channel = chatPlatform.channelManagement().create(
                request.name(),
                request.topic() != null ? request.topic() : "",
                request.description() != null ? request.description() : "",
                request.isPrivate());
        broadcaster.broadcastChannelAppend(channel);
        return Response.ok(channel).build();
    }

    @DELETE
    @Path("/channels/{channelId}")
    public Response deleteChannel(@PathParam("channelId") final String channelId) {
        chatPlatform.channelManagement().delete(channelId);
        broadcaster.broadcastChannelRemove(channelId);
        return Response.noContent().build();
    }

    @GET
    @Path("/channels")
    public List<Channel> listChannels() {
        return chatPlatform.discovery().listChannels();
    }

    // --- Messages ---

    @POST
    @Path("/channels/{channelId}/messages")
    public Response postMessage(@PathParam("channelId") final String channelId,
                                final PostMessageRequest request) {
        final var channelRef = new ChatChannelRef(channelId);
        final var sender     = new MemberRef(identity.getPrincipal().getName());
        ensureMembership(channelRef, sender);
        ensurePresence(sender);
        final var content = new ChatContent(request.text());
        final var msg     = chatBackend.storeMessage(PLATFORM_ID, channelRef, content, sender, null);

        final String msgType       = request.messageType() != null ? request.messageType() : "EVENT";
        final String actType       = request.actorType() != null ? request.actorType() : "HUMAN";
        final String correlationId = "COMMAND".equals(msgType) ? msg.messageRef().messageId() : null;
        String       refsJson      = "[]";
        try {
            if (request.artefactRefs() != null && !request.artefactRefs().isEmpty()) {
                refsJson = objectMapper.writeValueAsString(request.artefactRefs());
            }
        } catch (final JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        final String topicId = chatBackend.getDefaultTopicId(channelId);
        chatBackend.storeEnrichedFields(msg.messageRef().messageId(), channelId,
                                        msgType, actType, correlationId, request.target(), refsJson, topicId);
        if ("COMMAND".equals(msgType)) {
            chatBackend.createCommitment(msg.messageRef().messageId(), channelId, null);
            broadcaster.broadcastCommitmentAppend(msg.messageRef().messageId(), channelId);
        }

        broadcaster.broadcastMessageAppend(msg);
        return Response.ok(Map.of(
                "ok", true,
                "messageId", msg.messageRef().messageId(),
                "timestamp", msg.receivedAt().toString())).build();
    }

    private void ensureMembership(final ChatChannelRef channelRef, final MemberRef sender) {
        final boolean isMember = chatPlatform.members().list(channelRef).stream()
                                             .anyMatch(m -> m.ref().id().equals(sender.id()));
        if (!isMember) {
            final var member = new Member(sender, sender.id());
            chatPlatform.memberManagement().add(channelRef, member);
            broadcaster.broadcastMemberAppend(channelRef.id(), member);
        }
    }

    private void ensurePresence(final MemberRef sender) {
        if (chatPlatform.presence().of(sender) == PresenceStatus.UNKNOWN) {
            chatPlatform.presence().set(sender, PresenceStatus.ONLINE);
            broadcaster.broadcastPresenceReplace(sender, PresenceStatus.ONLINE);
        }
    }

    @GET
    @Path("/channels/{channelId}/messages")
    public List<ReceivedMessage> listMessages(@PathParam("channelId") final String channelId,
                                              @QueryParam("since") final String since) {
        final Instant sinceInstant;
        if (since != null) {
            try {
                sinceInstant = Instant.parse(since);
            } catch (final DateTimeParseException e) {
                throw new jakarta.ws.rs.BadRequestException("Invalid 'since' parameter: " + since);
            }
        } else {
            sinceInstant = Instant.EPOCH;
        }
        return chatPlatform.messageHistory().messages(new ChatChannelRef(channelId), sinceInstant);
    }

    // --- Replies ---

    @POST
    @Path("/channels/{channelId}/messages/{messageId}/replies")
    public Response postReply(@PathParam("channelId") final String channelId,
                              @PathParam("messageId") final String messageId,
                              final PostMessageRequest request) {
        final var channelRef = new ChatChannelRef(channelId);
        final var parentRef  = new ChatMessageRef(channelRef, messageId);
        final var sender     = new MemberRef(identity.getPrincipal().getName());
        ensureMembership(channelRef, sender);
        ensurePresence(sender);
        final var content = new ChatContent(request.text());
        final var msg     = chatBackend.storeMessage(PLATFORM_ID, channelRef, content, sender, parentRef);

        final String msgType       = request.messageType() != null ? request.messageType() : "EVENT";
        final String actType       = request.actorType() != null ? request.actorType() : "HUMAN";
        final var    parentFields  = chatBackend.getEnrichedFields(messageId);
        String       correlationId = null;
        if ("COMMAND".equals(parentFields.get("message_type"))) {
            correlationId = messageId;
        } else if (parentFields.get("correlation_id") != null) {
            correlationId = (String) parentFields.get("correlation_id");
        }
        String refsJson = "[]";
        try {
            if (request.artefactRefs() != null && !request.artefactRefs().isEmpty()) {
                refsJson = objectMapper.writeValueAsString(request.artefactRefs());
            }
        } catch (final JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        final String parentTopicId = (String) parentFields.getOrDefault("topic_id", chatBackend.getDefaultTopicId(channelId));
        chatBackend.storeEnrichedFields(msg.messageRef().messageId(), channelId,
                                        msgType, actType, correlationId, request.target(), refsJson, parentTopicId);

        broadcaster.broadcastMessageAppend(msg);
        return Response.ok(Map.of(
                "ok", true,
                "messageId", msg.messageRef().messageId(),
                "timestamp", msg.receivedAt().toString())).build();
    }

    // --- Reactions ---

    @POST
    @Path("/channels/{channelId}/messages/{messageId}/reactions")
    public Response addReaction(@PathParam("channelId") final String channelId,
                                @PathParam("messageId") final String messageId,
                                final ReactionRequest request) {
        chatPlatform.reactions().add(
                new ChatMessageRef(new ChatChannelRef(channelId), messageId), request.emoji());
        broadcaster.broadcastReactionAppend(messageId, request.emoji());
        return Response.ok().build();
    }

    @DELETE
    @Path("/channels/{channelId}/messages/{messageId}/reactions/{emoji}")
    public Response removeReaction(@PathParam("channelId") final String channelId,
                                   @PathParam("messageId") final String messageId,
                                   @PathParam("emoji") final String emoji) {
        chatPlatform.reactions().remove(
                new ChatMessageRef(new ChatChannelRef(channelId), messageId), emoji);
        broadcaster.broadcastReactionRemove(messageId, emoji);
        return Response.ok().build();
    }

    @GET
    @Path("/channels/{channelId}/messages/{messageId}/reactions")
    public List<String> listReactions(@PathParam("channelId") final String channelId,
                                      @PathParam("messageId") final String messageId) {
        return chatPlatform.reactions().list(
                new ChatMessageRef(new ChatChannelRef(channelId), messageId));
    }

    // --- Members ---

    @GET
    @Path("/channels/{channelId}/members")
    public List<Member> listMembers(@PathParam("channelId") final String channelId) {
        return chatPlatform.members().list(new ChatChannelRef(channelId));
    }

    @POST
    @Path("/channels/{channelId}/members")
    public Response addMember(@PathParam("channelId") final String channelId,
                              final AddMemberRequest request) {
        final var member = new Member(new MemberRef(request.memberId()), request.displayName());
        chatPlatform.memberManagement().add(new ChatChannelRef(channelId), member);
        broadcaster.broadcastMemberAppend(channelId, member);
        return Response.ok().build();
    }

    @DELETE
    @Path("/channels/{channelId}/members/{memberId}")
    public Response removeMember(@PathParam("channelId") final String channelId,
                                 @PathParam("memberId") final String memberId) {
        final var memberRef = new MemberRef(memberId);
        chatPlatform.memberManagement().remove(new ChatChannelRef(channelId), memberRef);
        broadcaster.broadcastMemberRemove(channelId, memberRef);
        return Response.ok().build();
    }

    // --- Presence ---

    @GET
    @Path("/presence/{memberId}")
    public Map<String, String> getPresence(@PathParam("memberId") final String memberId) {
        final PresenceStatus status = chatPlatform.presence().of(new MemberRef(memberId));
        return Map.of("memberId", memberId, "status", status.name());
    }

    @PUT
    @Path("/presence/{memberId}")
    public Response setPresence(@PathParam("memberId") final String memberId,
                                final SetPresenceRequest request) {
        try {
            final var memberRef = new MemberRef(memberId);
            final var status    = PresenceStatus.valueOf(request.status());
            chatPlatform.presence().set(memberRef, status);
            broadcaster.broadcastPresenceReplace(memberRef, status);
        } catch (final IllegalArgumentException e) {
            throw new jakarta.ws.rs.BadRequestException("Invalid status: " + request.status());
        }
        return Response.ok().build();
    }

    // --- Read tracking ---

    @PUT
    @Path("/channels/{channelId}/read")
    public Response markRead(@PathParam("channelId") final String channelId) {
        final var channelRef = new ChatChannelRef(channelId);
        final var memberRef  = new MemberRef(identity.getPrincipal().getName());
        chatBackend.markRead(channelRef, memberRef, java.time.Instant.now());
        return Response.ok().build();
    }

    // --- Request DTOs ---


    // --- Commitments ---

    @PATCH
    @Path("/channels/{channelId}/commitments/{commitmentId}")
    public Response updateCommitment(@PathParam("channelId") final String channelId,
                                     @PathParam("commitmentId") final String commitmentId,
                                     final UpdateCommitmentRequest request) {
        chatBackend.updateCommitmentState(commitmentId, request.state(), request.acknowledgedAt());
        broadcaster.broadcastCommitmentReplace(commitmentId, channelId);
        return Response.ok(Map.of("ok", true)).build();
    }

    @GET
    @Path("/channels/{channelId}/commitments")
    public List<Map<String, Object>> listCommitments(@PathParam("channelId") final String channelId) {
        return chatBackend.listCommitments(channelId);
    }

    // --- Correlation ---

    @GET
    @Path("/channels/{channelId}/correlation/{correlationId}")
    public List<Map<String, Object>> correlationChain(@PathParam("channelId") final String channelId,
                                                      @PathParam("correlationId") final String correlationId) {
        return chatBackend.correlationMessages(channelId, correlationId);
    }

    public record CreateChannelRequest(String name, String topic, String description, boolean isPrivate) {}

    public record PostMessageRequest(String text, String messageType, String actorType,
                                     String target, List<Map<String, Object>> artefactRefs) {}

    public record ReactionRequest(String emoji) {}

    public record AddMemberRequest(String memberId, String displayName) {}

    public record SetPresenceRequest(String status) {}

    public record UpdateCommitmentRequest(String state, String acknowledgedAt) {}

}
