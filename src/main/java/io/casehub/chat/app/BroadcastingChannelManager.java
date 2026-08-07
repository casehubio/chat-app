package io.casehub.chat.app;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelManager;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.inject.Inject;

import java.util.UUID;

@Decorator
@Priority(1)
public abstract class BroadcastingChannelManager implements ChannelManager {

    @Inject
    @Delegate
    ChannelManager delegate;

    @Inject
    ChatWebSocketBroadcaster broadcaster;

    @Override
    public Channel create(ChannelCreateRequest request) {
        Channel channel = delegate.create(request);
        broadcaster.broadcastChannelAppend(channel);
        return channel;
    }

    @Override
    public long delete(UUID channelId, boolean force) {
        long count = delegate.delete(channelId, force);
        broadcaster.broadcastChannelRemove(channelId);
        return count;
    }
}
