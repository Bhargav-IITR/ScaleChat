package com.easc.websocketapp.connection;

import com.easc.websocketapp.config.AppProperties;
import com.easc.websocketapp.metrics.MetricsService;
import com.easc.websocketapp.model.WsMessage;
import com.easc.websocketapp.redis.RedisPresenceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_SIZE_BYTES = 512 * 1024;

    private final AppProperties appProperties;
    private final RedisPresenceService redisPresenceService;
    private final MetricsService metricsService;
    private final TaskScheduler taskScheduler;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, ConcurrentMap<String, ClientSession>> clientsByUser = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ClientSession> sessionsById = new ConcurrentHashMap<>();

    public SessionRegistry(
            AppProperties appProperties,
            RedisPresenceService redisPresenceService,
            MetricsService metricsService,
            TaskScheduler taskScheduler,
            ObjectMapper objectMapper
    ) {
        this.appProperties = appProperties;
        this.redisPresenceService = redisPresenceService;
        this.metricsService = metricsService;
        this.taskScheduler = taskScheduler;
        this.objectMapper = objectMapper;
    }

    public ClientSession register(String userId, WebSocketSession rawSession) {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(
                rawSession,
                SEND_TIME_LIMIT_MS,
                SEND_BUFFER_SIZE_BYTES
        );
        String connectionId = UUID.randomUUID().toString();
        ScheduledFuture<?> heartbeatFuture = null;

        try {
            redisPresenceService.registerUserOnServer(userId, appProperties.getServerId());
            heartbeatFuture = taskScheduler.scheduleAtFixedRate(
                    () -> refreshPresence(userId),
                    HEARTBEAT_INTERVAL
            );

            ClientSession clientSession = new ClientSession(connectionId, userId, session, heartbeatFuture);
            clientsByUser.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
                    .put(connectionId, clientSession);
            sessionsById.put(rawSession.getId(), clientSession);

            metricsService.onClientConnect();
            log.info("User {} connected to server {}", userId, appProperties.getServerId());
            return clientSession;
        } catch (RuntimeException exception) {
            if (heartbeatFuture != null) {
                heartbeatFuture.cancel(true);
            }
            quietlyClose(session);
            throw exception;
        }
    }

    public void unregisterBySessionId(String sessionId) {
        ClientSession clientSession = sessionsById.remove(sessionId);
        if (clientSession == null) {
            return;
        }

        ScheduledFuture<?> heartbeatFuture = clientSession.heartbeatFuture();
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
        }

        boolean lastConnectionForUser = removeFromUserIndex(clientSession);
        if (lastConnectionForUser) {
            try {
                redisPresenceService.unregisterUserFromServer(
                        clientSession.userId(),
                        appProperties.getServerId()
                );
            } catch (RuntimeException exception) {
                log.warn("Failed to remove Redis presence for user {}", clientSession.userId(), exception);
            }
        }

        quietlyClose(clientSession.session());
        metricsService.onClientDisconnect();
        log.info("User {} disconnected from server {}", clientSession.userId(), appProperties.getServerId());
    }

    public void sendMessageToLocalUser(WsMessage message) {
        if (!StringUtils.hasText(message.getReceiverID())) {
            log.warn("receiverID is empty, cannot send message: {}", message);
            return;
        }

        Map<String, ClientSession> userClients = clientsByUser.get(message.getReceiverID());
        if (userClients == null || userClients.isEmpty()) {
            return;
        }

        final String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            log.warn("Failed to serialize outbound message", exception);
            return;
        }

        for (ClientSession clientSession : userClients.values()) {
            try {
                clientSession.session().sendMessage(new TextMessage(payload));
                metricsService.onMessageDelivered();
            } catch (IOException exception) {
                log.warn("Failed to send message to user {}", clientSession.userId(), exception);
            }
        }
    }

    int localConnectionCount(String userId) {
        Map<String, ClientSession> sessions = clientsByUser.get(userId);
        return sessions == null ? 0 : sessions.size();
    }

    private void refreshPresence(String userId) {
        try {
            redisPresenceService.refreshUserPresence(userId, appProperties.getServerId());
        } catch (RuntimeException exception) {
            log.warn("Failed to refresh heartbeat for user {}", userId, exception);
        }
    }

    private boolean removeFromUserIndex(ClientSession clientSession) {
        ConcurrentMap<String, ClientSession> sessions = clientsByUser.get(clientSession.userId());
        if (sessions == null) {
            return true;
        }

        sessions.remove(clientSession.connectionId());
        if (!sessions.isEmpty()) {
            return false;
        }

        clientsByUser.remove(clientSession.userId(), sessions);
        return true;
    }

    private void quietlyClose(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (IOException exception) {
            log.debug("Ignoring close failure for session {}", session.getId(), exception);
        }
    }
}
