package com.easc.websocketapp.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedisUriParserTest {

    @Test
    void parsesSingleNodeRedisUri() {
        RedisEndpointSet endpointSet = RedisUriParser.parse("redis://localhost:6380");

        assertThat(endpointSet.clusterMode()).isFalse();
        assertThat(endpointSet.endpoints())
                .containsExactly(new RedisEndpoint("localhost", 6380, null));
    }

    @Test
    void parsesClusterUriListAndReusesPassword() {
        RedisEndpointSet endpointSet = RedisUriParser.parse(
                "redis://:secret@redis-a:6379,redis://redis-b:6380"
        );

        assertThat(endpointSet.clusterMode()).isTrue();
        assertThat(endpointSet.password()).isEqualTo("secret");
        assertThat(endpointSet.endpoints())
                .containsExactly(
                        new RedisEndpoint("redis-a", 6379, "secret"),
                        new RedisEndpoint("redis-b", 6380, null)
                );
    }
}
