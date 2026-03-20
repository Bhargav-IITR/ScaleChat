package com.easc.websocketapp;

import com.easc.websocketapp.config.DotenvBootstrap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WebsocketAppApplication {

    public static void main(String[] args) {
        DotenvBootstrap.loadIntoSystemProperties();

        SpringApplication application = new SpringApplication(WebsocketAppApplication.class);
        application.setDefaultProperties(
                Map.of("server.port", DotenvBootstrap.get("WSS_PORT", "8081"))
        );
        application.run(args);
    }
}
