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

}
