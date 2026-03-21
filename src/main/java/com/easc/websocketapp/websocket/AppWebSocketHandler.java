package com.easc.websocketapp.websocket;

import com.easc.websocketapp.connection.SessionRegistry;
import com.easc.websocketapp.metrics.MetricsService;
import com.easc.websocketapp.model.WsMessage;
import com.easc.websocketapp.model.WsMessageType;
import com.easc.websocketapp.redis.RedisRoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class AppWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AppWebSocketHandler.class);
    private static final String UNEXPECTED_DISCONNECT_RECORDED = "unexpectedDisconnectRecorded";

    private final SessionRegistry sessionRegistry;
    private final RedisRoutingService redisRoutingService;
    private final MetricsService metricsService;
    private final ObjectMapper objectMapper;

    public AppWebSocketHandler(
            SessionRegistry sessionRegistry,
            RedisRoutingService redisRoutingService,
            MetricsService metricsService,
            ObjectMapper objectMapper
    ) {
        this.sessionRegistry = sessionRegistry;
        this.redisRoutingService = redisRoutingService;
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = (String) session.getAttributes().get(UserIdHandshakeInterceptor.USER_ID_ATTRIBUTE);
        if (!StringUtils.hasText(userId)) {
            session.close(CloseStatus.BAD_DATA.withReason("missing userId"));
            return;
        }

        try {
            sessionRegistry.register(userId, session);
        } catch (RuntimeException exception) {
            log.warn("Failed to register user {}", userId, exception);
            session.close(CloseStatus.SERVER_ERROR.withReason("failed to register user"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        String userId = (String) session.getAttributes().get(UserIdHandshakeInterceptor.USER_ID_ATTRIBUTE);
        if (!StringUtils.hasText(userId)) {
            log.warn("Received message without a userId-bound session");
            return;
        }

        final WsMessage message;
        try {
            message = objectMapper.readValue(textMessage.getPayload(), WsMessage.class);
        } catch (Exception exception) {
            log.warn("Invalid message from user {}", userId, exception);
            return;
        }

        message.setSenderId(userId);
        message.setTimestamp(Instant.now());

        if (message.getType() == WsMessageType.CHAT_MESSAGE) {
            log.info("Received chat message from {}: {}", userId, message.getPayload());
            redisRoutingService.sendMessageToUser(message);
            metricsService.onDirectMessageReceived();
            return;
        }

        if (message.getType() == WsMessageType.ROOM_JOIN) {
            if (!StringUtils.hasText(message.getRoomId())) {
                log.warn("Invalid room join from {} with empty roomId", userId);
                return;
            }

            sessionRegistry.joinRoom(session.getId(), message.getRoomId());
            log.info("User {} joined room {}", userId, message.getRoomId());
            return;
        }

        if (message.getType() == WsMessageType.ROOM_LEAVE) {
            if (!StringUtils.hasText(message.getRoomId())) {
                log.warn("Invalid room leave from {} with empty roomId", userId);
                return;
            }

            sessionRegistry.leaveRoom(session.getId(), message.getRoomId());
            log.info("User {} left room {}", userId, message.getRoomId());
            return;
        }

        if (message.getType() == WsMessageType.ROOM_MESSAGE) {
            if (!StringUtils.hasText(message.getRoomId())) {
                log.warn("Invalid room message from {} with empty roomId", userId);
                return;
            }
            if (!sessionRegistry.isSessionInRoom(session.getId(), message.getRoomId())) {
                log.warn("User {} attempted to send room message without joining room {}", userId, message.getRoomId());
                return;
            }

            log.info("Received room message from {} for room {}", userId, message.getRoomId());
            redisRoutingService.sendMessageToRoom(message);
            metricsService.onRoomMessageReceived();
            return;
        }

        if (message.getType() == WsMessageType.LATENCY_REPORT) {
            message.payloadAsDouble().ifPresentOrElse(
                    latency -> {
                        log.info("Received latency report from {}: {}", userId, latency);
                        metricsService.onLatencyReport(latency);
                    },
                    () -> log.warn("Invalid latency report from {}: {}", userId, message.getPayload())
            );
            return;
        }

        log.warn("Unknown message type from {}: {}", userId, message.getType());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        if (markUnexpectedDisconnect(session)) {
            metricsService.onUnexpectedDisconnect();
        }
        log.warn(
                "Unexpected disconnect for user {}",
                session.getAttributes().get(UserIdHandshakeInterceptor.USER_ID_ATTRIBUTE),
                exception
        );
        sessionRegistry.unregisterBySessionId(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (!CloseStatus.NORMAL.equals(status)
                && !CloseStatus.GOING_AWAY.equals(status)
                && markUnexpectedDisconnect(session)) {
            metricsService.onUnexpectedDisconnect();
        }
        sessionRegistry.unregisterBySessionId(session.getId());
    }

    private boolean markUnexpectedDisconnect(WebSocketSession session) {
        return session.getAttributes().put(UNEXPECTED_DISCONNECT_RECORDED, Boolean.TRUE) == null;
    }
}
