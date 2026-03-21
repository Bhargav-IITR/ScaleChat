package com.easc.websocketapp.metrics;

import com.easc.websocketapp.config.AppProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

@Service
public class MetricsService {

    private final AtomicInteger activeConnectionsCount = new AtomicInteger();
    private final AtomicInteger activeRoomsCount = new AtomicInteger();
    private final Counter messagesCounter;
    private final Counter directMessagesCounter;
    private final Counter roomMessagesCounter;
    private final Counter messagesDeliveredCounter;
    private final Counter unexpectedDisconnectsCounter;
    private final Counter roomJoinsCounter;
    private final Counter roomLeavesCounter;
    private final DistributionSummary latencySummary;

    public MetricsService(MeterRegistry meterRegistry, AppProperties appProperties) {
        List<Tag> tags = List.of(Tag.of("server_id", appProperties.getServerId()));

        Gauge.builder("wss.active.connections", activeConnectionsCount, AtomicInteger::get)
                .description("Currently connected websocket clients")
                .tags(tags)
                .register(meterRegistry);
        Gauge.builder("wss.active.rooms", activeRoomsCount, AtomicInteger::get)
                .description("Rooms with at least one local member")
                .tags(tags)
                .register(meterRegistry);

        messagesCounter = Counter.builder("wss.messages")
                .description("Total websocket messages received")
                .tags(tags)
                .register(meterRegistry);
        directMessagesCounter = Counter.builder("wss.direct.messages")
                .description("Direct websocket messages received")
                .tags(tags)
                .register(meterRegistry);
        roomMessagesCounter = Counter.builder("wss.room.messages")
                .description("Room websocket messages received")
                .tags(tags)
                .register(meterRegistry);
        messagesDeliveredCounter = Counter.builder("wss.messages.delivered")
                .description("Messages delivered to local websocket sessions")
                .tags(tags)
                .register(meterRegistry);
        unexpectedDisconnectsCounter = Counter.builder("wss.unexpected.disconnects")
                .description("Unexpected websocket disconnects")
                .tags(tags)
                .register(meterRegistry);
        roomJoinsCounter = Counter.builder("wss.room.joins")
                .description("Successful room join operations")
                .tags(tags)
                .register(meterRegistry);
        roomLeavesCounter = Counter.builder("wss.room.leaves")
                .description("Successful room leave operations")
                .tags(tags)
                .register(meterRegistry);
        latencySummary = DistributionSummary.builder("wss.latency.ms")
                .description("Latency reports submitted by clients in milliseconds")
                .baseUnit("milliseconds")
                .tags(tags)
                .register(meterRegistry);
    }

    public void onClientConnect() {
        activeConnectionsCount.incrementAndGet();
    }

    public void onClientDisconnect() {
        activeConnectionsCount.updateAndGet(current -> Math.max(0, current - 1));
    }

    public void updateActiveRooms(int activeRoomCount) {
        activeRoomsCount.set(Math.max(0, activeRoomCount));
    }

    public void onMessageDelivered() {
        messagesDeliveredCounter.increment();
    }

    public void onUnexpectedDisconnect() {
        unexpectedDisconnectsCounter.increment();
    }

    public void onDirectMessageReceived() {
        messagesCounter.increment();
        directMessagesCounter.increment();
    }

    public void onRoomMessageReceived() {
        messagesCounter.increment();
        roomMessagesCounter.increment();
    }

    public void onRoomJoin() {
        roomJoinsCounter.increment();
    }

    public void onRoomLeave() {
        roomLeavesCounter.increment();
    }

    public void onLatencyReport(double latencyMs) {
        latencySummary.record(latencyMs);
    }
}
