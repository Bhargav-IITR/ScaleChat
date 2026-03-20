package com.easc.websocketapp.connection;

import java.util.concurrent.ScheduledFuture;
import org.springframework.web.socket.WebSocketSession;

public record ClientSession(
        String connectionId,
        String userId,
        WebSocketSession session,
        ScheduledFuture<?> heartbeatFuture
) {
}
