package com.easc.websocketapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;

@Configuration
public class AwsConfiguration {

    @Bean
    public CloudWatchClient cloudWatchClient(AppProperties appProperties) {
        return CloudWatchClient.builder()
                .region(Region.of(appProperties.getAwsRegion()))
                .build();
    }
}
