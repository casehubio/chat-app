package io.casehub.chat.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ChatWebSocketTest {

    @TestHTTPResource("/ws/chat")
    URI wsUri;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void connectWithoutTokenIsRejected() throws Exception {
        final var container = ContainerProvider.getWebSocketContainer();
        final var wsUriConverted = new URI(wsUri.toString().replace("http://", "ws://"));
        try {
            container.connectToServer(new Endpoint() {
                @Override
                public void onOpen(final Session session, final EndpointConfig config) {}
            }, ClientEndpointConfig.Builder.create().build(), wsUriConverted);
            org.junit.jupiter.api.Assertions.fail("Expected connection to be rejected");
        } catch (final Exception e) {
            // Expected — server rejects upgrade without valid JWT
        }
    }

    @Test
    void snapshotContainsFourDatasetsWithCorrectStructure() throws Exception {
        final var future = new CompletableFuture<String>();
        try (Session session = connectAndCapture(future)) {
            final String raw = future.get(5, TimeUnit.SECONDS);
            final List<Map<String, Object>> snapshots = objectMapper.readValue(raw,
                    new TypeReference<>() {});

            assertThat(snapshots).hasSizeGreaterThanOrEqualTo(4);

            final var datasetNames = snapshots.stream()
                    .map(s -> (String) s.get("dataset"))
                    .toList();
            assertThat(datasetNames).contains("channels", "messages", "members", "presence");

            for (final Map<String, Object> snapshot : snapshots) {
                assertThat(snapshot).containsKey("op");
                assertThat(snapshot.get("op")).isEqualTo("snapshot");
                assertThat(snapshot).containsKey("seq");
                assertThat(snapshot).containsKey("columns");
                assertThat(snapshot).containsKey("rows");

                @SuppressWarnings("unchecked")
                final var columns = (List<Map<String, Object>>) snapshot.get("columns");
                for (final Map<String, Object> col : columns) {
                    assertThat(col).containsKeys("id", "name", "type");
                }
            }
        }
    }

    @Test
    void allRowValuesAreStringsOrNull() throws Exception {
        final var future = new CompletableFuture<String>();
        try (Session session = connectAndCapture(future)) {
            final String raw = future.get(5, TimeUnit.SECONDS);
            final List<Map<String, Object>> snapshots = objectMapper.readValue(raw,
                    new TypeReference<>() {});

            for (final Map<String, Object> snapshot : snapshots) {
                @SuppressWarnings("unchecked")
                final var rows = (List<List<Object>>) snapshot.get("rows");
                for (final List<Object> row : rows) {
                    for (final Object cell : row) {
                        assertThat(cell).satisfiesAnyOf(
                                v -> assertThat(v).isNull(),
                                v -> assertThat(v).isInstanceOf(String.class));
                    }
                }
            }
        }
    }

    @Test
    void seqValuesAreMonotonicallyIncreasing() throws Exception {
        final var future = new CompletableFuture<String>();
        try (Session session = connectAndCapture(future)) {
            final String raw = future.get(5, TimeUnit.SECONDS);
            final List<Map<String, Object>> snapshots = objectMapper.readValue(raw,
                    new TypeReference<>() {});

            long lastSeq = 0;
            for (final Map<String, Object> snapshot : snapshots) {
                final long seq = Long.parseLong((String) snapshot.get("seq"));
                assertThat(seq).isGreaterThan(lastSeq);
                lastSeq = seq;
            }
        }
    }

    @Test
    void membersDatasetHasMembershipIdColumn() throws Exception {
        final var future = new CompletableFuture<String>();
        try (Session session = connectAndCapture(future)) {
            final String raw = future.get(5, TimeUnit.SECONDS);
            final List<Map<String, Object>> snapshots = objectMapper.readValue(raw,
                    new TypeReference<>() {});

            final var members = snapshots.stream()
                    .filter(s -> "members".equals(s.get("dataset")))
                    .findFirst().orElseThrow();

            @SuppressWarnings("unchecked")
            final var columns = (List<Map<String, Object>>) members.get("columns");
            assertThat(columns.get(0).get("id")).isEqualTo("membershipId");
        }
    }

    @Test
    void presenceSnapshotDeduplicatesMembersAcrossChannels() throws Exception {
        final var future = new CompletableFuture<String>();
        try (Session session = connectAndCapture(future)) {
            final String raw = future.get(5, TimeUnit.SECONDS);
            final List<Map<String, Object>> snapshots = objectMapper.readValue(raw,
                    new TypeReference<>() {});

            final var presence = snapshots.stream()
                    .filter(s -> "presence".equals(s.get("dataset")))
                    .findFirst().orElseThrow();

            @SuppressWarnings("unchecked")
            final var rows = (List<List<String>>) presence.get("rows");
            final var memberIds = rows.stream().map(r -> r.get(0)).toList();
            assertThat(memberIds).doesNotHaveDuplicates();
        }
    }

    @Test
    void appendEventViaPost() throws Exception {
        final var messagesReceived = new ArrayList<String>();
        final var future = new CompletableFuture<Map<String, Object>>();
        try (Session session = connectAndCaptureMultiple(messagesReceived, future)) {
            // Wait for initial snapshot
            future.get(5, TimeUnit.SECONDS);

            // Get snapshot seq to compare with append
            final List<Map<String, Object>> snapshots = objectMapper.readValue(messagesReceived.get(0),
                    new TypeReference<>() {});
            final var messagesSnapshot = snapshots.stream()
                    .filter(s -> "messages".equals(s.get("dataset")))
                    .findFirst().orElseThrow();
            final long snapshotSeq = Long.parseLong((String) messagesSnapshot.get("seq"));

            // Post a new message via REST
            final String token = obtainToken("rest-user");
            RestAssured.given()
                    .contentType("application/json")
                    .header("Authorization", "Bearer " + token)
                    .body(Map.of("text", "append test message"))
                    .post("/api/channels/general/messages")
                    .then()
                    .statusCode(200);

            // Wait for append event
            Thread.sleep(500);

            // Parse all received messages and find append
            Map<String, Object> appendEvent = null;
            for (int i = 1; i < messagesReceived.size(); i++) {
                final String msg = messagesReceived.get(i);
                // Message can be a single object or an array
                if (msg.trim().startsWith("[")) {
                    final List<Map<String, Object>> events = objectMapper.readValue(msg,
                            new TypeReference<>() {});
                    for (final Map<String, Object> event : events) {
                        if ("append".equals(event.get("op")) && "messages".equals(event.get("dataset"))) {
                            appendEvent = event;
                            break;
                        }
                    }
                } else {
                    final Map<String, Object> event = objectMapper.readValue(msg,
                            new TypeReference<>() {});
                    if ("append".equals(event.get("op")) && "messages".equals(event.get("dataset"))) {
                        appendEvent = event;
                        break;
                    }
                }
                if (appendEvent != null) break;
            }

            assertThat(appendEvent).isNotNull();
            assertThat(appendEvent.get("op")).isEqualTo("append");
            assertThat(appendEvent.get("dataset")).isEqualTo("messages");
            assertThat(appendEvent).containsKey("seq");
            assertThat(appendEvent).containsKey("columns");
            assertThat(appendEvent).containsKey("rows");

            final long appendSeq = Long.parseLong((String) appendEvent.get("seq"));
            assertThat(appendSeq).isGreaterThan(snapshotSeq);

            @SuppressWarnings("unchecked")
            final var rows = (List<List<String>>) appendEvent.get("rows");
            assertThat(rows).isNotEmpty();
            final var firstRow = rows.get(0);
            assertThat(firstRow).hasSizeGreaterThanOrEqualTo(6);
        }
    }

    @Test
    void membershipIdValueFormat() throws Exception {
        final var future = new CompletableFuture<String>();
        try (Session session = connectAndCapture(future)) {
            final String raw = future.get(5, TimeUnit.SECONDS);
            final List<Map<String, Object>> snapshots = objectMapper.readValue(raw,
                    new TypeReference<>() {});

            final var members = snapshots.stream()
                    .filter(s -> "members".equals(s.get("dataset")))
                    .findFirst().orElseThrow();

            @SuppressWarnings("unchecked")
            final var rows = (List<List<String>>) members.get("rows");
            for (final List<String> row : rows) {
                final String membershipId = row.get(0);
                final String channelId = row.get(1);
                final String memberId = row.get(2);
                assertThat(membershipId).isEqualTo(channelId + ":" + memberId);
            }
        }
    }

    @Test
    void presenceRowStructure() throws Exception {
        final var future = new CompletableFuture<String>();
        try (Session session = connectAndCapture(future)) {
            final String raw = future.get(5, TimeUnit.SECONDS);
            final List<Map<String, Object>> snapshots = objectMapper.readValue(raw,
                    new TypeReference<>() {});

            final var presence = snapshots.stream()
                    .filter(s -> "presence".equals(s.get("dataset")))
                    .findFirst().orElseThrow();

            @SuppressWarnings("unchecked")
            final var rows = (List<List<String>>) presence.get("rows");
            for (final List<String> row : rows) {
                assertThat(row).hasSize(3);
                assertThat(row.get(1)).isIn("ONLINE", "OFFLINE", "AWAY", "DND", "UNKNOWN");
            }
        }
    }

    private String obtainToken(final String name) {
        return RestAssured.given()
                .contentType("application/json")
                .body(Map.of("name", name))
                .post("/dev/auth/login")
                .then().statusCode(200)
                .extract().path("token");
    }

    private Session connectAndCapture(final CompletableFuture<String> future) throws Exception {
        final String token = obtainToken("ws-user");
        final var container = ContainerProvider.getWebSocketContainer();
        final var wsUriConverted = new URI(wsUri.toString().replace("http://", "ws://") + "?token=" + token);
        return container.connectToServer(new Endpoint() {
            @Override
            public void onOpen(final Session session, final EndpointConfig config) {
                session.addMessageHandler(new MessageHandler.Whole<String>() {
                    @Override
                    public void onMessage(final String message) {
                        future.complete(message);
                    }
                });
            }
        }, ClientEndpointConfig.Builder.create().build(), wsUriConverted);
    }

    private Session connectAndCaptureMultiple(final List<String> messages,
                                              final CompletableFuture<Map<String, Object>> firstMessageFuture)
            throws Exception {
        final String token = obtainToken("ws-user");
        final var container = ContainerProvider.getWebSocketContainer();
        final var wsUriConverted = new URI(wsUri.toString().replace("http://", "ws://") + "?token=" + token);
        return container.connectToServer(new Endpoint() {
            @Override
            public void onOpen(final Session session, final EndpointConfig config) {
                session.addMessageHandler(new MessageHandler.Whole<String>() {
                    @Override
                    public void onMessage(final String message) {
                        messages.add(message);
                        if (!firstMessageFuture.isDone()) {
                            firstMessageFuture.complete(Map.of());
                        }
                    }
                });
            }
        }, ClientEndpointConfig.Builder.create().build(), wsUriConverted);
    }
}
