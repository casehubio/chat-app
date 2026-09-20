package io.casehub.chat.app.api;

import io.casehub.chat.app.ChatResource;
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

@McpDomain(value = "chat/messages", basePath = "/api/chat/messages")
@ApplicationScoped
public class ChatMessageApi {

    @Inject ChatResource resource;

    @PlatformMutation("Send a message to a channel")
    @RestPath("/{channelId}")
    public Object postMessage(@PathParam String channelId,
                               ChatResource.PostMessageRequest request) {
        return resource.postMessage(channelId, request).getEntity();
    }

    @PlatformQuery("List messages in a channel")
    @RestPath("/{channelId}")
    public List<Message> listMessages(@PathParam String channelId,
                                       @QueryParam("since") String since) {
        return resource.listMessages(channelId, since);
    }

    @PlatformMutation("Reply to a message in a channel")
    @RestPath("/{channelId}/{messageId}/replies")
    public Object postReply(@PathParam String channelId,
                             @PathParam String messageId,
                             ChatResource.PostMessageRequest request) {
        return resource.postReply(channelId, messageId, request).getEntity();
    }

    @PlatformMutation("Mark messages as read in a channel")
    @RestPath("/{channelId}/read")
    public Object markRead(@PathParam String channelId,
                            ChatResource.MarkReadRequest request) {
        return resource.markRead(channelId, request).getEntity();
    }

    @PlatformMutation("Move a channel to a space")
    @RestPath("/channels/{channelId}/space")
    public Object moveChannelToSpace(@PathParam String channelId,
                                      ChatResource.MoveToSpaceRequest request) {
        return resource.moveChannelToSpace(channelId, request).getEntity();
    }
}
