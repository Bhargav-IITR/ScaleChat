package com.easc.websocketapp.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {

    private final AppWebSocketHandler appWebSocketHandler;
    private final UserIdHandshakeInterceptor userIdHandshakeInterceptor;

    public WebSocketConfiguration(
            AppWebSocketHandler appWebSocketHandler,
            UserIdHandshakeInterceptor userIdHandshakeInterceptor
    ) {
        this.appWebSocketHandler = appWebSocketHandler;
        this.userIdHandshakeInterceptor = userIdHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(appWebSocketHandler, "/ws")
                .addInterceptors(userIdHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
