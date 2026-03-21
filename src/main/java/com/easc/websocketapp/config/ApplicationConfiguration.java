package com.easc.websocketapp.config;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ApplicationConfiguration {

    @Bean
    public AppProperties appProperties(
            @Value("${WSS_PORT:8081}") String wssPort,
            @Value("${JWT_SECRET:supersecret}") String jwtSecret,
            @Value("${ENVIRONMENT:development}") String environment,
            @Value("${REDIS_URI:redis://localhost:6379}") String redisUri
    ) {
        return new AppProperties(
                UUID.randomUUID().toString(),
                wssPort,
                jwtSecret,
                environment,
                redisUri
        );
    }

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("ws-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
