package com.easc.websocketapp.redis;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisPresenceService {

    private static final Duration PRESENCE_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;

    public RedisPresenceService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void registerUserOnServer(String userId, String serverId) {
        redisTemplate.opsForSet().add(userServersKey(userId), serverId);
        redisTemplate.opsForValue().set(userOnlineKey(userId, serverId), "1", PRESENCE_TTL);
    }

    public void refreshUserPresence(String userId, String serverId) {
        redisTemplate.expire(userOnlineKey(userId, serverId), PRESENCE_TTL);
    }

    public void unregisterUserFromServer(String userId, String serverId) {
        redisTemplate.opsForSet().remove(userServersKey(userId), serverId);
        redisTemplate.delete(userOnlineKey(userId, serverId));
    }

    public Set<String> getServerIdsForUser(String userId) {
        Set<String> members = redisTemplate.opsForSet().members(userServersKey(userId));
        if (members == null || members.isEmpty()) {
            return Set.of();
        }

        return members.stream()
                .filter(member -> member != null && !member.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isUserOnlineOnServer(String userId, String serverId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(userOnlineKey(userId, serverId)));
    }

    public void removeServerMapping(String userId, String serverId) {
        redisTemplate.opsForSet().remove(userServersKey(userId), serverId);
    }

    public void publishToServerChannel(String serverId, String payload) {
        redisTemplate.convertAndSend(serverChannel(serverId), payload);
    }

    public static String userServersKey(String userId) {
        return "user_servers:" + userId;
    }

    public static String userOnlineKey(String userId, String serverId) {
        return "user_online:" + userId + ":" + serverId;
    }

    public static String serverChannel(String serverId) {
        return "server:" + serverId;
    }
}
