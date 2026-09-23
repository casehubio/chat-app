package io.casehub.chat.app;

import java.util.List;
import java.util.Map;

public record PostMessageRequest(String text, String messageType, String actorType,
                                 String target, List<Map<String, Object>> artefactRefs,
                                 String topic, String topicId) {}
