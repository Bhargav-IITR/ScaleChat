package com.easc.websocketapp.redis;

import com.easc.websocketapp.connection.SessionRegistry;
import com.easc.websocketapp.model.WsMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class RedisServerSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisServerSubscriber.class);

    private final ObjectMapper objectMapper;
    private final SessionRegistry sessionRegistry;

    public RedisServerSubscriber(ObjectMapper objectMapper, SessionRegistry sessionRegistry) {
        this.objectMapper = objectMapper;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);

        log.info("Received message from Redis channel {}: {}", channel, payload);

        try {
            WsMessage wsMessage = objectMapper.readValue(payload, WsMessage.class);
            sessionRegistry.sendMessageToLocalUser(wsMessage);
        } catch (Exception exception) {
            log.warn("Invalid message from Redis pub/sub", exception);
        }
    }
}
