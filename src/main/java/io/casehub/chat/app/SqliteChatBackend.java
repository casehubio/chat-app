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
            addColumnIfMissing(conn, "members", "role", "TEXT NOT NULL DEFAULT 'PARTICIPANT'");
            addColumnIfMissing(conn, "members", "last_read_at", "TEXT");
            addColumnIfMissing(conn, "presence", "last_active_at", "TEXT");
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
        final String id = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO channels (id, name, topic, description, is_private) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, topic);
            ps.setString(4, description);
            ps.setInt(5, isPrivate ? 1 : 0);
            ps.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to create channel", e);
        }
        return new Channel(new ChatChannelRef(id), name, topic, description, isPrivate);
    }

    @Override
    public void deleteChannel(final String channelId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
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
