package com.easc.websocketapp.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.easc.websocketapp.config.AppProperties;
import com.easc.websocketapp.metrics.MetricsService;
import com.easc.websocketapp.redis.RedisPresenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class SessionRegistryTest {

    @Mock
    private RedisPresenceService redisPresenceService;

    @Mock
    private MetricsService metricsService;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private ScheduledFuture<?> heartbeatFutureOne;

    @Mock
    private ScheduledFuture<?> heartbeatFutureTwo;

    @Mock
    private WebSocketSession sessionOne;

    @Mock
    private WebSocketSession sessionTwo;

    private SessionRegistry sessionRegistry;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties(
                "server-1",
                "8081",
                "supersecret",
                "development",
                "redis://localhost:6379",
                "ap-south-1"
        );

        sessionRegistry = new SessionRegistry(
                appProperties,
                redisPresenceService,
                metricsService,
                taskScheduler,
                new ObjectMapper()
        );
    }

    @Test
    void keepsPresenceRegisteredUntilLastLocalConnectionCloses() {
        when(sessionOne.getId()).thenReturn("session-1");
        when(sessionTwo.getId()).thenReturn("session-2");
        when(sessionOne.isOpen()).thenReturn(true);
        when(sessionTwo.isOpen()).thenReturn(true);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofSeconds(30))))
                .thenReturn(heartbeatFutureOne, heartbeatFutureTwo);

        sessionRegistry.register("alice", sessionOne);
        sessionRegistry.register("alice", sessionTwo);

        assertThat(sessionRegistry.localConnectionCount("alice")).isEqualTo(2);

        sessionRegistry.unregisterBySessionId("session-1");

        assertThat(sessionRegistry.localConnectionCount("alice")).isEqualTo(1);
        verify(redisPresenceService, never()).unregisterUserFromServer("alice", "server-1");

        sessionRegistry.unregisterBySessionId("session-2");

        assertThat(sessionRegistry.localConnectionCount("alice")).isZero();
        verify(redisPresenceService).unregisterUserFromServer("alice", "server-1");
        verify(metricsService, times(2)).onClientConnect();
        verify(metricsService, times(2)).onClientDisconnect();
    }
}
