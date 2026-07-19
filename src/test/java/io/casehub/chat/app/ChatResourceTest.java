package io.casehub.chat.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;

@QuarkusTest
class ChatResourceTest {

    private String channelId;
    private String token;

    private static String obtainToken(final String name) {
        return given()
                .contentType(ContentType.JSON)
                .body(Map.of("name", name))
                .post("/dev/auth/login")
                .then().statusCode(200)
                .extract().path("token");
    }

    @BeforeEach
    void setUp() {
        token = obtainToken("test-user");
        channelId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("name", "test-" + System.nanoTime(), "topic", "Test", "description", "Desc", "isPrivate", false))
                .post("/api/channels")
                .then().statusCode(200)
                .extract().path("ref.id");
    }

    @Test
    void unauthenticatedRequestReturns401() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("text", "no auth"))
                .post("/api/channels/general/messages")
                .then().statusCode(401);
    }

    @Test
    void createAndListChannels() {
        given()
                .header("Authorization", "Bearer " + token)
                .get("/api/channels")
                .then().statusCode(200)
                .body("size()", is(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void postAndListMessages() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("text", "hello"))
                .post("/api/channels/{id}/messages", channelId)
                .then().statusCode(200)
                .body("ok", is(true))
                .body("messageId", notNullValue());

        final List<?> messages = given()
                .header("Authorization", "Bearer " + token)
                .get("/api/channels/{id}/messages", channelId)
                .then().statusCode(200)
                .extract().jsonPath().getList("$");

        assertThat(messages).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void postReply() {
        final String messageId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("text", "parent"))
                .post("/api/channels/{id}/messages", channelId)
                .then().statusCode(200)
                .extract().path("messageId");

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("text", "reply"))
                .post("/api/channels/{channelId}/messages/{messageId}/replies", channelId, messageId)
                .then().statusCode(200)
                .body("ok", is(true));
    }

    @Test
    void addAndListReactions() {
        final String messageId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("text", "react"))
                .post("/api/channels/{id}/messages", channelId)
                .then().statusCode(200)
                .extract().path("messageId");

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("emoji", "thumbsup"))
                .post("/api/channels/{channelId}/messages/{messageId}/reactions", channelId, messageId)
                .then().statusCode(200);

        final List<String> reactions = given()
                .header("Authorization", "Bearer " + token)
                .get("/api/channels/{channelId}/messages/{messageId}/reactions", channelId, messageId)
                .then().statusCode(200)
                .extract().jsonPath().getList("$");

        assertThat(reactions).contains("thumbsup");
    }

    @Test
    void removeReaction() {
        final String messageId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("text", "react"))
                .post("/api/channels/{id}/messages", channelId)
                .then().statusCode(200)
                .extract().path("messageId");

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("emoji", "heart"))
                .post("/api/channels/{channelId}/messages/{messageId}/reactions", channelId, messageId)
                .then().statusCode(200);

        given()
                .header("Authorization", "Bearer " + token)
                .delete("/api/channels/{channelId}/messages/{messageId}/reactions/{emoji}",
                        channelId, messageId, "heart")
                .then().statusCode(200);

        final List<String> reactions = given()
                .header("Authorization", "Bearer " + token)
                .get("/api/channels/{channelId}/messages/{messageId}/reactions", channelId, messageId)
                .then().statusCode(200)
                .extract().jsonPath().getList("$");

        assertThat(reactions).doesNotContain("heart");
    }

    @Test
    void addAndListMembers() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("memberId", "user1", "displayName", "User One"))
                .post("/api/channels/{id}/members", channelId)
                .then().statusCode(200);

        final List<?> members = given()
                .header("Authorization", "Bearer " + token)
                .get("/api/channels/{id}/members", channelId)
                .then().statusCode(200)
                .extract().jsonPath().getList("$");

        assertThat(members).hasSize(1);
    }

    @Test
    void removeMember() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("memberId", "user2", "displayName", "User Two"))
                .post("/api/channels/{id}/members", channelId)
                .then().statusCode(200);

        given()
                .header("Authorization", "Bearer " + token)
                .delete("/api/channels/{channelId}/members/{memberId}", channelId, "user2")
                .then().statusCode(200);

        final List<?> members = given()
                .header("Authorization", "Bearer " + token)
                .get("/api/channels/{id}/members", channelId)
                .then().statusCode(200)
                .extract().jsonPath().getList("$");

        assertThat(members).isEmpty();
    }

    @Test
    void deleteChannelCascadesAndReturns200() {
        final String messageId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("text", "doomed"))
                .post("/api/channels/{id}/messages", channelId)
                .then().statusCode(200)
                .extract().path("messageId");

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("emoji", "thumbsup"))
                .post("/api/channels/{channelId}/messages/{messageId}/reactions", channelId, messageId)
                .then().statusCode(200);

        given()
                .header("Authorization", "Bearer " + token)
                .delete("/api/channels/{id}", channelId)
                .then().statusCode(204);

        given()
                .header("Authorization", "Bearer " + token)
                .get("/api/channels/{id}/messages", channelId)
                .then().statusCode(200)
                .body("size()", is(0));

        given()
                .header("Authorization", "Bearer " + token)
                .get("/api/channels/{channelId}/messages/{messageId}/reactions", channelId, messageId)
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void setAndGetPresence() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("status", "ONLINE"))
                .put("/api/presence/{memberId}", "agent-1")
                .then().statusCode(200);

        given()
                .header("Authorization", "Bearer " + token)
                .get("/api/presence/{memberId}", "agent-1")
                .then().statusCode(200)
                .body("status", is("ONLINE"));
    }

    @Test
    void messageSenderMatchesAuthIdentity() {
        final String aliceToken = obtainToken("alice");
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + aliceToken)
                .body(Map.of("text", "hello from alice"))
                .post("/api/channels/{id}/messages", channelId)
                .then().statusCode(200);

        final List<Map<String, Object>> messages = given()
                .header("Authorization", "Bearer " + token)
                .get("/api/channels/{id}/messages", channelId)
                .then().statusCode(200)
                .extract().jsonPath().getList("$");

        final var aliceMsg = messages.stream()
                .filter(m -> "hello from alice".equals(((Map<?, ?>) m.get("content")).get("text")))
                .findFirst().orElseThrow();
        assertThat(((Map<?, ?>) aliceMsg.get("sender")).get("id")).isEqualTo("alice");
    }

    @Test
    void replySenderMatchesAuthIdentity() {
        final String bobToken = obtainToken("bob");
        final String messageId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + bobToken)
                .body(Map.of("text", "parent"))
                .post("/api/channels/{id}/messages", channelId)
                .then().statusCode(200)
                .extract().path("messageId");

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + bobToken)
                .body(Map.of("text", "reply"))
                .post("/api/channels/{channelId}/messages/{messageId}/replies", channelId, messageId)
                .then().statusCode(200);

        final List<Map<String, Object>> messages = given()
                .header("Authorization", "Bearer " + token)
                .get("/api/channels/{id}/messages", channelId)
                .then().statusCode(200)
                .extract().jsonPath().getList("$");

        final var reply = messages.stream()
                .filter(m -> "reply".equals(((Map<?, ?>) m.get("content")).get("text")))
                .findFirst().orElseThrow();
        assertThat(((Map<?, ?>) reply.get("sender")).get("id")).isEqualTo("bob");
        assertThat(((Map<?, ?>) reply.get("parentRef"))).isNotNull();
    }

    @Test
    void autoMembershipOnMessageSend() {
        final String carolToken = obtainToken("carol");

        // carol is NOT a member of the channel yet
        final List<?> membersBefore = given()
                .header("Authorization", "Bearer " + token)
                .get("/api/channels/{id}/members", channelId)
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        final boolean carolBeforePresent = membersBefore.stream()
                .anyMatch(m -> "carol".equals(((Map<?, ?>) ((Map<?, ?>) m).get("ref")).get("id")));
        assertThat(carolBeforePresent).isFalse();

        // carol sends a message
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + carolToken)
                .body(Map.of("text", "hi from carol"))
                .post("/api/channels/{id}/messages", channelId)
                .then().statusCode(200);

        // carol is now a member
        final List<?> membersAfter = given()
                .header("Authorization", "Bearer " + token)
                .get("/api/channels/{id}/members", channelId)
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        final boolean carolAfterPresent = membersAfter.stream()
                .anyMatch(m -> "carol".equals(((Map<?, ?>) ((Map<?, ?>) m).get("ref")).get("id")));
        assertThat(carolAfterPresent).isTrue();
    }

    @Test
    void presenceAutoCreateOnMessageSend() {
        final String userId = "dave-" + System.nanoTime();
        final String daveToken = obtainToken(userId);

        // dave has no presence yet — UNKNOWN
        given()
                .header("Authorization", "Bearer " + token)
                .get("/api/presence/{memberId}", userId)
                .then().statusCode(200)
                .body("status", is("UNKNOWN"));

        // dave sends a message
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + daveToken)
                .body(Map.of("text", "hi from dave"))
                .post("/api/channels/{id}/messages", channelId)
                .then().statusCode(200);

        // dave now has ONLINE presence
        given()
                .header("Authorization", "Bearer " + token)
                .get("/api/presence/{memberId}", userId)
                .then().statusCode(200)
                .body("status", is("ONLINE"));
    }

    @Test
    void postMessage_withEnrichedFields_createsCommitment() {
        var body = Map.of(
                "text", "Investigate case-456",
                "messageType", "COMMAND",
                "actorType", "AGENT",
                "target", "agent-b");
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(body)
               .post("/api/channels/" + channelId + "/messages")
               .then().statusCode(200)
               .body("messageId", notNullValue());
        given().auth().oauth2(token)
               .get("/api/channels/" + channelId + "/commitments")
               .then().statusCode(200)
               .body("size()", is(1))
               .body("[0].state", is("OPEN"));
    }

    @Test
    void updateCommitmentState_patchEndpoint() {
        var msgId = given().auth().oauth2(token).contentType(ContentType.JSON)
                           .body(Map.of("text", "Do this", "messageType", "COMMAND"))
                           .post("/api/channels/" + channelId + "/messages")
                           .then().statusCode(200)
                           .extract().path("messageId");
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("state", "FULFILLED"))
               .patch("/api/channels/" + channelId + "/commitments/" + msgId)
               .then().statusCode(200);
        given().auth().oauth2(token)
               .get("/api/channels/" + channelId + "/commitments")
               .then().body("[0].state", is("FULFILLED"));
    }

    @Test
    void correlationChain_returnsRelatedMessages() {
        var cmdId = given().auth().oauth2(token).contentType(ContentType.JSON)
                           .body(Map.of("text", "Investigate", "messageType", "COMMAND"))
                           .post("/api/channels/" + channelId + "/messages")
                           .then().extract().<String>path("messageId");
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("text", "Working on it", "messageType", "STATUS"))
               .post("/api/channels/" + channelId + "/messages/" + cmdId + "/replies")
               .then().statusCode(200);
        given().auth().oauth2(token)
               .get("/api/channels/" + channelId + "/correlation/" + cmdId)
               .then().statusCode(200).body("size()", is(2));
    }

    @Test
    void replyInheritsCorrelationId() {
        var cmdId = given().auth().oauth2(token).contentType(ContentType.JSON)
                           .body(Map.of("text", "Start task", "messageType", "COMMAND"))
                           .post("/api/channels/" + channelId + "/messages")
                           .then().extract().<String>path("messageId");
        var replyId = given().auth().oauth2(token).contentType(ContentType.JSON)
                             .body(Map.of("text", "Status update", "messageType", "STATUS"))
                             .post("/api/channels/" + channelId + "/messages/" + cmdId + "/replies")
                             .then().extract().<String>path("messageId");
        var chainReplyId = given().auth().oauth2(token).contentType(ContentType.JSON)
                                  .body(Map.of("text", "Done", "messageType", "DONE"))
                                  .post("/api/channels/" + channelId + "/messages/" + replyId + "/replies")
                                  .then().extract().<String>path("messageId");
        given().auth().oauth2(token)
               .get("/api/channels/" + channelId + "/correlation/" + cmdId)
               .then().statusCode(200).body("size()", is(3));
    }

    @Test
    void createTopic_returns200WithId() {
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("name", "deployment-pipeline"))
               .post("/api/channels/" + channelId + "/topics")
               .then().statusCode(200)
               .body("name", is("deployment-pipeline"))
               .body("id", notNullValue());
    }

    @Test
    void createTopic_duplicateName_returns409() {
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("name", "dup-topic"))
               .post("/api/channels/" + channelId + "/topics")
               .then().statusCode(200);
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("name", "dup-topic"))
               .post("/api/channels/" + channelId + "/topics")
               .then().statusCode(409);
    }

    @Test
    void createTopic_emptyName_returns400() {
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("name", ""))
               .post("/api/channels/" + channelId + "/topics")
               .then().statusCode(400);
    }

    @Test
    void createTopic_generalReserved_returns409() {
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("name", "General"))
               .post("/api/channels/" + channelId + "/topics")
               .then().statusCode(409);
    }

    @Test
    void listTopics_includesDefaultGeneral() {
        var topics = given().auth().oauth2(token)
                            .get("/api/channels/" + channelId + "/topics")
                            .then().statusCode(200)
                            .extract().body().as(List.class);
        assertThat(topics).isNotEmpty();
    }

    @Test
    void updateTopic_rename() {
        String topicId = given().auth().oauth2(token).contentType(ContentType.JSON)
                                .body(Map.of("name", "old-name"))
                                .post("/api/channels/" + channelId + "/topics")
                                .then().statusCode(200)
                                .extract().path("id");
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("name", "new-name"))
               .put("/api/channels/" + channelId + "/topics/" + topicId)
               .then().statusCode(200);
    }

    @Test
    void postMessage_withTopicName_createsTopicImplicitly() {
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("text", "hello", "topic", "new-discussion"))
               .post("/api/channels/" + channelId + "/messages")
               .then().statusCode(200);
        var topics = given().auth().oauth2(token)
                            .get("/api/channels/" + channelId + "/topics")
                            .then().statusCode(200)
                            .extract().body().as(List.class);
        assertThat(topics).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void postMessage_noTopic_defaultsToGeneral() {
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("text", "hello"))
               .post("/api/channels/" + channelId + "/messages")
               .then().statusCode(200);
    }

    @Test
    void postReply_inheritsParentTopic() {
        String msgId = given().auth().oauth2(token).contentType(ContentType.JSON)
                              .body(Map.of("text", "root", "topic", "specific-topic"))
                              .post("/api/channels/" + channelId + "/messages")
                              .then().statusCode(200)
                              .extract().path("messageId");
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("text", "reply", "topic", "different-topic"))
               .post("/api/channels/" + channelId + "/messages/" + msgId + "/replies")
               .then().statusCode(200);
    }

    @Test
    void mergeTopic_returns200() {
        String sourceId = given().auth().oauth2(token).contentType(ContentType.JSON)
                                 .body(Map.of("name", "merge-source"))
                                 .post("/api/channels/" + channelId + "/topics")
                                 .then().statusCode(200).extract().path("id");
        String targetId = given().auth().oauth2(token).contentType(ContentType.JSON)
                                 .body(Map.of("name", "merge-target"))
                                 .post("/api/channels/" + channelId + "/topics")
                                 .then().statusCode(200).extract().path("id");
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("targetTopicId", targetId))
               .post("/api/channels/" + channelId + "/topics/" + sourceId + "/merge")
               .then().statusCode(200);
    }


}
