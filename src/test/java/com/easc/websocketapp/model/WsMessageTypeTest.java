package com.easc.websocketapp.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WsMessageTypeTest {

    @Test
    void resolvesKnownValues() {
        assertThat(WsMessageType.fromValue("chat_message")).isEqualTo(WsMessageType.CHAT_MESSAGE);
        assertThat(WsMessageType.fromValue("room_message")).isEqualTo(WsMessageType.ROOM_MESSAGE);
        assertThat(WsMessageType.fromValue("room_join")).isEqualTo(WsMessageType.ROOM_JOIN);
    }

    @Test
    void fallsBackToUnknownForUnexpectedValues() {
        assertThat(WsMessageType.fromValue("not-real")).isEqualTo(WsMessageType.UNKNOWN);
    }
}
