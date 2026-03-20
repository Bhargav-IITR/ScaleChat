package com.easc.websocketapp.redis;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.util.StringUtils;

public final class RedisUriParser {

    private RedisUriParser() {
    }

    public static RedisEndpointSet parse(String redisUriValue) {
        List<RedisEndpoint> endpoints = Arrays.stream(redisUriValue.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(RedisUriParser::parseEndpoint)
                .toList();

        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("REDIS_URI must contain at least one endpoint");
        }

        String password = endpoints.stream()
                .map(RedisEndpoint::password)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);

        return new RedisEndpointSet(endpoints, password, endpoints.size() > 1);
    }

    private static RedisEndpoint parseEndpoint(String rawValue) {
        String normalizedValue = rawValue.contains("://") ? rawValue : "redis://" + rawValue;
        URI uri = URI.create(normalizedValue);

        String password = null;
        if (uri.getUserInfo() != null) {
            String[] userInfoParts = uri.getUserInfo().split(":", 2);
            if (userInfoParts.length == 2 && StringUtils.hasText(userInfoParts[1])) {
                password = userInfoParts[1];
            }
        }

        int port = uri.getPort() > 0 ? uri.getPort() : 6379;
        return new RedisEndpoint(uri.getHost(), port, password);
    }
}
