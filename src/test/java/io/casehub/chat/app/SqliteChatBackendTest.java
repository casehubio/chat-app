package io.casehub.chat.app;

import io.casehub.connectors.chat.ref.ChatBackend;
import io.casehub.connectors.chat.ref.ChatBackendContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class SqliteChatBackendTest extends ChatBackendContract {

    @TempDir
    Path tempDir;

    private SqliteChatBackend backend;

    @Override
    protected ChatBackend createBackend() {
        final String path = tempDir.resolve("test-" + System.nanoTime() + ".db").toString();
        backend = new SqliteChatBackend(path);
        return backend;
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.close();
        }
    }

    @org.junit.jupiter.api.Test
    void memberRole_defaultsToParticipant() {
        final var channel = backend.createChannel("test-" + System.nanoTime(), null, null, false);
        backend.addMember(new io.casehub.connectors.chat.model.ChatChannelRef(channel.ref().id()),
                          new io.casehub.connectors.chat.model.Member(new io.casehub.connectors.chat.model.MemberRef("alice"), "Alice"));
        final String role = backend.memberRole(new io.casehub.connectors.chat.model.ChatChannelRef(channel.ref().id()),
                                               new io.casehub.connectors.chat.model.MemberRef("alice"));
        org.junit.jupiter.api.Assertions.assertEquals("PARTICIPANT", role);
    }

    @org.junit.jupiter.api.Test
    void setMemberRole_updatesRole() {
        final var channel    = backend.createChannel("test-" + System.nanoTime(), null, null, false);
        final var channelRef = new io.casehub.connectors.chat.model.ChatChannelRef(channel.ref().id());
        backend.addMember(channelRef, new io.casehub.connectors.chat.model.Member(new io.casehub.connectors.chat.model.MemberRef("alice"), "Alice"));
        backend.setMemberRole(channelRef, new io.casehub.connectors.chat.model.MemberRef("alice"), "MODERATOR");
        org.junit.jupiter.api.Assertions.assertEquals("MODERATOR", backend.memberRole(channelRef, new io.casehub.connectors.chat.model.MemberRef("alice")));
    }

    @org.junit.jupiter.api.Test
    void markRead_storesTimestamp() {
        final var channel    = backend.createChannel("test-" + System.nanoTime(), null, null, false);
        final var channelRef = new io.casehub.connectors.chat.model.ChatChannelRef(channel.ref().id());
        backend.addMember(channelRef, new io.casehub.connectors.chat.model.Member(new io.casehub.connectors.chat.model.MemberRef("alice"), "Alice"));
        final java.time.Instant now = java.time.Instant.now();
        backend.markRead(channelRef, new io.casehub.connectors.chat.model.MemberRef("alice"), now);
        final java.time.Instant lastRead = backend.lastReadAt(channelRef, new io.casehub.connectors.chat.model.MemberRef("alice"));
        org.junit.jupiter.api.Assertions.assertNotNull(lastRead);
        org.junit.jupiter.api.Assertions.assertEquals(now.getEpochSecond(), lastRead.getEpochSecond());
    }

    @org.junit.jupiter.api.Test
    void lastReadAt_returnsNullWhenNeverRead() {
        final var channel    = backend.createChannel("test-" + System.nanoTime(), null, null, false);
        final var channelRef = new io.casehub.connectors.chat.model.ChatChannelRef(channel.ref().id());
        backend.addMember(channelRef, new io.casehub.connectors.chat.model.Member(new io.casehub.connectors.chat.model.MemberRef("alice"), "Alice"));
        org.junit.jupiter.api.Assertions.assertNull(backend.lastReadAt(channelRef, new io.casehub.connectors.chat.model.MemberRef("alice")));
    }

    @org.junit.jupiter.api.Test
    void presenceLastActiveAt_updatedOnSetPresence() {
        final var memberRef = new io.casehub.connectors.chat.model.MemberRef("alice");
        backend.setPresence(memberRef, io.casehub.connectors.chat.model.PresenceStatus.ONLINE);
        final java.time.Instant lastActive = backend.lastActiveAt(memberRef);
        org.junit.jupiter.api.Assertions.assertNotNull(lastActive);
        org.junit.jupiter.api.Assertions.assertTrue(java.time.Duration.between(lastActive, java.time.Instant.now()).getSeconds() < 5);
    }

    @org.junit.jupiter.api.Test
    void enrichedColumns_existAfterInit() throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("test-" + System.nanoTime() + ".db"))) {
            // Create a backend that initializes the schema on this DB
            var b = new SqliteChatBackend(conn.getMetaData().getURL().replace("jdbc:sqlite:", ""));
            try {
                var rs = conn.createStatement().executeQuery(
                        "SELECT message_type, actor_type, correlation_id, target FROM messages LIMIT 0");
                org.junit.jupiter.api.Assertions.assertNotNull(rs.getMetaData());
                org.junit.jupiter.api.Assertions.assertEquals(4, rs.getMetaData().getColumnCount());
            } finally {
                b.close();
            }
        }
    }

    @org.junit.jupiter.api.Test
    void commitments_tableExists() throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("test-" + System.nanoTime() + ".db"))) {
            var b = new SqliteChatBackend(conn.getMetaData().getURL().replace("jdbc:sqlite:", ""));
            try {
                var rs = conn.createStatement().executeQuery(
                        "SELECT id, channel_id, state, deadline, acknowledged_at, created_at, updated_at FROM commitments LIMIT 0");
                org.junit.jupiter.api.Assertions.assertEquals(7, rs.getMetaData().getColumnCount());
            } finally {
                b.close();
            }
        }
    }

    @org.junit.jupiter.api.Test
    void artefactRefs_tableExists() throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("test-" + System.nanoTime() + ".db"))) {
            var b = new SqliteChatBackend(conn.getMetaData().getURL().replace("jdbc:sqlite:", ""));
            try {
                var rs = conn.createStatement().executeQuery(
                        "SELECT message_id, uri, type, label, start_line, end_line, start_offset, end_offset, selected_text FROM artefact_refs LIMIT 0");
                org.junit.jupiter.api.Assertions.assertEquals(9, rs.getMetaData().getColumnCount());
            } finally {
                b.close();
            }
        }
    }

    @org.junit.jupiter.api.Test
    void storeEnrichedFields_andRetrieve() {
        var ch = backend.createChannel("enr-" + System.nanoTime(), null, null, false);
        var msg = backend.storeMessage("ref", ch.ref(),
                                       new io.casehub.connectors.chat.model.ChatContent("hello"),
                                       new io.casehub.connectors.chat.model.MemberRef("alice"), null);
        backend.storeEnrichedFields(msg.messageRef().messageId(), ch.ref().id(),
                                    "COMMAND", "AGENT", msg.messageRef().messageId(), "bob", "[]", null);
        var fields = backend.getEnrichedFields(msg.messageRef().messageId());
        org.junit.jupiter.api.Assertions.assertEquals("COMMAND", fields.get("message_type"));
        org.junit.jupiter.api.Assertions.assertEquals("AGENT", fields.get("actor_type"));
        org.junit.jupiter.api.Assertions.assertEquals(msg.messageRef().messageId(), fields.get("correlation_id"));
        org.junit.jupiter.api.Assertions.assertEquals("bob", fields.get("target"));
    }

    @org.junit.jupiter.api.Test
    void createAndUpdateCommitment() {
        var ch = backend.createChannel("cmt-" + System.nanoTime(), null, null, false);
        var msg = backend.storeMessage("ref", ch.ref(),
                                       new io.casehub.connectors.chat.model.ChatContent("do this"),
                                       new io.casehub.connectors.chat.model.MemberRef("alice"), null);
        backend.createCommitment(msg.messageRef().messageId(), ch.ref().id(), null);
        var commitments = backend.listCommitments(ch.ref().id());
        org.junit.jupiter.api.Assertions.assertEquals(1, commitments.size());
        org.junit.jupiter.api.Assertions.assertEquals("OPEN", commitments.get(0).get("state"));

        backend.updateCommitmentState(msg.messageRef().messageId(), "FULFILLED", null);
        commitments = backend.listCommitments(ch.ref().id());
        org.junit.jupiter.api.Assertions.assertEquals("FULFILLED", commitments.get(0).get("state"));
    }

    @org.junit.jupiter.api.Test
    void storeAndRetrieveArtefactRefs() {
        var ch = backend.createChannel("art-" + System.nanoTime(), null, null, false);
        var msg = backend.storeMessage("ref", ch.ref(),
                                       new io.casehub.connectors.chat.model.ChatContent("see doc"),
                                       new io.casehub.connectors.chat.model.MemberRef("alice"), null);
        String refsJson = "[{\"uri\":\"docs/spec.md\",\"type\":\"DOCUMENT\",\"label\":\"Design Spec\",\"startLine\":10,\"endLine\":20}]";
        backend.storeEnrichedFields(msg.messageRef().messageId(), ch.ref().id(),
                                    "EVENT", "HUMAN", null, null, refsJson, null);
        String retrieved = backend.getArtefactRefsJson(msg.messageRef().messageId());
        org.junit.jupiter.api.Assertions.assertTrue(retrieved.contains("docs/spec.md"));
        org.junit.jupiter.api.Assertions.assertTrue(retrieved.contains("DOCUMENT"));
    }

    @org.junit.jupiter.api.Test
    void deleteChannel_cascadesToCommitmentsAndArtefactRefs() {
        var ch = backend.createChannel("cas-" + System.nanoTime(), null, null, false);
        var msg = backend.storeMessage("ref", ch.ref(),
                                       new io.casehub.connectors.chat.model.ChatContent("cmd"),
                                       new io.casehub.connectors.chat.model.MemberRef("alice"), null);
        backend.storeEnrichedFields(msg.messageRef().messageId(), ch.ref().id(),
                                    "COMMAND", "AGENT", null, null,
                                    "[{\"uri\":\"doc.md\",\"type\":\"DOCUMENT\",\"label\":\"Doc\"}]", null);
        backend.createCommitment(msg.messageRef().messageId(), ch.ref().id(), null);
        backend.deleteChannel(ch.ref().id());
        org.junit.jupiter.api.Assertions.assertTrue(backend.listCommitments(ch.ref().id()).isEmpty());
    }

    @org.junit.jupiter.api.Test
    void correlationMessages_returnsChain() {
        var ch = backend.createChannel("cor-" + System.nanoTime(), null, null, false);
        var cmd = backend.storeMessage("ref", ch.ref(),
                                       new io.casehub.connectors.chat.model.ChatContent("investigate"),
                                       new io.casehub.connectors.chat.model.MemberRef("alice"), null);
        backend.storeEnrichedFields(cmd.messageRef().messageId(), ch.ref().id(),
                                    "COMMAND", "AGENT", cmd.messageRef().messageId(), null, "[]", null);
        var reply = backend.storeMessage("ref", ch.ref(),
                                         new io.casehub.connectors.chat.model.ChatContent("working on it"),
                                         new io.casehub.connectors.chat.model.MemberRef("alice"),
                                         new io.casehub.connectors.chat.model.ChatMessageRef(ch.ref(), cmd.messageRef().messageId()));
        backend.storeEnrichedFields(reply.messageRef().messageId(), ch.ref().id(),
                                    "STATUS", "AGENT", cmd.messageRef().messageId(), null, "[]", null);
        var chain = backend.correlationMessages(ch.ref().id(), cmd.messageRef().messageId());
        org.junit.jupiter.api.Assertions.assertEquals(2, chain.size());
    }

    @org.junit.jupiter.api.Test
    void createChannel_autoCreatesGeneralTopic() {
        var channel = backend.createChannel("test-" + System.nanoTime(), null, null, false);
        var topics  = backend.listTopics(channel.ref().id());
        org.junit.jupiter.api.Assertions.assertEquals(1, topics.size());
        org.junit.jupiter.api.Assertions.assertEquals("General", topics.get(0).get("name"));
        org.junit.jupiter.api.Assertions.assertEquals("ACTIVE", topics.get(0).get("state"));
    }

    @org.junit.jupiter.api.Test
    void createTopic_returnsNewTopic() {
        var channel = backend.createChannel("test-" + System.nanoTime(), null, null, false);
        var topic   = backend.createTopic(channel.ref().id(), "deployment-pipeline");
        org.junit.jupiter.api.Assertions.assertNotNull(topic.get("id"));
        org.junit.jupiter.api.Assertions.assertEquals("deployment-pipeline", topic.get("name"));
        org.junit.jupiter.api.Assertions.assertEquals("ACTIVE", topic.get("state"));
    }

    @org.junit.jupiter.api.Test
    void createTopic_duplicateName_throws() {
        var channel = backend.createChannel("test-" + System.nanoTime(), null, null, false);
        backend.createTopic(channel.ref().id(), "my-topic");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                                                                                      backend.createTopic(channel.ref().id(), "my-topic"));
    }

    @org.junit.jupiter.api.Test
    void findTopicByName_returnsMatch() {
        var channel = backend.createChannel("test-" + System.nanoTime(), null, null, false);
        var found   = backend.findTopicByName(channel.ref().id(), "General");
        org.junit.jupiter.api.Assertions.assertTrue(found.isPresent());
        org.junit.jupiter.api.Assertions.assertEquals("General", found.get().get("name"));
    }

    @org.junit.jupiter.api.Test
    void findTopicByName_noMatch_returnsEmpty() {
        var channel = backend.createChannel("test-" + System.nanoTime(), null, null, false);
        org.junit.jupiter.api.Assertions.assertTrue(backend.findTopicByName(channel.ref().id(), "nonexistent").isEmpty());
    }

    @org.junit.jupiter.api.Test
    void updateTopic_rename() {
        var channel = backend.createChannel("test-" + System.nanoTime(), null, null, false);
        var topic   = backend.createTopic(channel.ref().id(), "old-name");
        backend.updateTopic((String) topic.get("id"), "new-name", null);
        var found = backend.findTopicById((String) topic.get("id"));
        org.junit.jupiter.api.Assertions.assertEquals("new-name", found.get().get("name"));
    }

    @org.junit.jupiter.api.Test
    void updateTopic_stateChange() {
        var channel = backend.createChannel("test-" + System.nanoTime(), null, null, false);
        var topic   = backend.createTopic(channel.ref().id(), "my-topic");
        backend.updateTopic((String) topic.get("id"), null, "RESOLVED");
        var found = backend.findTopicById((String) topic.get("id"));
        org.junit.jupiter.api.Assertions.assertEquals("RESOLVED", found.get().get("state"));
    }

    @org.junit.jupiter.api.Test
    void mergeTopic_movesMessagesAndSetsMerged() {
        var    channel    = backend.createChannel("test-" + System.nanoTime(), null, null, false);
        var    topicA     = backend.createTopic(channel.ref().id(), "topic-a");
        var    topicB     = backend.createTopic(channel.ref().id(), "topic-b");
        String topicAId   = (String) topicA.get("id");
        String topicBId   = (String) topicB.get("id");
        var    channelRef = new io.casehub.connectors.chat.model.ChatChannelRef(channel.ref().id());
        var    sender     = new io.casehub.connectors.chat.model.MemberRef("alice");
        backend.addMember(channelRef, new io.casehub.connectors.chat.model.Member(sender, "Alice"));
        var msg = backend.storeMessage("test", channelRef,
                                       new io.casehub.connectors.chat.model.ChatContent("hello"), sender, null);
        backend.storeEnrichedFields(msg.messageRef().messageId(), channel.ref().id(),
                                    "EVENT", "HUMAN", null, null, "[]", topicAId);
        backend.mergeTopic(topicAId, topicBId);
        var sourceTopic = backend.findTopicById(topicAId);
        org.junit.jupiter.api.Assertions.assertEquals("MERGED", sourceTopic.get().get("state"));
        org.junit.jupiter.api.Assertions.assertEquals(topicBId, sourceTopic.get().get("merged_into"));
    }

    @org.junit.jupiter.api.Test
    void deleteChannel_cascadesTopics() {
        var channel = backend.createChannel("test-" + System.nanoTime(), null, null, false);
        backend.createTopic(channel.ref().id(), "extra-topic");
        backend.deleteChannel(channel.ref().id());
        org.junit.jupiter.api.Assertions.assertTrue(backend.listTopics(channel.ref().id()).isEmpty());
    }

    @org.junit.jupiter.api.Test
    void getDefaultTopicId_returnsGeneralTopicId() {
        var    channel   = backend.createChannel("test-" + System.nanoTime(), null, null, false);
        String defaultId = backend.getDefaultTopicId(channel.ref().id());
        org.junit.jupiter.api.Assertions.assertNotNull(defaultId);
        var topic = backend.findTopicById(defaultId);
        org.junit.jupiter.api.Assertions.assertEquals("General", topic.get().get("name"));
    }


}
