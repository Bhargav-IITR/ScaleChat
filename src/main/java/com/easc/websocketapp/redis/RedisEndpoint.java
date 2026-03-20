package com.easc.websocketapp.redis;

public record RedisEndpoint(String host, int port, String password) {

    public String asHostPort() {
        return host + ":" + port;
    }
}
