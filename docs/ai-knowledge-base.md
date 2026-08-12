# AI Knowledge Base: `websocket-app`

Use this file as the primary project context for another AI during interview prep. It is written from the codebase in `D:\websocket\websocket-app-main`, not just from the README.

## What This Project Is

This is a Java 21 + Spring Boot WebSocket backend that supports:

- direct user-to-user messaging
- room/group messaging
- horizontal scaling across multiple app instances
- Redis-backed presence and routing
- Prometheus metrics
- Grafana dashboards

The main engineering problem it solves is: WebSocket connections are stateful, so once the app is scaled horizontally, the server that receives a message may not own the recipient's socket. This project solves that by:

- keeping local socket ownership in memory on each app instance
- storing user/server and room/server routing state in Redis
- publishing inter-server events through Redis Pub/Sub

## 30-Second Interview Pitch

"I built a horizontally scalable WebSocket server in Spring Boot. Each app instance owns only its local WebSocket sessions, while Redis is used for cross-instance coordination. Redis stores which server currently hosts a user or room, and each server subscribes to its own Redis channel. Direct messages and room messages can therefore reach users even when sender and receiver are connected to different backend instances. I also added Prometheus metrics, Grafana dashboards, Docker setup, and unit tests for the core registry and Redis URI parsing logic."

## Ground Truth Summary

- Language: Java 21
- Framework: Spring Boot 3.3.5
- Transport: raw Spring WebSocket, not STOMP
- Build tool: Maven
- Cache/message bus: Redis
- Observability: Spring Actuator + Micrometer + Prometheus + Grafana
- Auth state today: only `userId` passed as a query parameter
- Message durability today: none
- Chat history persistence today: none
- Multi-instance routing: yes
- Offline delivery: no

## Core Runtime Model

### 1. Connection model

Clients connect to:

```text
/ws?userId=<id>
```

`UserIdHandshakeInterceptor` checks that `userId` is present and stores it in the WebSocket session attributes. There is no JWT validation yet, even though `JWT_SECRET` exists in config.

### 2. Local ownership model

Each app instance keeps its own in-memory indexes:

- `clientsByUser`: `userId -> connectionId -> ClientSession`
- `clientsByRoom`: `roomId -> connectionId -> ClientSession`
- `sessionsById`: `webSocketSessionId -> ClientSession`

This means only the server that owns a socket can write to it.

### 3. Distributed routing model

Redis is used for cross-server coordination:

- `user_servers:{userId}` -> set of server IDs where the user has active sessions
- `user_online:{userId}:{serverId}` -> TTL key proving that user presence is still alive on that server
- `room:{roomId}` -> set of server IDs that currently have at least one local member of the room
- `server:{serverId}` -> Redis Pub/Sub channel subscribed to by exactly one app instance

### 4. Server identity

Every app instance gets a random UUID `serverId` at startup in `ApplicationConfiguration`. That UUID is the identity used in Redis sets and server channels. It is not derived from the port.

## End-to-End Flows

### Connection flow

1. Client connects to `/ws?userId=user1`.
2. `UserIdHandshakeInterceptor` validates that `userId` is non-empty.
3. `AppWebSocketHandler.afterConnectionEstablished()` calls `SessionRegistry.register(userId, session)`.
4. `SessionRegistry` wraps the session in `ConcurrentWebSocketSessionDecorator`.
5. The server writes presence into Redis:
   - add `serverId` to `user_servers:user1`
   - set `user_online:user1:{serverId}` with 60-second TTL
6. A heartbeat is scheduled every 30 seconds to refresh the TTL.
7. The local session is stored in the concurrent in-memory maps.

### Direct message flow

Sample payload:

```json
{
  "type": "chat_message",
  "payload": "hello from user 1",
  "receiverID": "user2"
}
```

Flow:

1. Sender sends `chat_message`.
2. `AppWebSocketHandler.handleTextMessage()` parses the JSON into `WsMessage`.
3. The backend overwrites `senderId` from the trusted WebSocket session attribute.
4. The backend sets `timestamp = Instant.now()`.
5. `RedisRoutingService.sendMessageToUser()` loads `user_servers:user2`.
6. For each candidate server:
   - check `user_online:user2:{serverId}`
   - if missing, remove stale mapping from `user_servers:user2`
   - if present, publish the serialized message to `server:{serverId}`
7. `RedisServerSubscriber` on the target server receives the Pub/Sub payload.
8. `SessionRegistry.sendMessageToLocalUser()` sends the message to all local sessions for `user2`.

Important nuance:

- if the receiver has multiple sessions on one server, all of those local sessions receive the message
- if the receiver is connected to multiple servers, the message is published to each active server

### Room join flow

Payload:

