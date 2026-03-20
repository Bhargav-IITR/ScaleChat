package com.easc.websocketapp.redis;

import com.easc.websocketapp.model.WsMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RedisRoutingService {

    private static final Logger log = LoggerFactory.getLogger(RedisRoutingService.class);

    private final RedisPresenceService redisPresenceService;
    private final ObjectMapper objectMapper;

    public RedisRoutingService(RedisPresenceService redisPresenceService, ObjectMapper objectMapper) {
        this.redisPresenceService = redisPresenceService;
        this.objectMapper = objectMapper;
    }

    public boolean sendMessageToUser(WsMessage message) {
        if (!StringUtils.hasText(message.getReceiverID())) {
            log.warn("receiverID is empty, cannot send message: {}", message);
            return false;
        }

        final Set<String> serverIds;
        try {
            serverIds = redisPresenceService.getServerIdsForUser(message.getReceiverID());
        } catch (RuntimeException exception) {
            log.warn("Failed to resolve Redis routes for user {}", message.getReceiverID(), exception);
            return false;
        }

        if (serverIds.isEmpty()) {
            log.info("User {} is not connected to any server", message.getReceiverID());
            return false;
        }

        final String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            log.warn("Failed to serialize message for Redis publish", exception);
            return false;
        }

        boolean published = false;
        for (String serverId : serverIds) {
            try {
                if (!redisPresenceService.isUserOnlineOnServer(message.getReceiverID(), serverId)) {
                    redisPresenceService.removeServerMapping(message.getReceiverID(), serverId);
                    continue;
                }

                redisPresenceService.publishToServerChannel(serverId, payload);
                published = true;
            } catch (RuntimeException exception) {
                log.warn(
                        "Failed to publish message for user {} via server {}",
                        message.getReceiverID(),
                        serverId,
                        exception
                );
            }
        }

        return published;
    }
}
