package com.easc.websocketapp.config;

public class AppProperties {

    private final String serverId;
    private final String wssPort;
    private final String jwtSecret;
    private final String environment;
    private final String redisUri;

    public AppProperties(
            String serverId,
            String wssPort,
            String jwtSecret,
            String environment,
            String redisUri
    ) {
        this.serverId = serverId;
        this.wssPort = wssPort;
        this.jwtSecret = jwtSecret;
        this.environment = environment;
        this.redisUri = redisUri;
    }

    public String getServerId() {
        return serverId;
    }

    public String getWssPort() {
        return wssPort;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getRedisUri() {
        return redisUri;
    }

    public String getServerChannel() {
        return "server:" + serverId;
    }
}