```json
{ "type": "room_join", "roomId": "general" }
```

Flow:

1. Client sends `room_join`.
2. `SessionRegistry.joinRoom(sessionId, roomId)` adds the room to that client session's local room set.
3. The session is inserted into `clientsByRoom`.
4. If this is the first local member of that room on this server, the server adds itself to Redis key `room:general`.

### Room message flow

Sample payload:

```json
{
  "type": "room_message",
  "roomId": "general",
  "payload": "hello room members"
}
```

Flow:

1. Client sends `room_message`.
2. `AppWebSocketHandler` verifies `roomId` is present.
3. The handler checks `sessionRegistry.isSessionInRoom(sessionId, roomId)`.
4. If the sender is not a local member of the room, the message is rejected.
5. `RedisRoutingService.sendMessageToRoom()` loads the Redis set `room:{roomId}`.
6. The message is serialized and published once per active server ID in that set.
7. Each server's `RedisServerSubscriber` receives the Pub/Sub message.
8. `SessionRegistry.sendMessageToLocalRoom()` broadcasts to all local sessions in that room.

Key design benefit:

- Redis publish is once per server, not once per user
- each server performs local fan-out to its own room members

Important nuance:

- the sender also receives the room message if their session is in that room, because room broadcast includes all local room members

### Room leave flow

Payload:

```json
{ "type": "room_leave", "roomId": "general" }
```

Flow:

1. `SessionRegistry.leaveRoom(sessionId, roomId)` removes the membership from the session.
2. The session is removed from the room's local map.
3. If the room no longer has any local members on this server, the server removes itself from Redis key `room:{roomId}`.

### Disconnect flow

Handled in both:

- `handleTransportError()`
- `afterConnectionClosed()`

Flow:

1. The handler records an unexpected-disconnect metric only once per session.
2. `SessionRegistry.unregisterBySessionId(sessionId)` runs cleanup.
3. The heartbeat future is cancelled.
4. The session is removed from all local indexes.
5. If this was the user's last local connection on this server:
   - remove `serverId` from `user_servers:{userId}`
   - delete `user_online:{userId}:{serverId}`
6. If the session was the last local member of a room on this server:
   - remove `serverId` from `room:{roomId}`
7. Close the WebSocket session if still open.

### Latency report flow

Sample payload:

```json
{
  "type": "latency_report",
  "payload": 150.232
}
```

Behavior:

- `payload` must be numeric
- `WsMessage.payloadAsDouble()` converts it
- `MetricsService.onLatencyReport()` records it into a Micrometer `DistributionSummary`

## Main Classes And Their Responsibilities

### Entry and config

- `src/main/java/com/easc/websocketapp/WebsocketAppApplication.java`
  - boots Spring Boot
  - loads `.env.local`
  - sets default `server.port` from `WSS_PORT`

- `src/main/java/com/easc/websocketapp/config/DotenvBootstrap.java`
  - loads `.env.local` into system properties if present
  - does not override already-set env vars/system properties

- `src/main/java/com/easc/websocketapp/config/ApplicationConfiguration.java`
  - creates `AppProperties`
  - generates random `serverId`
  - builds the shared `TaskScheduler`

- `src/main/java/com/easc/websocketapp/config/AppProperties.java`
  - stores runtime config like `serverId`, `WSS_PORT`, `JWT_SECRET`, `ENVIRONMENT`, `REDIS_URI`
  - exposes `getServerChannel()` as `server:{serverId}`

### WebSocket layer

- `src/main/java/com/easc/websocketapp/websocket/WebSocketConfiguration.java`
  - registers `/ws`
  - attaches the handshake interceptor
  - currently allows all origins via `setAllowedOriginPatterns("*")`

- `src/main/java/com/easc/websocketapp/websocket/UserIdHandshakeInterceptor.java`
  - extracts `userId` from the query string
  - rejects the handshake with `400 BAD_REQUEST` if missing

- `src/main/java/com/easc/websocketapp/websocket/AppWebSocketHandler.java`
  - main runtime dispatcher
  - handles connect, direct message, room join, room leave, room message, latency report, and disconnect

### Session management

- `src/main/java/com/easc/websocketapp/connection/SessionRegistry.java`
  - core local session state manager
  - stores local connections
  - handles room membership
  - refreshes presence via scheduled heartbeats
  - sends outbound messages to local users or local rooms
  - protects WebSocket writes using `ConcurrentWebSocketSessionDecorator`

- `src/main/java/com/easc/websocketapp/connection/ClientSession.java`
  - lightweight record storing connection metadata, session object, heartbeat future, and room IDs

### Messaging model

- `src/main/java/com/easc/websocketapp/model/WsMessage.java`
  - JSON message model
  - fields: `type`, `payload`, `receiverID`, `roomId`, `senderId`, `timestamp`
  - `payload` is generic `JsonNode`, not a strongly typed DTO

