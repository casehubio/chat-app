package io.casehub.chat.app;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import io.quarkus.websockets.next.WebSocketConnection;

import io.casehub.connectors.chat.model.Channel;
import io.casehub.connectors.chat.model.Member;
import io.casehub.connectors.chat.model.MemberRef;
import io.casehub.connectors.chat.model.PresenceStatus;
import io.casehub.connectors.chat.model.ReceivedMessage;
import io.casehub.connectors.chat.spi.ChatPlatform;

@ApplicationScoped
public class ChatWebSocketBroadcaster {

    private final Set<WebSocketConnection> connections = new CopyOnWriteArraySet<>();
    private final AtomicLong seq = new AtomicLong(0);

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
            Map.of("id", "timestamp", "name", "Timestamp", "type", "DATE"));

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

    private static final List<Map<String, Object>> REACTION_COLUMNS = List.of(
            Map.of("id", "messageId", "name", "Message ID", "type", "LABEL"),
            Map.of("id", "emoji", "name", "Emoji", "type", "LABEL"));

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
                        ch.topic(),
                        ch.description(),
                        String.valueOf(ch.isPrivate())))
                .toList();

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
                final String role = chatBackend.memberRole(ch.ref(), m.ref());
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
                    final PresenceStatus status = chatPlatform.presence().of(memberRef);
                    final java.time.Instant lastActive = chatBackend.lastActiveAt(memberRef);
                    final String lastActiveStr = lastActive != null ? lastActive.toString() : "";
                    return List.<Object>of(memberRef.id(), status.name(), lastActiveStr);
                })
                .toList();

        return toJson(List.of(
                Map.of("dataset", "channels", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                        "columns", CHANNEL_COLUMNS, "rows", channelRows),
                Map.of("dataset", "messages", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                        "columns", MESSAGE_COLUMNS, "rows", messages),
                Map.of("dataset", "members", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                        "columns", MEMBER_COLUMNS, "rows", members),
                Map.of("dataset", "presence", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                        "columns", PRESENCE_COLUMNS, "rows", presenceRows),
                Map.of("dataset", "reactions", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                        "columns", REACTION_COLUMNS, "rows", reactions)));
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

    private void broadcast(final Object event) {
        final String json = toJson(event);
        connections.forEach(c -> c.sendText(json).subscribe().with(
                ignored -> {},
                err -> Log.warnf("WebSocket send failed: %s", err.getMessage())));
    }

    private List<Object> messageToRow(final ReceivedMessage msg) {
        final var row = new java.util.ArrayList<Object>(6);
        row.add(msg.channel().id());
        row.add(msg.messageRef().messageId());
        row.add(msg.parentRef() != null ? msg.parentRef().messageId() : null);
        row.add(msg.sender().id());
        row.add(msg.content().text());
        row.add(msg.receivedAt().toString());
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
