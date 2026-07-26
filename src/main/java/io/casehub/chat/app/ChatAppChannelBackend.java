package io.casehub.chat.app;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.BackendRegistry;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.CommitmentStateChangedEvent;
import io.casehub.qhorus.api.gateway.HumanParticipatingChannelBackend;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class ChatAppChannelBackend implements HumanParticipatingChannelBackend {

    @Inject
    ChatWebSocketBroadcaster broadcaster;

    @Inject
    BackendRegistry registry;

    @Override
    public String backendId() {
        return "chat-app";
    }

    @Override
    public ActorType actorType() {
        return ActorType.HUMAN;
    }

    void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
        registry.registerBackend(event.channelId(), this, "human_participating");
        broadcaster.registerChannel(event.channelId(), event.channelName());
    }

    @Override
    public void open(ChannelRef channel, Map<String, String> metadata) {
    }

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        broadcaster.pushMessage(channel, message);
    }

    void onCommitmentChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS)
                             CommitmentStateChangedEvent event) {
        broadcaster.broadcastCommitment(event.commitment());
    }

    @Override
    public void close(ChannelRef channel) {
        broadcaster.deregisterChannel(channel);
    }
}
