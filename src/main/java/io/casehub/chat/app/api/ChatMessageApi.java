package io.casehub.chat.app.api;

import io.casehub.chat.app.ChatResource;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import io.casehub.qhorus.api.message.Message;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.QueryParam;

import java.util.List;

@McpDomain(value = "chat/messages", app = "chat-app", basePath = "/api/chat/messages", summary = "Messages — list, mark, move operations")
@ApplicationScoped
public class ChatMessageApi {

    @Inject
    ChatResource resource;

    @PlatformMutation("Send a message to a channel. messageType: QUERY (default), COMMAND, RESPONSE, STATUS, DECLINE, HANDOFF, DONE, FAILURE, PROPOSE, JUDGMENT. actorType: HUMAN (default), AGENT, SYSTEM")
    @RestPath("/{channelId}")
    public DispatchResult postMessage(@PathParam String channelId,
                                      ChatResource.PostMessageRequest request) {
        return resource.dispatchMessage(channelId, request);
    }

    @PlatformQuery("List messages in a channel. since: message ID (numeric) — returns messages after this ID")
    @RestPath("/{channelId}")
    public List<Message> listMessages(@PathParam String channelId,
                                      @QueryParam("since") String since) {
        return resource.listMessages(channelId, since);
    }

    @PlatformMutation("Reply to a message in a channel. Inherits the parent message's topic and correlation ID")
    @RestPath("/{channelId}/{messageId}/replies")
    public DispatchResult postReply(@PathParam String channelId,
                                    @PathParam String messageId,
                                    ChatResource.PostMessageRequest request) {
        return resource.dispatchReply(channelId, messageId, request);
    }

    @PlatformMutation("Mark messages as read in a channel up to a given message ID")
    @RestPath("/{channelId}/read")
    public ChatResource.ReadMarkerView markRead(@PathParam String channelId,
                                                ChatResource.MarkReadRequest request) {
        return resource.markRead(channelId, request);
    }

    @PlatformMutation("Move a channel to a space")
    @RestPath("/channels/{channelId}/space")
    public ChatResource.ChannelMoveView moveChannelToSpace(@PathParam String channelId,
                                                           ChatResource.MoveToSpaceRequest request) {
        return resource.moveChannelToSpace(channelId, request);
    }
}
