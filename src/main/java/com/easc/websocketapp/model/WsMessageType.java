package com.easc.websocketapp.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum WsMessageType {
    NOTIFICATION("notification"),
    ROADMAP_READY("roadmap_ready"),
    QUIZ_READY("quiz_ready"),
    CHAT_MESSAGE("chat_message"),
    LATENCY_REPORT("latency_report"),
    UNKNOWN("unknown");

    private final String value;

    WsMessageType(String value) {
        this.value = value;
    }

    @JsonCreator
    public static WsMessageType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }

        for (WsMessageType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }

        return UNKNOWN;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
