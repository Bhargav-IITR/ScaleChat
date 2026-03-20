package com.easc.websocketapp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.OptionalDouble;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsMessage {

    private WsMessageType type = WsMessageType.UNKNOWN;
    private JsonNode payload;

    @JsonProperty("receiverID")
    private String receiverID;

    @JsonProperty("senderId")
    private String senderId;

    private Instant timestamp;

    public WsMessageType getType() {
        return type;
    }

    public void setType(WsMessageType type) {
        this.type = type == null ? WsMessageType.UNKNOWN : type;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }

    public String getReceiverID() {
        return receiverID;
    }

    public void setReceiverID(String receiverID) {
        this.receiverID = receiverID;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public OptionalDouble payloadAsDouble() {
        if (payload == null || !payload.isNumber()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(payload.asDouble());
    }

    @Override
    public String toString() {
        return "WsMessage{"
                + "type=" + type
                + ", receiverID='" + receiverID + '\''
                + ", senderId='" + senderId + '\''
                + '}';
    }
}
