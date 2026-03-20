package com.easc.websocketapp.connection;

import static org.assertj.core.api.Assertions.assertThat;

import com.easc.websocketapp.config.AppProperties;
import com.easc.websocketapp.metrics.MetricsService;
import com.easc.websocketapp.redis.RedisPresenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketSession;

class SessionRegistryTest {

    private TrackingRedisPresenceService redisPresenceService;
    private CountingMetricsService metricsService;
    private TaskScheduler taskScheduler;
    private TrackingScheduledFuture heartbeatFutureOne;
    private TrackingScheduledFuture heartbeatFutureTwo;
    private WebSocketSession sessionOne;
    private WebSocketSession sessionTwo;

    private SessionRegistry sessionRegistry;
    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties(
                "server-1",
                "8081",
                "supersecret",
                "development",
                "redis://localhost:6379",
                "ap-south-1"
        );

        redisPresenceService = new TrackingRedisPresenceService();
        metricsService = new CountingMetricsService(appProperties);
        heartbeatFutureOne = new TrackingScheduledFuture();
        heartbeatFutureTwo = new TrackingScheduledFuture();
        taskScheduler = taskSchedulerReturning(heartbeatFutureOne, heartbeatFutureTwo);
        sessionOne = webSocketSession("session-1");
        sessionTwo = webSocketSession("session-2");

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
        sessionRegistry.register("alice", sessionOne);
        sessionRegistry.register("alice", sessionTwo);

        assertThat(sessionRegistry.localConnectionCount("alice")).isEqualTo(2);
        assertThat(redisPresenceService.registerCalls).isEqualTo(2);

        sessionRegistry.unregisterBySessionId("session-1");

        assertThat(sessionRegistry.localConnectionCount("alice")).isEqualTo(1);
        assertThat(redisPresenceService.unregisterCalls).isZero();
        assertThat(heartbeatFutureOne.cancelled).isTrue();

        sessionRegistry.unregisterBySessionId("session-2");

        assertThat(sessionRegistry.localConnectionCount("alice")).isZero();
        assertThat(redisPresenceService.unregisterCalls).isEqualTo(1);
        assertThat(redisPresenceService.lastUnregisteredUserId).isEqualTo("alice");
        assertThat(redisPresenceService.lastUnregisteredServerId).isEqualTo("server-1");
        assertThat(metricsService.clientConnectCount).isEqualTo(2);
        assertThat(metricsService.clientDisconnectCount).isEqualTo(2);
        assertThat(heartbeatFutureTwo.cancelled).isTrue();
    }

    private TaskScheduler taskSchedulerReturning(ScheduledFuture<?>... futures) {
        Deque<ScheduledFuture<?>> returnedFutures = new ArrayDeque<>();
        for (ScheduledFuture<?> future : futures) {
            returnedFutures.addLast(future);
        }

        return (TaskScheduler) Proxy.newProxyInstance(
                TaskScheduler.class.getClassLoader(),
                new Class<?>[]{TaskScheduler.class},
                (proxy, method, args) -> {
                    if ("scheduleAtFixedRate".equals(method.getName())
                            && args.length == 2
                            && args[0] instanceof Runnable
                            && Duration.ofSeconds(30).equals(args[1])) {
                        return returnedFutures.removeFirst();
                    }

                    throw new UnsupportedOperationException("Unexpected TaskScheduler call: " + method.getName());
                }
        );
    }

    private WebSocketSession webSocketSession(String sessionId) {
        Map<String, Object> attributes = new HashMap<>();
        SessionState sessionState = new SessionState(sessionId, attributes);

        return (WebSocketSession) Proxy.newProxyInstance(
                WebSocketSession.class.getClassLoader(),
                new Class<?>[]{WebSocketSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> sessionState.id;
                    case "getAttributes" -> sessionState.attributes;
                    case "getHandshakeHeaders" -> HttpHeaders.EMPTY;
                    case "getExtensions" -> java.util.List.<WebSocketExtension>of();
                    case "isOpen" -> sessionState.open;
                    case "close" -> {
                        sessionState.open = false;
                        yield null;
                    }
                    case "sendMessage" -> null;
                    case "setTextMessageSizeLimit", "setBinaryMessageSizeLimit" -> null;
                    case "getTextMessageSizeLimit", "getBinaryMessageSizeLimit" -> 64 * 1024;
                    case "getAcceptedProtocol", "getUri", "getPrincipal", "getLocalAddress", "getRemoteAddress" -> null;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class SessionState {
        private final String id;
        private final Map<String, Object> attributes;
        private boolean open = true;

        private SessionState(String id, Map<String, Object> attributes) {
            this.id = id;
            this.attributes = attributes;
        }
    }

    private static final class TrackingRedisPresenceService extends RedisPresenceService {
        private int registerCalls;
        private int unregisterCalls;
        private String lastUnregisteredUserId;
        private String lastUnregisteredServerId;

        private TrackingRedisPresenceService() {
            super(null);
        }

        @Override
        public void registerUserOnServer(String userId, String serverId) {
            registerCalls++;
        }

        @Override
        public void unregisterUserFromServer(String userId, String serverId) {
            unregisterCalls++;
            lastUnregisteredUserId = userId;
            lastUnregisteredServerId = serverId;
        }
    }

    private static final class CountingMetricsService extends MetricsService {
        private int clientConnectCount;
        private int clientDisconnectCount;

        private CountingMetricsService(AppProperties appProperties) {
            super(null, appProperties);
        }

        @Override
        public void onClientConnect() {
            clientConnectCount++;
        }

        @Override
        public void onClientDisconnect() {
            clientDisconnectCount++;
        }
    }

    private static final class TrackingScheduledFuture implements ScheduledFuture<Object> {
        private boolean cancelled;

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    }
}
