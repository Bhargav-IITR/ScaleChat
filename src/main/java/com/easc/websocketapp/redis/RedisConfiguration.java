package com.easc.websocketapp.redis;

import com.easc.websocketapp.config.AppProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.util.StringUtils;

@Configuration
public class RedisConfiguration {

    @Bean
    public RedisEndpointSet redisEndpointSet(AppProperties appProperties) {
        return RedisUriParser.parse(appProperties.getRedisUri());
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory(RedisEndpointSet endpointSet) {
        if (endpointSet.clusterMode()) {
            RedisClusterConfiguration configuration = new RedisClusterConfiguration(
                    endpointSet.endpoints().stream()
                            .map(RedisEndpoint::asHostPort)
                            .toList()
            );
            if (StringUtils.hasText(endpointSet.password())) {
                configuration.setPassword(RedisPassword.of(endpointSet.password()));
            }
            return new LettuceConnectionFactory(configuration);
        }

        RedisEndpoint endpoint = endpointSet.endpoints().getFirst();
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                endpoint.host(),
                endpoint.port()
        );
        if (StringUtils.hasText(endpoint.password())) {
            configuration.setPassword(RedisPassword.of(endpoint.password()));
        }
        return new LettuceConnectionFactory(configuration);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory redisConnectionFactory,
            RedisServerSubscriber redisServerSubscriber,
            AppProperties appProperties
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener(
                redisServerSubscriber,
                List.of(new ChannelTopic(appProperties.getServerChannel()))
        );
        return container;
    }
}