- `src/main/java/com/easc/websocketapp/model/WsMessageType.java`
  - supported values include:
    - `chat_message`
    - `room_join`
    - `room_leave`
    - `room_message`
    - `latency_report`
    - some extra enum constants like `notification`, `roadmap_ready`, `quiz_ready`

Important nuance:

- `notification`, `roadmap_ready`, and `quiz_ready` exist in the enum but are not explicitly handled in `AppWebSocketHandler`
- if the current app receives those directly from a client, they fall into the "unknown message type" path

### Redis layer

- `src/main/java/com/easc/websocketapp/redis/RedisConfiguration.java`
  - builds standalone or cluster Redis connection factories
  - wires `StringRedisTemplate`
  - subscribes the app instance to its own `server:{serverId}` channel

- `src/main/java/com/easc/websocketapp/redis/RedisUriParser.java`
  - parses a single Redis URI or comma-separated list of URIs
  - enables basic cluster mode when more than one endpoint is present

- `src/main/java/com/easc/websocketapp/redis/RedisPresenceService.java`
  - owns Redis key operations for users and rooms
  - publishes messages to server channels

- `src/main/java/com/easc/websocketapp/redis/RedisRoutingService.java`
  - decides where to publish messages
  - direct messages validate active TTL keys before publish
  - room messages publish to each server in the room set

- `src/main/java/com/easc/websocketapp/redis/RedisServerSubscriber.java`
  - receives Pub/Sub events for this server
  - sends room messages to local room members
  - sends all non-room messages to local user sessions

### Observability and web

- `src/main/java/com/easc/websocketapp/metrics/MetricsService.java`
  - defines gauges, counters, and latency summary
  - tags all metrics with `server_id`

- `src/main/java/com/easc/websocketapp/web/HealthController.java`
  - exposes `/health` on the app port

## Metrics

The code registers these Micrometer meter names:

- `wss.active.connections`
- `wss.active.rooms`
- `wss.messages`
- `wss.direct.messages`
- `wss.room.messages`
- `wss.messages.delivered`
- `wss.unexpected.disconnects`
- `wss.room.joins`
- `wss.room.leaves`
- `wss.latency.ms`

Prometheus usually exposes Micrometer dotted names in underscore form, so interview answers can mention both styles if needed.

Important nuance:

- `wss.messages` is incremented only for direct and room messages
- room joins, room leaves, and latency reports are not counted in that total even though the description says "Total websocket messages received"

## Configuration And Ports

### Environment variables

From `.env.example`:

- `WSS_PORT=8081`
- `METRICS_PORT=9000`
- `JWT_SECRET=secret`
- `ENVIRONMENT=development`
- `REDIS_URI=redis://localhost:6379`

### Ports

- app HTTP/WebSocket port: `WSS_PORT`, default `8081`
- management/metrics port: `METRICS_PORT`, default `9000`

Exposed endpoints:

- app health: `http://localhost:8081/health`
- metrics scrape: `http://localhost:9000/actuator/prometheus`
- actuator health on management port is also enabled by Spring Actuator

## Deployment Model

### Docker

`Dockerfile` uses:

1. Maven + Temurin 21 builder image
2. `mvn dependency:go-offline`
3. `mvn package -DskipTests`
4. runtime image `eclipse-temurin:21-jre`

### Docker Compose

`docker-compose.yml` starts:

- `redis`
- `wss`
- `prometheus`
- `grafana`

Important nuance:

- compose brings up one app instance only
- the project supports multi-instance behavior, but that is demonstrated by manually launching multiple app processes with different ports

### Prometheus scrape model

`metrics/prometheus/prometheus.yml` is configured to scrape:

- `host.docker.internal:9000`
- `host.docker.internal:9001`

That means the monitoring stack is set up to observe two app metrics ports if two backend instances are running on the host.

## Testing Status

Verified by `mvn test` on May 4, 2026:

- 6 tests passed
- test suite is unit-test focused

Current automated test coverage:

- `SessionRegistryTest`
  - last-local-connection cleanup behavior
  - last-local-room-member cleanup behavior

- `WsMessageTypeTest`
  - enum parsing and unknown fallback

- `RedisUriParserTest`
  - single-node parsing
  - multi-endpoint parsing with password extraction

What is not covered by automated tests today:

- real Redis integration
- multi-server end-to-end routing
- handshake rejection path
- `AppWebSocketHandler` invalid payload behavior
- room authorization edge cases
- unexpected disconnect metric behavior

## Strengths

