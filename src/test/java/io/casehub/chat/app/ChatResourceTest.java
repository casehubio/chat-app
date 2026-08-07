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
        token     = obtainToken("test-user");
        channelId = given()
                            .contentType(ContentType.JSON)
                            .header("Authorization", "Bearer " + token)
                            .body(Map.of("name", "test-" + System.nanoTime(), "topic", "Test", "description", "Desc", "isPrivate", false))
                            .post("/api/channels")
                            .then().statusCode(201)
                            .extract().path("channelId");
    }

    private String postMessageAndGetId(String text) {
        return postMessageAndGetId(channelId, text, token);
    }

    private String postMessageAndGetId(String chId, String text, String tok) {
        Object msgId = given()
                               .contentType(ContentType.JSON)
                               .header("Authorization", "Bearer " + tok)
                               .body(Map.of("text", text))
                               .post("/api/channels/{id}/messages", chId)
                               .then().statusCode(200)
                               .extract().path("messageId");
        return String.valueOf(msgId);
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
        postMessageAndGetId("hello");

        final List<?> messages = given()
                                         .header("Authorization", "Bearer " + token)
                                         .get("/api/channels/{id}/messages", channelId)
                                         .then().statusCode(200)
                                         .extract().jsonPath().getList("$");

        assertThat(messages).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void postReply() {
        String messageId = postMessageAndGetId("parent");

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
        String messageId = postMessageAndGetId("react");

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
        String messageId = postMessageAndGetId("react");

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
                .body(Map.of("memberId", "user1"))
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
                .body(Map.of("memberId", "user2"))
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
        String messageId = postMessageAndGetId("doomed");

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("emoji", "thumbsup"))
                .post("/api/channels/{channelId}/messages/{messageId}/reactions", channelId, messageId)
                .then().statusCode(200);

        given()
                .header("Authorization", "Bearer " + token)
                .queryParam("force", true)
                .delete("/api/channels/{id}", channelId)
                .then().statusCode(204);

        given()
                .header("Authorization", "Bearer " + token)
                .get("/api/channels/{id}/messages", channelId)
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void setAndGetPresence() {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("status", "ONLINE"))
                .put("/api/presence/{memberId}", "test-user")
                .then().statusCode(200);

        given()
                .header("Authorization", "Bearer " + token)
                .get("/api/presence/{memberId}", "test-user")
                .then().statusCode(200)
                .body("status", is("ONLINE"));
    }

    @Test
    void messageSenderMatchesAuthIdentity() {
        final String aliceToken = obtainToken("alice");
        postMessageAndGetId(channelId, "hello from alice", aliceToken);

        final List<Map<String, Object>> messages = given()
                                                           .header("Authorization", "Bearer " + token)
                                                           .get("/api/channels/{id}/messages", channelId)
                                                           .then().statusCode(200)
                                                           .extract().jsonPath().getList("$");

        final var aliceMsg = messages.stream()
                                     .filter(m -> "hello from alice".equals(m.get("content")))
                                     .findFirst().orElseThrow();
        assertThat(aliceMsg.get("sender")).isEqualTo("alice");
    }

    @Test
    void replySenderMatchesAuthIdentity() {
        final String bobToken  = obtainToken("bob");
        String       messageId = postMessageAndGetId(channelId, "parent", bobToken);

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
                                  .filter(m -> "reply".equals(m.get("content")))
                                  .findFirst().orElseThrow();
        assertThat(reply.get("sender")).isEqualTo("bob");
        assertThat(reply.get("inReplyTo")).isNotNull();
    }

    @Test
    void autoMembershipOnMessageSend() {
        final String carolToken = obtainToken("carol");

        final List<Map<String, Object>> membersBefore = given()
                                                                .header("Authorization", "Bearer " + token)
                                                                .get("/api/channels/{id}/members", channelId)
                                                                .then().statusCode(200)
                                                                .extract().jsonPath().getList("$");
        final boolean carolBeforePresent = membersBefore.stream()
                                                        .anyMatch(m -> "carol".equals(m.get("memberId")));
        assertThat(carolBeforePresent).isFalse();

        postMessageAndGetId(channelId, "hi from carol", carolToken);

        final List<Map<String, Object>> membersAfter = given()
                                                               .header("Authorization", "Bearer " + token)
                                                               .get("/api/channels/{id}/members", channelId)
                                                               .then().statusCode(200)
                                                               .extract().jsonPath().getList("$");
        final boolean carolAfterPresent = membersAfter.stream()
                                                      .anyMatch(m -> "carol".equals(m.get("memberId")));
        assertThat(carolAfterPresent).isTrue();
    }

    @Test
    void presenceAutoCreateOnMessageSend() {
        final String userId    = "dave-" + System.nanoTime();
        final String daveToken = obtainToken(userId);

        postMessageAndGetId(channelId, "hi from dave", daveToken);

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
               .body("messageId", notNullValue())
               .body("correlationId", notNullValue());
        given().auth().oauth2(token)
               .get("/api/channels/" + channelId + "/commitments")
               .then().statusCode(200)
               .body("size()", is(1))
               .body("[0].state", is("OPEN"));}

    @Test
    void correlationChain_returnsRelatedMessages() {
        var cmdResponse = given().auth().oauth2(token).contentType(ContentType.JSON)
                                 .body(Map.of("text", "Investigate", "messageType", "COMMAND"))
                                 .post("/api/channels/" + channelId + "/messages")
                                 .then().statusCode(200).extract();
        String cmdId  = cmdResponse.jsonPath().get("messageId").toString();
        String corrId = cmdResponse.jsonPath().getString("correlationId");
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("text", "Working on it", "messageType", "STATUS"))
               .post("/api/channels/" + channelId + "/messages/" + cmdId + "/replies")
               .then().statusCode(200);
        given().auth().oauth2(token)
               .get("/api/channels/" + channelId + "/correlation/" + corrId)
               .then().statusCode(200).body("size()", is(2));}

    @Test
    void replyInheritsCorrelationId() {
        var cmdResponse = given().auth().oauth2(token).contentType(ContentType.JSON)
                                 .body(Map.of("text", "Start task", "messageType", "COMMAND"))
                                 .post("/api/channels/" + channelId + "/messages")
                                 .then().statusCode(200).extract();
        String cmdId  = cmdResponse.jsonPath().get("messageId").toString();
        String corrId = cmdResponse.jsonPath().getString("correlationId");

        var replyResponse = given().auth().oauth2(token).contentType(ContentType.JSON)
                                   .body(Map.of("text", "Status update", "messageType", "STATUS"))
                                   .post("/api/channels/" + channelId + "/messages/" + cmdId + "/replies")
                                   .then().statusCode(200).extract();
        String replyId = replyResponse.jsonPath().get("messageId").toString();

        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("text", "Done", "messageType", "DONE"))
               .post("/api/channels/" + channelId + "/messages/" + replyId + "/replies")
               .then().statusCode(200);

        given().auth().oauth2(token)
               .get("/api/channels/" + channelId + "/correlation/" + corrId)
               .then().statusCode(200).body("size()", is(3));}

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
    void listTopics_returnsTopicSummaries() {
        postMessageAndGetId("hello");
        var topics = given().auth().oauth2(token)
                            .get("/api/channels/" + channelId + "/topics")
                            .then().statusCode(200)
                            .extract().body().as(List.class);
        assertThat(topics).isNotEmpty();}

    @Test
    void updateTopic_rename() {
        Object topicIdObj = given().auth().oauth2(token).contentType(ContentType.JSON)
                                   .body(Map.of("name", "old-name"))
                                   .post("/api/channels/" + channelId + "/topics")
                                   .then().statusCode(200)
                                   .extract().path("id");
        String topicId = String.valueOf(topicIdObj);
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("name", "new-name"))
               .put("/api/channels/" + channelId + "/topics/" + topicId)
               .then().statusCode(200);
    }

    @Test
    void postMessage_withTopicName_createsTopicImplicitly() {
        postMessageAndGetId("seed");
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("text", "hello", "topic", "new-discussion"))
               .post("/api/channels/" + channelId + "/messages")
               .then().statusCode(200);
        var topics = given().auth().oauth2(token)
                            .get("/api/channels/" + channelId + "/topics")
                            .then().statusCode(200)
                            .extract().body().as(List.class);
        assertThat(topics).hasSizeGreaterThanOrEqualTo(2);}

    @Test
    void postMessage_noTopic_defaultsToGeneral() {
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("text", "hello"))
               .post("/api/channels/" + channelId + "/messages")
               .then().statusCode(200);
    }

    @Test
    void postReply_inheritsParentTopic() {
        String msgId = postMessageAndGetId(channelId, "root", token);
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("text", "reply"))
               .post("/api/channels/" + channelId + "/messages/" + msgId + "/replies")
               .then().statusCode(200);
    }

    @Test
    void mergeTopic_returns200() {
        Object sourceIdObj = given().auth().oauth2(token).contentType(ContentType.JSON)
                                    .body(Map.of("name", "merge-source"))
                                    .post("/api/channels/" + channelId + "/topics")
                                    .then().statusCode(200).extract().path("id");
        Object targetIdObj = given().auth().oauth2(token).contentType(ContentType.JSON)
                                    .body(Map.of("name", "merge-target"))
                                    .post("/api/channels/" + channelId + "/topics")
                                    .then().statusCode(200).extract().path("id");
        given().auth().oauth2(token).contentType(ContentType.JSON)
               .body(Map.of("targetTopicId", String.valueOf(targetIdObj)))
               .post("/api/channels/" + channelId + "/topics/" + String.valueOf(sourceIdObj) + "/merge")
               .then().statusCode(200);
    }
}
