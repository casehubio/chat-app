package io.casehub.chat.app;

import io.casehub.platform.api.capacity.RedistributionContext;
import io.casehub.platform.api.capacity.RedistributionDecision;
import io.casehub.platform.api.capacity.RedistributionPolicy;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class NoOpRedistributionPolicy implements RedistributionPolicy {

    @Override
    public RedistributionDecision evaluate(RedistributionContext context) {
        return RedistributionDecision.hold("chat-app: no redistribution policy configured");
    }
}