- Solves a real distributed systems problem: routing stateful WebSocket traffic across horizontally scaled servers
- Clean separation between local socket ownership and distributed routing
- Uses Redis sets and TTLs in a practical way
- Supports multiple sessions for the same user
- Room fan-out is server-level, not per-user at Redis layer
- Adds production-style observability instead of stopping at "it works locally"
- Uses `ConcurrentWebSocketSessionDecorator` for safer sends to slow clients

## Current Limitations And Tradeoffs

### Security

- no real authentication yet
- `userId` comes from query params
- `JWT_SECRET` exists but is unused
- all origins are allowed
- no rate limiting
- no room ACLs

### Reliability

- Redis Pub/Sub is best-effort, not durable
- no message persistence
- no acks, retries, deduplication, or idempotency keys
- offline users do not receive messages later

### Presence accuracy

- user presence is TTL-backed and self-healing
- room presence is not TTL-backed
- if a server dies ungracefully, room server mappings can become stale until some later cleanup path removes them

### Scale considerations

- heartbeat scheduling is per connection, not batched
- local room fan-out is synchronous over the room's session map
- no explicit backpressure queue per client
- no load test evidence is stored in the repo

### Message model

- `payload` is generic JSON, so validation is minimal
- there is no schema per message type beyond a few field checks

## Good Interview Tradeoff Statements

### Why Redis Pub/Sub instead of Kafka?

"I wanted a simple low-latency inter-server bus for online delivery. Redis Pub/Sub was enough for a real-time demo and easy to operate locally. The tradeoff is that it is not durable, so if I needed guaranteed delivery or replay I would move to Redis Streams, Kafka, or a persisted outbox pattern."

### Why WebSocket instead of polling?

"Polling wastes requests and adds latency because the client has to keep asking for updates. WebSocket keeps a persistent connection so the server can push events immediately. That is a much better fit for chat, notifications, and live operational updates."

### Why no sticky sessions?

"A load balancer still needs to keep each individual WebSocket connection bound for its lifetime, but this architecture does not rely on reconnecting users to the same server forever. Redis presence and Pub/Sub let any server route to whichever server currently owns the recipient's socket."

### Why a TTL heartbeat?

"Graceful disconnect is not guaranteed in distributed systems. TTL-backed presence makes the system self-healing after crashes or broken connections, because stale online markers eventually expire even if cleanup code never runs."

## Questions Another AI Should Answer Carefully

Do say:

- this is a scalable WebSocket routing backend
- local socket state lives in memory
- distributed routing state lives in Redis
- direct delivery is best-effort
- room broadcast is once per active server, then local fan-out
- one user can have multiple sessions

Do not say:

- messages are durable
- chat history is stored
- authentication is implemented
- room presence also has TTL
- the app uses STOMP
- the app uses a database
- the app already guarantees exactly-once delivery

## Fast Lookup Facts

- WebSocket endpoint: `/ws`
- Required handshake query param: `userId`
- Health endpoint: `/health`
- Metrics endpoint: `/actuator/prometheus` on management port
- Default app port: `8081`
- Default metrics port: `9000`
- User presence TTL: 60 seconds
- Heartbeat refresh interval: 30 seconds
- Session send protection: `ConcurrentWebSocketSessionDecorator`
- Redis direct-message route key: `user_servers:{userId}`
- Redis room route key: `room:{roomId}`
- Redis liveness key: `user_online:{userId}:{serverId}`
- Redis Pub/Sub channel: `server:{serverId}`

## If Asked "What Would You Improve Next?"

Best answers:

1. Add real auth in the handshake using JWT and derive user identity from verified claims.
2. Add durable delivery for important events using Redis Streams or Kafka plus message IDs and acknowledgements.
3. Add integration tests with real Redis and a two-server setup.
4. Add room-presence expiry or server-heartbeat-based cleanup for stale `room:{roomId}` mappings.
5. Add better input validation, rate limiting, and per-client backpressure handling.

## Best Files To Show In An Interview

Open these first if someone asks to see the implementation:

- `src/main/java/com/easc/websocketapp/websocket/AppWebSocketHandler.java`
- `src/main/java/com/easc/websocketapp/connection/SessionRegistry.java`
- `src/main/java/com/easc/websocketapp/redis/RedisRoutingService.java`
- `src/main/java/com/easc/websocketapp/redis/RedisPresenceService.java`
- `src/main/java/com/easc/websocketapp/redis/RedisServerSubscriber.java`
- `src/main/java/com/easc/websocketapp/metrics/MetricsService.java`
- `src/test/java/com/easc/websocketapp/connection/SessionRegistryTest.java`

## One-Line Bottom Line

This project is a distributed real-time messaging backend where Spring Boot manages WebSocket connections locally, Redis coordinates routing across instances, and Prometheus/Grafana make the system observable enough to discuss like a real backend service in interviews.
