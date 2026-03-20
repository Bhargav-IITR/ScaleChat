package com.easc.websocketapp.metrics;

import com.easc.websocketapp.config.AppProperties;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

@Service
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    private final CloudWatchClient cloudWatchClient;
    private final AppProperties appProperties;

    private final AtomicLong activeConnectionsCount = new AtomicLong();
    private final AtomicLong messagesDeliveredCount = new AtomicLong();
    private final AtomicLong messagesReceivedCount = new AtomicLong();
    private final AtomicLong unexpectedDisconnectsCount = new AtomicLong();
    private final AtomicLong latencyTotal = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();

    public MetricsService(CloudWatchClient cloudWatchClient, AppProperties appProperties) {
        this.cloudWatchClient = cloudWatchClient;
        this.appProperties = appProperties;
    }

    public void onClientConnect() {
        activeConnectionsCount.incrementAndGet();
    }

    public void onClientDisconnect() {
        activeConnectionsCount.updateAndGet(current -> Math.max(0, current - 1));
    }

    public void onMessageDelivered() {
        messagesDeliveredCount.incrementAndGet();
    }

    public void onUnexpectedDisconnect() {
        unexpectedDisconnectsCount.incrementAndGet();
    }

    public void onMessageReceived() {
        messagesReceivedCount.incrementAndGet();
    }

    public void onLatencyReport(double latencyMs) {
        latencyTotal.addAndGet(Math.round(latencyMs));
        latencyCount.incrementAndGet();
    }

    @Scheduled(fixedDelayString = "${app.metrics.cloudwatch-interval-ms:60000}")
    public void pushMetricsToCloudWatch() {
        long active = activeConnectionsCount.get();
        long messagesReceived = messagesReceivedCount.get();
        long messagesDelivered = messagesDeliveredCount.get();
        long unexpectedDisconnects = unexpectedDisconnectsCount.get();
        long totalLatency = latencyTotal.getAndSet(0);
        long totalLatencySamples = latencyCount.getAndSet(0);

        double averageLatency = totalLatencySamples > 0
                ? (double) totalLatency / totalLatencySamples
                : 0.0d;

        PutMetricDataRequest request = PutMetricDataRequest.builder()
                .namespace("wss/metrics")
                .metricData(List.of(
                        countMetric("ActiveConnections", active),
                        countMetric("MessagesTotal", messagesReceived),
                        countMetric("MessagesDelivered", messagesDelivered),
                        countMetric("UnexpectedDisconnects", unexpectedDisconnects),
                        millisecondsMetric("AverageLatencyMs", averageLatency)
                ))
                .build();

        try {
            cloudWatchClient.putMetricData(request);
            log.info("Pushed metrics to CloudWatch");
        } catch (RuntimeException exception) {
            log.warn("Failed to push metrics to CloudWatch", exception);
        }
    }

    private MetricDatum countMetric(String name, double value) {
        return MetricDatum.builder()
                .metricName(name)
                .value(value)
                .unit(StandardUnit.COUNT)
                .dimensions(serverDimension())
                .build();
    }

    private MetricDatum millisecondsMetric(String name, double value) {
        return MetricDatum.builder()
                .metricName(name)
                .value(value)
                .unit(StandardUnit.MILLISECONDS)
                .dimensions(serverDimension())
                .build();
    }

    private Dimension serverDimension() {
        return Dimension.builder()
                .name("ServerID")
                .value(appProperties.getServerId())
                .build();
    }
}
