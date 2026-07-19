package io.casehub.chat.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.connectors.chat.model.Channel;
import io.casehub.connectors.chat.model.Member;
import io.casehub.connectors.chat.model.MemberRef;
import io.casehub.connectors.chat.model.PresenceStatus;
import io.casehub.connectors.chat.model.ReceivedMessage;
import io.casehub.connectors.chat.spi.ChatPlatform;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class ChatWebSocketBroadcaster {

    // Column definitions
    private static final List<Map<String, Object>> CHANNEL_COLUMNS = List.of(
            Map.of("id", "id", "name", "ID", "type", "LABEL"),
            Map.of("id", "name", "name", "Name", "type", "LABEL"),
            Map.of("id", "topic", "name", "Topic", "type", "LABEL"),
            Map.of("id", "description", "name", "Description", "type", "LABEL"),
            Map.of("id", "isPrivate", "name", "Private", "type", "LABEL"));
    private static final List<Map<String, Object>> MESSAGE_COLUMNS = List.of(
            Map.of("id", "channelId", "name", "Channel", "type", "LABEL"),
            Map.of("id", "messageId", "name", "Message ID", "type", "LABEL"),
            Map.of("id", "parentId", "name", "Parent", "type", "LABEL"),
            Map.of("id", "senderId", "name", "Sender", "type", "LABEL"),
            Map.of("id", "text", "name", "Text", "type", "LABEL"),
            Map.of("id", "timestamp", "name", "Timestamp", "type", "DATE"),
            Map.of("id", "messageType", "name", "Type", "type", "LABEL"),
            Map.of("id", "actorType", "name", "Actor", "type", "LABEL"),
            Map.of("id", "topicId", "name", "Topic", "type", "LABEL"),
            Map.of("id", "correlationId", "name", "Correlation", "type", "LABEL"),
            Map.of("id", "artefactRefs", "name", "Artefacts", "type", "LABEL"),
            Map.of("id", "target", "name", "Target", "type", "LABEL"));
    private static final List<Map<String, Object>> MEMBER_COLUMNS = List.of(
            Map.of("id", "membershipId", "name", "Membership", "type", "LABEL"),
            Map.of("id", "channelId", "name", "Channel", "type", "LABEL"),
            Map.of("id", "memberId", "name", "Member", "type", "LABEL"),
            Map.of("id", "displayName", "name", "Display Name", "type", "LABEL"),
            Map.of("id", "role", "name", "Role", "type", "LABEL"));
    private static final List<Map<String, Object>> PRESENCE_COLUMNS = List.of(
            Map.of("id", "memberId", "name", "Member", "type", "LABEL"),
            Map.of("id", "status", "name", "Status", "type", "LABEL"),
            Map.of("id", "lastActiveAt", "name", "Last Active", "type", "DATE"));
    private static final List<Map<String, Object>> REACTION_COLUMNS   = List.of(
            Map.of("id", "messageId", "name", "Message ID", "type", "LABEL"),
            Map.of("id", "emoji", "name", "Emoji", "type", "LABEL"));
    private static final List<Map<String, Object>> COMMITMENT_COLUMNS = List.of(
            Map.of("id", "commitmentId", "name", "ID", "type", "LABEL"),
            Map.of("id", "channelId", "name", "Channel", "type", "LABEL"),
            Map.of("id", "state", "name", "State", "type", "LABEL"),
            Map.of("id", "deadline", "name", "Deadline", "type", "DATE"),
            Map.of("id", "acknowledgedAt", "name", "Acknowledged", "type", "DATE"),
            Map.of("id", "createdAt", "name", "Created", "type", "DATE"),
            Map.of("id", "updatedAt", "name", "Updated", "type", "DATE"));
    private static final List<Map<String, Object>> TOPIC_COLUMNS = List.of(
            Map.of("id", "topicId", "name", "Topic ID", "type", "LABEL"),
            Map.of("id", "channelId", "name", "Channel", "type", "LABEL"),
            Map.of("id", "name", "name", "Name", "type", "LABEL"),
            Map.of("id", "state", "name", "State", "type", "LABEL"),
            Map.of("id", "messageCount", "name", "Messages", "type", "LABEL"),
            Map.of("id", "latestActivityTs", "name", "Latest", "type", "DATE"),
            Map.of("id", "createdAt", "name", "Created", "type", "DATE"));
    private final Set<WebSocketConnection> connections = new CopyOnWriteArraySet<>();
    private final AtomicLong               seq         = new AtomicLong(0);
    @Inject
    ObjectMapper objectMapper;

    @Inject
    ChatPlatform chatPlatform;

    @Inject
    SqliteChatBackend chatBackend;

    void addConnection(final WebSocketConnection connection) {
        connections.add(connection);
    }

    void removeConnection(final WebSocketConnection connection) {
        connections.remove(connection);
    }

    String buildSnapshot() {
        final var channels = chatPlatform.discovery().listChannels();

        // Channels dataset
        final var channelRows = channels.stream()
                                        .map(ch -> List.of(
                                                ch.ref().id(),
                                                ch.name(),
                                                ch.topic() != null ? ch.topic() : "",
                                                ch.description() != null ? ch.description() : "",
                                                String.valueOf(ch.isPrivate())))
                                        .toList();

        // Topics dataset (before messages — adapter needs topics to resolve topicId)
        final var topicRows = new java.util.ArrayList<List<Object>>();
        for (final Channel ch : channels) {
            for (final var t : chatBackend.listTopics(ch.ref().id())) {
                topicRows.add(List.of(
                        t.get("id"), t.get("channelId"), t.get("name"), t.get("state"),
                        String.valueOf(t.get("messageCount")),
                        t.get("latestActivityTs") != null ? t.get("latestActivityTs") : "",
                        t.get("createdAt")));
            }
        }

        // Messages dataset
        final var messages = new java.util.ArrayList<List<Object>>();
        for (final Channel ch : channels) {
            for (final ReceivedMessage msg : chatPlatform.messageHistory().messages(ch.ref(), java.time.Instant.EPOCH)) {
                messages.add(messageToRow(msg));
            }
        }

        // Members dataset with membershipId
        final var members = new java.util.ArrayList<List<Object>>();
        for (final Channel ch : channels) {
            for (final Member m : chatPlatform.members().list(ch.ref())) {
                final String membershipId = ch.ref().id() + ":" + m.ref().id();
                final String role         = chatBackend.memberRole(ch.ref(), m.ref());
                members.add(List.of(membershipId, ch.ref().id(), m.ref().id(), m.displayName(), role));
            }
        }

        // Reactions dataset
        final var reactions = new java.util.ArrayList<List<Object>>();
        for (final Channel ch : channels) {
            for (final ReceivedMessage msg : chatPlatform.messageHistory().messages(ch.ref(), java.time.Instant.EPOCH)) {
                for (final String emoji : chatPlatform.reactions().list(msg.messageRef())) {
                    reactions.add(List.of(msg.messageRef().messageId(), emoji));
                }
            }
        }

        // Presence dataset - collect unique members across all channels
        final var uniqueMembers = new LinkedHashSet<MemberRef>();
        for (final Channel ch : channels) {
            for (final Member m : chatPlatform.members().list(ch.ref())) {
                uniqueMembers.add(m.ref());
            }
        }
        final var presenceRows = uniqueMembers.stream()
                                              .map(memberRef -> {
                                                  final PresenceStatus    status        = chatPlatform.presence().of(memberRef);
                                                  final java.time.Instant lastActive    = chatBackend.lastActiveAt(memberRef);
                                                  final String            lastActiveStr = lastActive != null ? lastActive.toString() : "";
                                                  return List.<Object>of(memberRef.id(), status.name(), lastActiveStr);
                                              })
                                              .toList();

        final var commitmentRows = new java.util.ArrayList<java.util.List<Object>>();
        for (final Channel ch : channels) {
            for (final var c : chatBackend.listCommitments(ch.ref().id())) {
                commitmentRows.add(commitmentToRow(c));
            }
        }

        return toJson(List.of(
                Map.of("dataset", "channels", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                       "columns", CHANNEL_COLUMNS, "rows", channelRows),
                Map.of("dataset", "topics", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                       "columns", TOPIC_COLUMNS, "rows", topicRows),
                Map.of("dataset", "messages", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                       "columns", MESSAGE_COLUMNS, "rows", messages),
                Map.of("dataset", "members", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                       "columns", MEMBER_COLUMNS, "rows", members),
                Map.of("dataset", "presence", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                       "columns", PRESENCE_COLUMNS, "rows", presenceRows),
                Map.of("dataset", "reactions", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                       "columns", REACTION_COLUMNS, "rows", reactions),
                Map.of("dataset", "commitments", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                       "columns", COMMITMENT_COLUMNS, "rows", commitmentRows)));
    }

    void broadcastMessageAppend(final ReceivedMessage msg) {
        broadcast(Map.of(
                "dataset", "messages",
                "op", "append",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", MESSAGE_COLUMNS,
                "rows", List.of(messageToRow(msg))));
    }

    void broadcastChannelAppend(final Channel channel) {
        broadcast(Map.of(
                "dataset", "channels",
                "op", "append",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", CHANNEL_COLUMNS,
                "rows", List.of(List.of(
                        channel.ref().id(),
                        channel.name(),
                        channel.topic(),
                        channel.description(),
                        String.valueOf(channel.isPrivate())))));
    }

    void broadcastPresenceReplace(final MemberRef member, final PresenceStatus status) {
        broadcast(Map.of(
                "dataset", "presence",
                "op", "replace",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", PRESENCE_COLUMNS,
                "key", member.id(),
                "row", List.of(member.id(), status.name(), java.time.Instant.now().toString())));
    }

    void broadcastMemberAppend(final String channelId, final Member member) {
        final String membershipId = channelId + ":" + member.ref().id();
        broadcast(Map.of(
                "dataset", "members",
                "op", "append",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", MEMBER_COLUMNS,
                "rows", List.of(List.of(membershipId, channelId, member.ref().id(), member.displayName(), "PARTICIPANT"))));
    }

    void broadcastMemberRemove(final String channelId, final MemberRef member) {
        broadcast(Map.of(
                "dataset", "members",
                "op", "remove",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", MEMBER_COLUMNS,
                "key", channelId + ":" + member.id()));
    }

    void broadcastReactionAppend(final String messageId, final String emoji) {
        broadcast(Map.of(
                "dataset", "reactions",
                "op", "append",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", REACTION_COLUMNS,
                "rows", List.of(List.of(messageId, emoji))));
    }

    void broadcastReactionRemove(final String messageId, final String emoji) {
        broadcast(Map.of(
                "dataset", "reactions",
                "op", "remove",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", REACTION_COLUMNS,
                "key", messageId + ":" + emoji));
    }

    void broadcastChannelRemove(final String channelId) {
        broadcast(Map.of(
                "dataset", "channels",
                "op", "remove",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", CHANNEL_COLUMNS,
                "key", channelId));
    }


    void broadcastCommitmentAppend(final String commitmentId, final String channelId) {
        chatBackend.listCommitments(channelId).stream()
                   .filter(c -> commitmentId.equals(c.get("id")))
                   .findFirst()
                   .ifPresent(c -> broadcast(java.util.Map.of(
                           "dataset", "commitments", "op", "append",
                           "seq", String.valueOf(seq.incrementAndGet()),
                           "columns", COMMITMENT_COLUMNS,
                           "rows", java.util.List.of(commitmentToRow(c)))));
    }

    void broadcastCommitmentReplace(final String commitmentId, final String channelId) {
        chatBackend.listCommitments(channelId).stream()
                   .filter(c -> commitmentId.equals(c.get("id")))
                   .findFirst()
                   .ifPresent(c -> broadcast(java.util.Map.of(
                           "dataset", "commitments", "op", "replace",
                           "seq", String.valueOf(seq.incrementAndGet()),
                           "columns", COMMITMENT_COLUMNS,
                           "key", commitmentId,
                           "row", commitmentToRow(c))));
    }

    void broadcastTopicAppend(final String channelId) {
        final var topics = chatBackend.listTopics(channelId);
        if (!topics.isEmpty()) {
            final var latest = topics.get(topics.size() - 1);
            broadcast(java.util.Map.of(
                    "dataset", "topics", "op", "append",
                    "seq", String.valueOf(seq.incrementAndGet()),
                    "columns", TOPIC_COLUMNS,
                    "rows", java.util.List.of(topicToRow(latest))));
        }
    }

    void broadcastTopicReplace(final String channelId, final String topicId) {
        chatBackend.listTopics(channelId).stream()
                   .filter(t -> topicId.equals(t.get("id")))
                   .findFirst()
                   .ifPresent(t -> broadcast(java.util.Map.of(
                           "dataset", "topics", "op", "replace",
                           "seq", String.valueOf(seq.incrementAndGet()),
                           "columns", TOPIC_COLUMNS,
                           "key", topicId,
                           "row", topicToRow(t))));
    }

    void broadcastTopicRemove(final String channelId, final String topicId) {
        broadcast(java.util.Map.of(
                "dataset", "topics", "op", "remove",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", TOPIC_COLUMNS,
                "key", topicId));
    }

    private java.util.List<Object> topicToRow(final java.util.Map<String, Object> t) {
        return java.util.List.of(
                t.get("id"), t.get("channelId"), t.get("name"), t.get("state"),
                String.valueOf(t.get("messageCount")),
                t.get("latestActivityTs") != null ? t.get("latestActivityTs") : "",
                t.get("createdAt"));
    }


    private java.util.List<Object> commitmentToRow(final java.util.Map<String, Object> c) {
        return java.util.List.of(
                c.get("id"), c.get("channel_id"), c.get("state"),
                c.get("deadline") != null ? c.get("deadline") : "",
                c.get("acknowledged_at") != null ? c.get("acknowledged_at") : "",
                c.get("created_at"), c.get("updated_at"));
    }

    private void broadcast(final Object event) {
        final String json = toJson(event);
        connections.forEach(c -> c.sendText(json).subscribe().with(
                ignored -> {},
                err -> Log.warnf("WebSocket send failed: %s", err.getMessage())));
    }

    private List<Object> messageToRow(final ReceivedMessage msg) {
        final var row = new java.util.ArrayList<Object>(12);
        row.add(msg.channel().id());
        row.add(msg.messageRef().messageId());
        row.add(msg.parentRef() != null ? msg.parentRef().messageId() : null);
        row.add(msg.sender().id());
        row.add(msg.content().text());
        row.add(msg.receivedAt().toString());
        final var enriched = chatBackend.getEnrichedFields(msg.messageRef().messageId());
        row.add(enriched.getOrDefault("message_type", "EVENT"));
        row.add(enriched.getOrDefault("actor_type", "HUMAN"));
        row.add(enriched.getOrDefault("topic_id", ""));
        row.add(enriched.get("correlation_id"));
        row.add(chatBackend.getArtefactRefsJson(msg.messageRef().messageId()));
        row.add(enriched.get("target"));
        return row;
    }

    private String toJson(final Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (final JsonProcessingException e) {
            throw new RuntimeException("JSON serialisation failed", e);
        }
    }
}
