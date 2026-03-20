package com.easc.websocketapp.redis;

import java.util.List;

public record RedisEndpointSet(List<RedisEndpoint> endpoints, String password, boolean clusterMode) {
}
