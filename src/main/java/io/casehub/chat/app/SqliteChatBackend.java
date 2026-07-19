package io.casehub.chat.app;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.casehub.connectors.chat.model.Channel;
import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.casehub.connectors.chat.model.Member;
import io.casehub.connectors.chat.model.MemberRef;
import io.casehub.connectors.chat.model.PresenceStatus;
import io.casehub.connectors.chat.model.ReceivedMessage;
import io.casehub.connectors.chat.ref.ChatBackend;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class SqliteChatBackend implements ChatBackend {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    @ConfigProperty(name = "casehub.chat.backend.path", defaultValue = "chat-demo.db")
    String dbPath;

    @ConfigProperty(name = "casehub.chat.backend.seed")
    Optional<String> seedResource;

    private HikariDataSource dataSource;

    public SqliteChatBackend() {}

    public SqliteChatBackend(final String dbPath) {
        this.dbPath = dbPath;
        this.seedResource = Optional.empty();
        init();
    }

    @PostConstruct
    void init() {
        seedIfNeeded();

        final SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5000);

        final SQLiteDataSource sqDs = new SQLiteDataSource(config);
        sqDs.setUrl("jdbc:sqlite:" + dbPath);

        final HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(sqDs);
        hikari.setMaximumPoolSize(5);
        hikari.setMinimumIdle(1);
        dataSource = new HikariDataSource(hikari);

        createSchema();
    }

    @PreDestroy
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private void seedIfNeeded() {
        if (seedResource == null || seedResource.isEmpty()) return;
        final String seed = seedResource.get();
        final Path target = Path.of(dbPath);
        if (Files.exists(target)) return;
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(seed)) {
            if (is != null) {
                Files.copy(is, target);
            }
        } catch (final IOException e) {
            throw new RuntimeException("Failed to copy seed database: " + seed, e);
        }
    }

    private void createSchema() {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                               CREATE TABLE IF NOT EXISTS channels (
                                                  id          TEXT PRIMARY KEY,
                                                  name        TEXT NOT NULL UNIQUE,
                                                  topic       TEXT,
                                                  description TEXT,
                                                  is_private  INTEGER NOT NULL DEFAULT 0
                                              )""");
            stmt.executeUpdate("""
                               CREATE TABLE IF NOT EXISTS messages (
                                   id          TEXT PRIMARY KEY,
                                   platform_id TEXT NOT NULL,
                                   channel_id  TEXT NOT NULL,
                                   parent_id   TEXT,
                                   sender_id   TEXT NOT NULL,
                                   content     TEXT NOT NULL,
                                   created_at  TEXT NOT NULL,
                                   FOREIGN KEY (channel_id) REFERENCES channels(id)
                               )""");
            stmt.executeUpdate("""
                               CREATE TABLE IF NOT EXISTS reactions (
                                   message_id  TEXT NOT NULL,
                                   emoji       TEXT NOT NULL,
                                   PRIMARY KEY (message_id, emoji)
                               )""");
            stmt.executeUpdate("""
                               CREATE TABLE IF NOT EXISTS presence (
                                   member_id      TEXT PRIMARY KEY,
                                   status         TEXT NOT NULL,
                                   last_active_at TEXT
                               )""");
            stmt.executeUpdate("""
                               CREATE TABLE IF NOT EXISTS members (
                                   channel_id   TEXT NOT NULL,
                                   member_id    TEXT NOT NULL,
                                   display_name TEXT,
                                   role         TEXT NOT NULL DEFAULT 'PARTICIPANT',
                                   last_read_at TEXT,
                                   PRIMARY KEY (channel_id, member_id)
                               )""");
            stmt.executeUpdate("""
                               CREATE TABLE IF NOT EXISTS commitments (
                                   id              TEXT PRIMARY KEY,
                                   channel_id      TEXT NOT NULL,
                                   state           TEXT NOT NULL DEFAULT 'OPEN',
                                   deadline        TEXT,
                                   acknowledged_at TEXT,
                                   created_at      TEXT NOT NULL,
                                   updated_at      TEXT NOT NULL,
                                   FOREIGN KEY (channel_id) REFERENCES channels(id)
                               )""");
            stmt.executeUpdate("""
                               CREATE TABLE IF NOT EXISTS artefact_refs (
                                   message_id    TEXT NOT NULL,
                                   uri           TEXT NOT NULL,
                                   type          TEXT NOT NULL,
                                   label         TEXT NOT NULL,
                                   start_line    INTEGER,
                                   end_line      INTEGER,
                                   start_offset  INTEGER,
                                   end_offset    INTEGER,
                                   selected_text TEXT,
                                   FOREIGN KEY (message_id) REFERENCES messages(id)
                               )""");
            stmt.executeUpdate("""
                               CREATE TABLE IF NOT EXISTS topics (
                                   id          TEXT PRIMARY KEY,
                                   channel_id  TEXT NOT NULL,
                                   name        TEXT NOT NULL,
                                   state       TEXT NOT NULL DEFAULT 'ACTIVE',
                                   merged_into TEXT,
                                   created_at  TEXT NOT NULL,
                                   updated_at  TEXT NOT NULL,
                                   UNIQUE(channel_id, name),
                                   FOREIGN KEY (channel_id) REFERENCES channels(id),
                                   FOREIGN KEY (merged_into) REFERENCES topics(id)
                               )""");
            addColumnIfMissing(conn, "messages", "topic_id", "TEXT");
            addColumnIfMissing(conn, "members", "role", "TEXT NOT NULL DEFAULT 'PARTICIPANT'");
            addColumnIfMissing(conn, "members", "last_read_at", "TEXT");
            addColumnIfMissing(conn, "presence", "last_active_at", "TEXT");
            addColumnIfMissing(conn, "messages", "message_type", "TEXT NOT NULL DEFAULT 'EVENT'");
            addColumnIfMissing(conn, "messages", "actor_type", "TEXT NOT NULL DEFAULT 'HUMAN'");
            addColumnIfMissing(conn, "messages", "correlation_id", "TEXT");
            addColumnIfMissing(conn, "messages", "target", "TEXT");
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to create SQLite schema", e);
        }
    }

    private void addColumnIfMissing(final Connection conn, final String table, final String column, final String type) {
        try (var rs = conn.createStatement().executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) return;
            }
        } catch (final SQLException e) {
            return;
        }
        try (var stmt = conn.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (final SQLException ignored) {
        }
    }

    @Override
    public Channel createChannel(final String name, final String topic,
                                 final String description, final boolean isPrivate) {
        final String now = java.time.Instant.now().toString();
        final String id  = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO channels (id, name, topic, description, is_private) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, name);
                ps.setString(3, topic);
                ps.setString(4, description);
                ps.setInt(5, isPrivate ? 1 : 0);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO topics (id, channel_id, name, state, created_at, updated_at) VALUES (?, ?, 'General', 'ACTIVE', ?, ?)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, id);
                ps.setString(3, now);
                ps.setString(4, now);
                ps.executeUpdate();
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to create channel", e);
        }
        return new Channel(new ChatChannelRef(id), name, topic, description, isPrivate);}

    @Override
    public void deleteChannel(final String channelId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM artefact_refs WHERE message_id IN " +
                        "(SELECT id FROM messages WHERE channel_id = ?)")) {
                    ps.setString(1, channelId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM commitments WHERE channel_id = ?")) {
                    ps.setString(1, channelId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM reactions WHERE message_id IN " +
                        "(SELECT id FROM messages WHERE channel_id = ?)")) {
                    ps.setString(1, channelId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM messages WHERE channel_id = ?")) {
                    ps.setString(1, channelId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM topics WHERE channel_id = ?")) {
                    ps.setString(1, channelId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM members WHERE channel_id = ?")) {
                    ps.setString(1, channelId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM channels WHERE id = ?")) {
                    ps.setString(1, channelId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (final SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to delete channel", e);
        }
    }

    @Override
    public Optional<Channel> findChannel(final String channelId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, name, topic, description, is_private FROM channels WHERE id = ?")) {
            ps.setString(1, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(channelFromRow(rs));
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to find channel", e);
        }
        return Optional.empty();
    }

    @Override
    public List<Channel> listChannels() {
        final List<Channel> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, name, topic, description, is_private FROM channels ORDER BY name")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(channelFromRow(rs));
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to list channels", e);
        }
        return result;
    }

    @Override
    public ReceivedMessage storeMessage(final String platformId, final ChatChannelRef channel,
                                         final ChatContent content, final MemberRef sender,
                                         final ChatMessageRef parentRef) {
        final String id = UUID.randomUUID().toString();
        final Instant now = Instant.now();
        final String ts = TS_FORMAT.format(now);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO messages (id, platform_id, channel_id, parent_id, sender_id, content, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, platformId);
            ps.setString(3, channel.id());
            ps.setString(4, parentRef != null ? parentRef.messageId() : null);
            ps.setString(5, sender.id());
            ps.setString(6, content.text());
            ps.setString(7, ts);
            ps.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to store message", e);
        }
        final ChatMessageRef ref = new ChatMessageRef(channel, id);
        return new ReceivedMessage(platformId, channel, ref, parentRef, sender, content, now);
    }

    @Override
    public List<ReceivedMessage> messages(final ChatChannelRef channel, final Instant since) {
        final String sinceTs = TS_FORMAT.format(since);
        final List<ReceivedMessage> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, platform_id, channel_id, parent_id, sender_id, content, created_at FROM messages WHERE channel_id = ? AND created_at >= ? ORDER BY created_at")) {
            ps.setString(1, channel.id());
            ps.setString(2, sinceTs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(messageFromRow(rs, channel));
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to query messages", e);
        }
        return result;
    }

    @Override
    public void addReaction(final ChatMessageRef message, final String emoji) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR IGNORE INTO reactions (message_id, emoji) VALUES (?, ?)")) {
            ps.setString(1, message.messageId());
            ps.setString(2, emoji);
            ps.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to add reaction", e);
        }
    }

    @Override
    public void removeReaction(final ChatMessageRef message, final String emoji) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM reactions WHERE message_id = ? AND emoji = ?")) {
            ps.setString(1, message.messageId());
            ps.setString(2, emoji);
            ps.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to remove reaction", e);
        }
    }

    @Override
    public List<String> reactions(final ChatMessageRef message) {
        final List<String> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT emoji FROM reactions WHERE message_id = ? ORDER BY rowid")) {
            ps.setString(1, message.messageId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to list reactions", e);
        }
        return result;
    }

    @Override
    public void setPresence(final MemberRef member, final PresenceStatus status) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR REPLACE INTO presence (member_id, status, last_active_at) VALUES (?, ?, ?)")) {
            ps.setString(1, member.id());
            ps.setString(2, status.name());
            ps.setString(3, TS_FORMAT.format(Instant.now()));
            ps.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to set presence", e);
        }
    }

    @Override
    public PresenceStatus presence(final MemberRef member) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM presence WHERE member_id = ?")) {
            ps.setString(1, member.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return PresenceStatus.valueOf(rs.getString(1));
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to query presence", e);
        }
        return PresenceStatus.UNKNOWN;
    }

    @Override
    public void addMember(final ChatChannelRef channel, final Member member) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR IGNORE INTO members (channel_id, member_id, display_name) VALUES (?, ?, ?)")) {
            ps.setString(1, channel.id());
            ps.setString(2, member.ref().id());
            ps.setString(3, member.displayName());
            ps.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to add member", e);
        }
    }

    @Override
    public void removeMember(final ChatChannelRef channel, final MemberRef member) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM members WHERE channel_id = ? AND member_id = ?")) {
            ps.setString(1, channel.id());
            ps.setString(2, member.id());
            ps.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to remove member", e);
        }
    }

    @Override
    public List<Member> members(final ChatChannelRef channel) {
        final List<Member> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT member_id, display_name FROM members WHERE channel_id = ? ORDER BY member_id")) {
            ps.setString(1, channel.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Member(
                            new MemberRef(rs.getString(1)),
                            rs.getString(2)));
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to list members", e);
        }
        return result;
    }

    public String memberRole(final ChatChannelRef channel, final MemberRef member) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT role FROM members WHERE channel_id = ? AND member_id = ?")) {
            ps.setString(1, channel.id());
            ps.setString(2, member.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to query member role", e);
        }
        return "PARTICIPANT";
    }

    public void setMemberRole(final ChatChannelRef channel, final MemberRef member, final String role) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE members SET role = ? WHERE channel_id = ? AND member_id = ?")) {
            ps.setString(1, role);
            ps.setString(2, channel.id());
            ps.setString(3, member.id());
            ps.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to set member role", e);
        }
    }

    public void markRead(final ChatChannelRef channel, final MemberRef member, final Instant timestamp) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE members SET last_read_at = ? WHERE channel_id = ? AND member_id = ?")) {
            ps.setString(1, TS_FORMAT.format(timestamp));
            ps.setString(2, channel.id());
            ps.setString(3, member.id());
            ps.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to mark read", e);
        }
    }

    public Instant lastReadAt(final ChatChannelRef channel, final MemberRef member) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT last_read_at FROM members WHERE channel_id = ? AND member_id = ?")) {
            ps.setString(1, channel.id());
            ps.setString(2, member.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    final String ts = rs.getString(1);
                    if (ts != null) {
                        return Instant.parse(ts);
                    }
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to query last read", e);
        }
        return null;
    }

    public Instant lastActiveAt(final MemberRef member) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT last_active_at FROM presence WHERE member_id = ?")) {
            ps.setString(1, member.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    final String ts = rs.getString(1);
                    if (ts != null) {
                        return Instant.parse(ts);
                    }
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to query last active", e);
        }
        return null;
    }


    public void storeEnrichedFields(final String messageId, final String channelId,
                                    final String messageType, final String actorType,
                                    final String correlationId, final String target,
                                    final String artefactRefsJson, final String topicId) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE messages SET message_type = ?, actor_type = ?, correlation_id = ?, target = ?, topic_id = ? WHERE id = ?")) {
                ps.setString(1, messageType != null ? messageType : "EVENT");
                ps.setString(2, actorType != null ? actorType : "HUMAN");
                ps.setString(3, correlationId);
                ps.setString(4, target);
                ps.setString(5, topicId);
                ps.setString(6, messageId);
                ps.executeUpdate();
            }
            if (artefactRefsJson != null && !"[]".equals(artefactRefsJson) && !artefactRefsJson.isEmpty()) {
                storeArtefactRefs(conn, messageId, artefactRefsJson);
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to store enriched fields", e);
        }
    }

    private void storeArtefactRefs(final Connection conn, final String messageId, final String json) throws SQLException {
        try {
            final var refs = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            for (final var ref : refs) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO artefact_refs (message_id, uri, type, label, start_line, end_line, start_offset, end_offset, selected_text) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, messageId);
                    ps.setString(2, ref.get("uri").asText());
                    ps.setString(3, ref.get("type").asText());
                    ps.setString(4, ref.get("label").asText());
                    ps.setObject(5, ref.has("startLine") ? ref.get("startLine").asInt() : null);
                    ps.setObject(6, ref.has("endLine") ? ref.get("endLine").asInt() : null);
                    ps.setObject(7, ref.has("startOffset") ? ref.get("startOffset").asInt() : null);
                    ps.setObject(8, ref.has("endOffset") ? ref.get("endOffset").asInt() : null);
                    ps.setString(9, ref.has("selectedText") ? ref.get("selectedText").asText() : null);
                    ps.executeUpdate();
                }
            }
        } catch (final Exception e) {
            throw new SQLException("Failed to parse artefact refs JSON", e);
        }
    }

    public Map<String, Object> getEnrichedFields(final String messageId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT message_type, actor_type, correlation_id, target, topic_id FROM messages WHERE id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    final var map = new java.util.HashMap<String, Object>();
                    map.put("message_type", rs.getString("message_type"));
                    map.put("actor_type", rs.getString("actor_type"));
                    map.put("correlation_id", rs.getString("correlation_id"));
                    map.put("target", rs.getString("target"));
                    map.put("topic_id", rs.getString("topic_id"));
                    return map;
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to get enriched fields", e);
        }
        return Map.of();}

    public String getArtefactRefsJson(final String messageId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT uri, type, label, start_line, end_line, start_offset, end_offset, selected_text FROM artefact_refs WHERE message_id = ?")) {
            ps.setString(1, messageId);
            final var refs = new java.util.ArrayList<Map<String, Object>>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final var ref = new java.util.LinkedHashMap<String, Object>();
                    ref.put("uri", rs.getString("uri"));
                    ref.put("type", rs.getString("type"));
                    ref.put("label", rs.getString("label"));
                    if (rs.getObject("start_line") != null) {ref.put("startLine", rs.getInt("start_line"));}
                    if (rs.getObject("end_line") != null) {ref.put("endLine", rs.getInt("end_line"));}
                    if (rs.getObject("start_offset") != null) {ref.put("startOffset", rs.getInt("start_offset"));}
                    if (rs.getObject("end_offset") != null) {ref.put("endOffset", rs.getInt("end_offset"));}
                    if (rs.getString("selected_text") != null) {ref.put("selectedText", rs.getString("selected_text"));}
                    refs.add(ref);
                }
            }
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(refs);
        } catch (final Exception e) {
            throw new RuntimeException("Failed to get artefact refs", e);
        }
    }

    public java.util.Map<String, Object> createTopic(final String channelId, final String name) {
        final String id  = UUID.randomUUID().toString();
        final String now = java.time.Instant.now().toString();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO topics (id, channel_id, name, state, created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, channelId);
            ps.setString(3, name);
            ps.setString(4, now);
            ps.setString(5, now);
            ps.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to create topic: " + e.getMessage(), e);
        }
        return java.util.Map.of("id", id, "channelId", channelId, "name", name, "state", "ACTIVE", "createdAt", now);
    }

    public java.util.List<java.util.Map<String, Object>> listTopics(final String channelId) {
        final var result = new java.util.ArrayList<java.util.Map<String, Object>>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT t.id, t.channel_id, t.name, t.state, t.merged_into, t.created_at, t.updated_at, " +
                     "(SELECT COUNT(*) FROM messages m WHERE m.topic_id = t.id) AS message_count, " +
                     "(SELECT MAX(m.created_at) FROM messages m WHERE m.topic_id = t.id) AS latest_activity " +
                     "FROM topics t WHERE t.channel_id = ? ORDER BY t.created_at")) {
            ps.setString(1, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final var map = new java.util.HashMap<String, Object>();
                    map.put("id", rs.getString("id"));
                    map.put("channelId", rs.getString("channel_id"));
                    map.put("name", rs.getString("name"));
                    map.put("state", rs.getString("state"));
                    map.put("merged_into", rs.getString("merged_into"));
                    map.put("messageCount", rs.getInt("message_count"));
                    map.put("latestActivityTs", rs.getString("latest_activity"));
                    map.put("createdAt", rs.getString("created_at"));
                    result.add(map);
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to list topics", e);
        }
        return result;
    }

    public java.util.Optional<java.util.Map<String, Object>> findTopicByName(final String channelId, final String name) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, channel_id, name, state, merged_into, created_at, updated_at FROM topics WHERE channel_id = ? AND name = ?")) {
            ps.setString(1, channelId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    final var map = new java.util.HashMap<String, Object>();
                    map.put("id", rs.getString("id"));
                    map.put("channelId", rs.getString("channel_id"));
                    map.put("name", rs.getString("name"));
                    map.put("state", rs.getString("state"));
                    map.put("merged_into", rs.getString("merged_into"));
                    return java.util.Optional.of(map);
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to find topic by name", e);
        }
        return java.util.Optional.empty();
    }

    public java.util.Optional<java.util.Map<String, Object>> findTopicById(final String topicId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, channel_id, name, state, merged_into, created_at, updated_at FROM topics WHERE id = ?")) {
            ps.setString(1, topicId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    final var map = new java.util.HashMap<String, Object>();
                    map.put("id", rs.getString("id"));
                    map.put("channelId", rs.getString("channel_id"));
                    map.put("name", rs.getString("name"));
                    map.put("state", rs.getString("state"));
                    map.put("merged_into", rs.getString("merged_into"));
                    return java.util.Optional.of(map);
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to find topic by id", e);
        }
        return java.util.Optional.empty();
    }

    public void updateTopic(final String topicId, final String name, final String state) {
        final String now = java.time.Instant.now().toString();
        try (Connection conn = dataSource.getConnection()) {
            if (name != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE topics SET name = ?, updated_at = ? WHERE id = ?")) {
                    ps.setString(1, name);
                    ps.setString(2, now);
                    ps.setString(3, topicId);
                    ps.executeUpdate();
                }
            }
            if (state != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE topics SET state = ?, updated_at = ? WHERE id = ?")) {
                    ps.setString(1, state);
                    ps.setString(2, now);
                    ps.setString(3, topicId);
                    ps.executeUpdate();
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to update topic", e);
        }
    }

    public void mergeTopic(final String sourceId, final String targetId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE messages SET topic_id = ? WHERE topic_id = ?")) {
                    ps.setString(1, targetId);
                    ps.setString(2, sourceId);
                    ps.executeUpdate();
                }
                final String now = java.time.Instant.now().toString();
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE topics SET state = 'MERGED', merged_into = ?, updated_at = ? WHERE id = ?")) {
                    ps.setString(1, targetId);
                    ps.setString(2, now);
                    ps.setString(3, sourceId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (final SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to merge topic", e);
        }
    }

    public String getDefaultTopicId(final String channelId) {
        return findTopicByName(channelId, "General")
                       .map(t -> (String) t.get("id"))
                       .orElse(null);
    }


    public void createCommitment(final String commitmentId, final String channelId, final String deadline) {
        final String now = TS_FORMAT.format(Instant.now());
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO commitments (id, channel_id, state, deadline, created_at, updated_at) VALUES (?, ?, 'OPEN', ?, ?, ?)")) {
            ps.setString(1, commitmentId);
            ps.setString(2, channelId);
            ps.setString(3, deadline);
            ps.setString(4, now);
            ps.setString(5, now);
            ps.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to create commitment", e);
        }
    }

    public void updateCommitmentState(final String commitmentId, final String state, final String acknowledgedAt) {
        final String now = TS_FORMAT.format(Instant.now());
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE commitments SET state = ?, acknowledged_at = COALESCE(?, acknowledged_at), updated_at = ? WHERE id = ?")) {
            ps.setString(1, state);
            ps.setString(2, acknowledgedAt);
            ps.setString(3, now);
            ps.setString(4, commitmentId);
            ps.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to update commitment state", e);
        }
    }

    public List<Map<String, Object>> listCommitments(final String channelId) {
        final var result = new java.util.ArrayList<Map<String, Object>>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, channel_id, state, deadline, acknowledged_at, created_at, updated_at FROM commitments WHERE channel_id = ? ORDER BY created_at")) {
            ps.setString(1, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final var row = new java.util.LinkedHashMap<String, Object>();
                    row.put("id", rs.getString("id"));
                    row.put("channel_id", rs.getString("channel_id"));
                    row.put("state", rs.getString("state"));
                    row.put("deadline", rs.getString("deadline"));
                    row.put("acknowledged_at", rs.getString("acknowledged_at"));
                    row.put("created_at", rs.getString("created_at"));
                    row.put("updated_at", rs.getString("updated_at"));
                    result.add(row);
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to list commitments", e);
        }
        return result;
    }

    public List<Map<String, Object>> correlationMessages(final String channelId, final String correlationId) {
        final var result = new ArrayList<Map<String, Object>>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, channel_id, parent_id, sender_id, content, created_at, message_type, actor_type, correlation_id, target " +
                     "FROM messages WHERE channel_id = ? AND correlation_id = ? ORDER BY created_at")) {
            ps.setString(1, channelId);
            ps.setString(2, correlationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final var row = new java.util.LinkedHashMap<String, Object>();
                    row.put("id", rs.getString("id"));
                    row.put("channelId", rs.getString("channel_id"));
                    row.put("parentId", rs.getString("parent_id"));
                    row.put("sender", rs.getString("sender_id"));
                    row.put("content", rs.getString("content"));
                    row.put("createdAt", rs.getString("created_at"));
                    row.put("messageType", rs.getString("message_type"));
                    row.put("actorType", rs.getString("actor_type"));
                    row.put("correlationId", rs.getString("correlation_id"));
                    row.put("target", rs.getString("target"));
                    result.add(row);
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to query correlation chain", e);
        }
        return result;
    }

    private Channel channelFromRow(final ResultSet rs) throws SQLException {
        return new Channel(
                new ChatChannelRef(rs.getString("id")),
                rs.getString("name"),
                rs.getString("topic"),
                rs.getString("description"),
                rs.getInt("is_private") == 1);
    }

    private ReceivedMessage messageFromRow(final ResultSet rs, final ChatChannelRef channel) throws SQLException {
        final String parentId = rs.getString("parent_id");
        final ChatMessageRef parentRef = parentId != null
                ? new ChatMessageRef(channel, parentId) : null;
        return new ReceivedMessage(
                rs.getString("platform_id"),
                channel,
                new ChatMessageRef(channel, rs.getString("id")),
                parentRef,
                new MemberRef(rs.getString("sender_id")),
                new ChatContent(rs.getString("content")),
                Instant.parse(rs.getString("created_at")));
    }
}
