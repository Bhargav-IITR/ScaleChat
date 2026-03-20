package com.easc.websocketapp.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DotenvBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DotenvBootstrap.class);

    private static volatile boolean loaded;
    private static Map<String, String> dotenvValues = Collections.emptyMap();

    private DotenvBootstrap() {
    }

    public static synchronized void loadIntoSystemProperties() {
        if (loaded) {
            return;
        }

        try {
            Dotenv dotenv = Dotenv.configure()
                    .filename(".env.local")
                    .ignoreIfMalformed()
                    .ignoreIfMissing()
                    .load();

            Map<String, String> values = new HashMap<>();
            dotenv.entries().forEach(entry -> {
                values.put(entry.getKey(), entry.getValue());
                if (System.getenv(entry.getKey()) == null && System.getProperty(entry.getKey()) == null) {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            });
            dotenvValues = Map.copyOf(values);

            if (!values.isEmpty()) {
                log.info("Loaded {} entries from .env.local", values.size());
            }
        } catch (DotenvException exception) {
            log.warn("Unable to load .env.local, continuing with system environment: {}", exception.getMessage());
        }

        loaded = true;
    }

    public static String get(String key, String defaultValue) {
        loadIntoSystemProperties();

        String systemProperty = System.getProperty(key);
        if (systemProperty != null && !systemProperty.isBlank()) {
            return systemProperty;
        }

        String environmentValue = System.getenv(key);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }

        String dotenvValue = dotenvValues.get(key);
        if (dotenvValue != null && !dotenvValue.isBlank()) {
            return dotenvValue;
        }

        return defaultValue;
    }
}
