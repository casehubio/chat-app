package io.casehub.chat.app;

public record PostMessageResult(boolean ok, long messageId, String correlationId) {}
