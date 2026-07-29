package io.casehub.chat.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelMembership;
import io.casehub.qhorus.api.channel.ChannelReader;
import io.casehub.qhorus.api.channel.PresenceStatus;
import io.casehub.qhorus.api.channel.PresenceTracker;
import io.casehub.qhorus.api.channel.TopicManager;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.ConsumerMessaging;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.Topic;
import io.casehub.qhorus.api.store.CommitmentReader;
import io.casehub.qhorus.api.store.MembershipReader;
import io.casehub.qhorus.api.store.ReactionReader;
import io.casehub.qhorus.api.store.TopicReader;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class ChatWebSocketBroadcaster {

    private static final List<Map<String, Object>> CHANNEL_COLUMNS    = List.of(
            Map.of("id", "id", "name", "ID", "type", "LABEL"),
            Map.of("id", "name", "name", "Name", "type", "LABEL"),
            Map.of("id", "topic", "name", "Topic", "type", "LABEL"),
            Map.of("id", "description", "name", "Description", "type", "LABEL"),
            Map.of("id", "isPrivate", "name", "Private", "type", "LABEL"));
    private static final List<Map<String, Object>> MESSAGE_COLUMNS    = List.of(
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
    private static final List<Map<String, Object>> MEMBER_COLUMNS     = List.of(
            Map.of("id", "membershipId", "name", "Membership", "type", "LABEL"),
            Map.of("id", "channelId", "name", "Channel", "type", "LABEL"),
            Map.of("id", "memberId", "name", "Member", "type", "LABEL"),
            Map.of("id", "displayName", "name", "Display Name", "type", "LABEL"),
            Map.of("id", "role", "name", "Role", "type", "LABEL"));
    private static final List<Map<String, Object>> PRESENCE_COLUMNS   = List.of(
            Map.of("id", "memberId", "name", "Member", "type", "LABEL"),
            Map.of("id", "status", "name", "Status", "type", "LABEL"),
            Map.of("id", "lastActiveAt", "name", "Last Active", "type", "DATE"));
    private static final List<Map<String, Object>> REACTION_COLUMNS   = List.of(
            Map.of("id", "messageId", "name", "Message ID", "type", "LABEL"),
            Map.of("id", "emoji", "name", "Emoji", "type", "LABEL"));
    private static final List<Map<String, Object>> COMMITMENT_COLUMNS = List.of(
            Map.of("id", "correlationId", "name", "Correlation", "type", "LABEL"),
            Map.of("id", "channelId", "name", "Channel", "type", "LABEL"),
            Map.of("id", "state", "name", "State", "type", "LABEL"),
            Map.of("id", "deadline", "name", "Deadline", "type", "DATE"),
            Map.of("id", "acknowledgedAt", "name", "Acknowledged", "type", "DATE"),
            Map.of("id", "resolvedAt", "name", "Resolved", "type", "DATE"),
            Map.of("id", "createdAt", "name", "Created", "type", "DATE"));
    private static final List<Map<String, Object>> TOPIC_COLUMNS      = List.of(
            Map.of("id", "topicId", "name", "Topic ID", "type", "LABEL"),
            Map.of("id", "channelId", "name", "Channel", "type", "LABEL"),
            Map.of("id", "name", "name", "Name", "type", "LABEL"),
            Map.of("id", "state", "name", "State", "type", "LABEL"),
            Map.of("id", "messageCount", "name", "Messages", "type", "LABEL"),
            Map.of("id", "latestActivityTs", "name", "Latest", "type", "DATE"),
            Map.of("id", "createdAt", "name", "Created", "type", "DATE"));
    private final        Set<WebSocketConnection>  connections        = new CopyOnWriteArraySet<>();
    private final        AtomicLong                seq                = new AtomicLong(0);

    @Inject
    ObjectMapper      objectMapper;
    @Inject
    ChannelReader     channelReader;
    @Inject
    ConsumerMessaging messaging;
    @Inject
    MembershipReader  memberReader;
    @Inject
    ReactionReader    reactionReader;
    @Inject
    CommitmentReader  commitmentReader;
    @Inject
    TopicReader       topicReader;
    @Inject
    PresenceTracker   presenceTracker;
    @Inject
    TopicManager      topicManager;

    void addConnection(WebSocketConnection connection) {
        connections.add(connection);
    }

    void removeConnection(WebSocketConnection connection) {
        connections.remove(connection);
    }

    void registerChannel(UUID channelId, String channelName) {
    }

    void deregisterChannel(ChannelRef channel) {
    }

    String buildSnapshot() {
        var channels = channelReader.listAll();

        var channelRows = channels.stream()
                                  .map(ch -> List.<Object>of(
                                          ch.id().toString(), ch.name(), "", ch.description() != null ? ch.description() : "", "false"))
                                  .toList();

        var topicRows = new ArrayList<List<Object>>();
        for (var ch : channels) {
            for (var ts : topicManager.listTopics(ch.id())) {
                var  topic   = topicReader.find(ch.id(), ts.name());
                Long topicId = topic.map(Topic::id).orElse(null);
                topicRows.add(List.of(
                        topicId != null ? String.valueOf(topicId) : ts.name(),
                        ch.id().toString(), ts.name(),
                        ts.resolved() ? "RESOLVED" : "ACTIVE",
                        String.valueOf(ts.messageCount()),
                        ts.lastActivityAt() != null ? ts.lastActivityAt().toString() : "",
                        topic.map(t -> t.createdAt().toString()).orElse("")));
            }
        }

        var messageRows = new ArrayList<List<Object>>();
        for (var ch : channels) {
            for (var msg : messaging.history(ch.id(), 0, 10000)) {
                messageRows.add(messageToRow(msg));
            }
        }

        var memberRows = new ArrayList<List<Object>>();
        for (var ch : channels) {
            for (var m : memberReader.findByChannel(ch.id())) {
                String membershipId = ch.id().toString() + ":" + m.memberId();
                memberRows.add(List.of(membershipId, ch.id().toString(), m.memberId(), m.memberId(), m.role().name()));
            }
        }

        var reactionRows = new ArrayList<List<Object>>();
        for (var ch : channels) {
            var msgs   = messaging.history(ch.id(), 0, 10000);
            var msgIds = msgs.stream().map(Message::id).toList();
            if (!msgIds.isEmpty()) {
                var reactionsMap = reactionReader.findByMessages(msgIds);
                for (var entry : reactionsMap.entrySet()) {
                    for (var r : entry.getValue()) {
                        reactionRows.add(List.of(String.valueOf(r.messageId()), r.emoji()));
                    }
                }
            }
        }

        var presenceRows = new ArrayList<List<Object>>();
        for (var ch : channels) {
            for (var p : presenceTracker.getChannelPresence(ch.id())) {
                presenceRows.add(List.of(p.memberId(), p.status().name(),
                                         p.lastSeenAt() != null ? p.lastSeenAt().toString() : ""));
            }
        }

        var commitmentRows = new ArrayList<List<Object>>();
        for (var ch : channels) {
            for (var c : commitmentReader.findByChannel(ch.id())) {
                commitmentRows.add(commitmentToRow(c));
            }
        }

        return toJson(List.of(
                Map.of("dataset", "channels", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                       "columns", CHANNEL_COLUMNS, "rows", channelRows),
                Map.of("dataset", "topics", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                       "columns", TOPIC_COLUMNS, "rows", topicRows),
                Map.of("dataset", "messages", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                       "columns", MESSAGE_COLUMNS, "rows", messageRows),
                Map.of("dataset", "members", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                       "columns", MEMBER_COLUMNS, "rows", memberRows),
                Map.of("dataset", "presence", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                       "columns", PRESENCE_COLUMNS, "rows", presenceRows),
                Map.of("dataset", "reactions", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                       "columns", REACTION_COLUMNS, "rows", reactionRows),
                Map.of("dataset", "commitments", "op", "snapshot", "seq", String.valueOf(seq.incrementAndGet()),
                       "columns", COMMITMENT_COLUMNS, "rows", commitmentRows)));
    }

    void pushMessage(ChannelRef channel, OutboundMessage message) {
        var row = new ArrayList<Object>(12);
        row.add(channel.id().toString());
        row.add(String.valueOf(message.sequenceId()));
        row.add(message.inReplyTo() != null ? String.valueOf(message.inReplyTo()) : null);
        row.add(message.sender());
        row.add(message.content());
        row.add(Instant.now().toString());
        row.add(message.type().name());
        row.add(message.senderActorType().name());
        row.add(message.topic() != null ? message.topic() : "");
        row.add(message.correlationId());
        String artefactRefsJson = "[]";
        if (message.artefactRefs() != null && !message.artefactRefs().isEmpty()) {
            artefactRefsJson = toJson(message.artefactRefs());
        }
        row.add(artefactRefsJson);
        row.add(message.target());
        broadcast(Map.of(
                "dataset", "messages", "op", "append",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", MESSAGE_COLUMNS,
                "rows", List.of(row)));}

    void broadcastChannelAppend(Channel channel) {
        broadcast(Map.of(
                "dataset", "channels", "op", "append",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", CHANNEL_COLUMNS,
                "rows", List.of(List.of(
                        channel.id().toString(), channel.name(), "",
                        channel.description() != null ? channel.description() : "", "false"))));
    }

    void broadcastChannelRemove(UUID channelId) {
        broadcast(Map.of(
                "dataset", "channels", "op", "remove",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", CHANNEL_COLUMNS,
                "key", channelId.toString()));
    }

    void broadcastPresenceReplace(String memberId, PresenceStatus status) {
        broadcast(Map.of(
                "dataset", "presence", "op", "replace",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", PRESENCE_COLUMNS,
                "key", memberId,
                "row", List.of(memberId, status.name(), Instant.now().toString())));
    }

    void broadcastMemberAppend(UUID channelId, ChannelMembership membership) {
        String membershipId = channelId.toString() + ":" + membership.memberId();
        broadcast(Map.of(
                "dataset", "members", "op", "append",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", MEMBER_COLUMNS,
                "rows", List.of(List.of(membershipId, channelId.toString(),
                                        membership.memberId(), membership.memberId(), membership.role().name()))));
    }

    void broadcastMemberRemove(UUID channelId, String memberId) {
        broadcast(Map.of(
                "dataset", "members", "op", "remove",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", MEMBER_COLUMNS,
                "key", channelId.toString() + ":" + memberId));
    }

    void broadcastReactionAppend(Long messageId, String emoji) {
        broadcast(Map.of(
                "dataset", "reactions", "op", "append",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", REACTION_COLUMNS,
                "rows", List.of(List.of(String.valueOf(messageId), emoji))));
    }

    void broadcastReactionRemove(Long messageId, String emoji) {
        broadcast(Map.of(
                "dataset", "reactions", "op", "remove",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", REACTION_COLUMNS,
                "key", String.valueOf(messageId) + ":" + emoji));
    }

    void broadcastCommitment(Commitment commitment) {
        broadcast(Map.of(
                "dataset", "commitments", "op", "replace",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", COMMITMENT_COLUMNS,
                "key", commitment.correlationId(),
                "row", commitmentToRow(commitment)));
    }

    void broadcastCommitmentAppend(Commitment commitment) {
        broadcast(Map.of(
                "dataset", "commitments", "op", "append",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", COMMITMENT_COLUMNS,
                "rows", List.of(commitmentToRow(commitment))));
    }

    void broadcastTopicAppend(UUID channelId, Topic topic) {
        broadcast(Map.of(
                "dataset", "topics", "op", "append",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", TOPIC_COLUMNS,
                "rows", List.of(topicToRow(channelId, topic))));
    }

    void broadcastTopicReplace(UUID channelId, Topic topic) {
        broadcast(Map.of(
                "dataset", "topics", "op", "replace",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", TOPIC_COLUMNS,
                "key", String.valueOf(topic.id()),
                "row", topicToRow(channelId, topic)));
    }

    void broadcastTopicRemove(UUID channelId, Long topicId) {
        broadcast(Map.of(
                "dataset", "topics", "op", "remove",
                "seq", String.valueOf(seq.incrementAndGet()),
                "columns", TOPIC_COLUMNS,
                "key", String.valueOf(topicId)));
    }

    private List<Object> topicToRow(UUID channelId, Topic topic) {
        return List.of(
                String.valueOf(topic.id()), channelId.toString(), topic.name(),
                topic.resolved() ? "RESOLVED" : "ACTIVE",
                "0", topic.createdAt() != null ? topic.createdAt().toString() : "",
                topic.createdAt() != null ? topic.createdAt().toString() : "");
    }

    private List<Object> commitmentToRow(Commitment c) {
        return List.of(
                c.correlationId(), c.channelId().toString(), c.state().name(),
                c.expiresAt() != null ? c.expiresAt().toString() : "",
                c.acknowledgedAt() != null ? c.acknowledgedAt().toString() : "",
                c.resolvedAt() != null ? c.resolvedAt().toString() : "",
                c.createdAt().toString());
    }

    private List<Object> messageToRow(Message msg) {
        var row = new ArrayList<Object>(12);
        row.add(msg.channelId().toString());
        row.add(String.valueOf(msg.id()));
        row.add(msg.inReplyTo() != null ? String.valueOf(msg.inReplyTo()) : null);
        row.add(msg.sender());
        row.add(msg.content());
        row.add(msg.createdAt().toString());
        row.add(msg.messageType().name());
        row.add(msg.actorType().name());
        String topicIdStr = "";
        if (msg.topic() != null && !msg.topic().isEmpty()) {
            var topic = topicReader.find(msg.channelId(), msg.topic());
            topicIdStr = topic.map(t -> String.valueOf(t.id())).orElse("");
        }
        row.add(topicIdStr);
        row.add(msg.correlationId());
        String artefactRefsJson = "[]";
        if (msg.artefactRefs() != null && !msg.artefactRefs().isEmpty()) {
            artefactRefsJson = toJson(msg.artefactRefs());
        }
        row.add(artefactRefsJson);
        row.add(msg.target());
        return row;
    }

    private void broadcast(Object event) {
        String json = toJson(event);
        connections.forEach(c -> c.sendText(json).subscribe().with(
                ignored -> {},
                err -> Log.warnf("WebSocket send failed: %s", err.getMessage())));
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialisation failed", e);
        }
    }
}
