# WebSocket App Interview Prep

Use this as your spoken prep for a deep SDE-1 project discussion. The goal is not to memorize every line, but to understand the design so you can reason under follow-up questions.

## 30-Second Pitch

I built a horizontally scalable WebSocket server using Spring Boot, Redis Pub/Sub, Redis-backed presence, Prometheus metrics, and Grafana dashboards. The project supports direct user-to-user messaging and room/group messaging even when users are connected to different server instances. Redis solves the cross-server routing problem: each app instance owns only its local WebSocket connections, while Redis stores which server currently hosts a user or room and carries messages between servers.

## Why This Project

Real-time systems are common in products like food delivery, ride tracking, trading, support chat, multiplayer collaboration, and live notifications. In a Zomato-like product, the same architecture can power order-status updates, delivery-partner location events, restaurant order dashboards, user support chat, and live operational alerts.

The core problem is that WebSocket connections are stateful. After horizontal scaling, user A may be connected to server 1 and user B may be connected to server 2. A normal in-memory send would fail because server 1 does not have user B's socket. This project solves that with Redis presence plus per-server Pub/Sub channels.

## Implemented Features

- WebSocket endpoint: `/ws?userId=<id>`.
- Direct messaging: `chat_message` from one connected user to another.
- Room/group chat: `room_join`, `room_leave`, and `room_message`.
- Redis user presence: `user_servers:{userId}` and `user_online:{userId}:{serverId}`.
- Redis room routing: `room:{roomId}` maps a room to active server IDs.
- Per-server Redis channels: `server:{serverId}`.
- Multi-session support: one user can have multiple local connections.
- Heartbeat-backed user presence TTL.
- Prometheus metrics and Grafana dashboards.
- Health endpoint and Docker Compose local stack.
- Redis standalone and basic cluster endpoint parsing.
- Unit tests around message type parsing, Redis URI parsing, and session registry behavior.

## High-Level Architecture

```text
Client A                Server 1                 Redis                  Server 2                Client B
   |                       |                       |                        |                       |
   |-- WebSocket connect ->|                       |                        |                       |
   |                       |-- user_servers add -->|                        |                       |
   |                       |-- user_online TTL --->|                        |                       |
   |                       |                       |<-- user B presence ----|<- WebSocket connect --|
   |-- chat_message ------>|                       |                        |                       |
   |                       |-- resolve B servers ->|                        |                       |
   |                       |-- publish server:2 -->|-- Pub/Sub message ---->|                       |
   |                       |                       |                        |-- local socket send ->|
```

Each server only writes to WebSocket sessions it owns locally. Redis is used to find the right server and deliver an inter-server event.

## Direct Message Flow

1. Client connects to `/ws?userId=user1`.
2. `UserIdHandshakeInterceptor` extracts and validates `userId`.
3. `SessionRegistry.register()` stores the local socket in concurrent maps.
4. Redis presence is written:
   - `user_servers:user1` contains this server ID.
   - `user_online:user1:{serverId}` is set with a 60-second TTL.
5. Client sends a `chat_message` with `receiverID`.
6. `AppWebSocketHandler` parses JSON into `WsMessage`, sets `senderId` from the trusted session attribute, and adds a timestamp.
7. `RedisRoutingService` resolves recipient server IDs from Redis.
8. For each active server, it publishes the message to `server:{serverId}`.
9. `RedisServerSubscriber` on the target server receives the Pub/Sub message.
10. The target server calls `SessionRegistry.sendMessageToLocalUser()` and writes to local WebSocket sessions.

Key point: the sender cannot spoof `senderId` because the backend overwrites it from the WebSocket session.

## Room Message Flow

1. Client sends `room_join` with `roomId`.
2. `SessionRegistry.joinRoom()` adds the session to local room membership.
3. If this is the first local member for that room, the server adds itself to `room:{roomId}` in Redis.
4. Client sends `room_message`.
5. Server checks the sender has joined that room locally.
6. `RedisRoutingService.sendMessageToRoom()` gets server IDs from `room:{roomId}`.
7. Message is published once per active server.
8. Each receiving server broadcasts only to its local sessions in that room.

This avoids publishing once per user. For a room with many users on the same server, Redis sees one message for that server and the app does local fan-out.

## Important Classes

- `WebsocketAppApplication`: bootstraps Spring Boot and loads `.env.local`.
- `WebSocketConfiguration`: registers `/ws` with the app handler and handshake interceptor.
- `UserIdHandshakeInterceptor`: extracts `userId` during the WebSocket handshake.
- `AppWebSocketHandler`: main message dispatcher for chat, room join/leave, room messages, latency reports, and disconnect handling.
- `SessionRegistry`: local source of truth for WebSocket sessions, user indexes, room indexes, heartbeats, and local delivery.
- `RedisPresenceService`: Redis keys, sets, TTLs, and Pub/Sub publishing.
- `RedisRoutingService`: decides which server channel to publish to.
- `RedisServerSubscriber`: consumes this server's Redis channel and delivers locally.
- `MetricsService`: Prometheus/Micrometer counters, gauges, and latency summary.
- `RedisConfiguration`: Redis standalone/cluster connection and listener setup.

## Data Structures

Local in-memory structures:

- `clientsByUser`: `userId -> connectionId -> ClientSession`.
- `clientsByRoom`: `roomId -> connectionId -> ClientSession`.
- `sessionsById`: `webSocketSessionId -> ClientSession`.
- `ClientSession`: stores connection ID, user ID, decorated WebSocket session, heartbeat future, and joined room IDs.

Redis structures:

- `user_servers:{userId}`: Redis set of server IDs where the user has active sessions.
- `user_online:{userId}:{serverId}`: TTL key proving the user is still alive on that server.
- `room:{roomId}`: Redis set of server IDs with at least one local member in the room.
- `server:{serverId}`: Pub/Sub channel consumed by exactly that server instance.

## Why This Tech Stack

Spring Boot:
- Fast setup for production-ish services.
- Built-in WebSocket support.
- Actuator and Micrometer integrate cleanly with Prometheus.

Java 21:
- Strong concurrency primitives and mature server ecosystem.
- Good fit for backend services where type safety and observability matter.

Redis:
- Very fast in-memory data store.
- Sets are ideal for `user -> serverIds` and `room -> serverIds`.
- TTL keys are a simple way to handle stale presence after crashes.
- Pub/Sub is simple and low latency for best-effort real-time fan-out.

Prometheus/Grafana:
- Useful for proving the system works under load.
- Metrics make the project more production-oriented than a basic WebSocket demo.

Docker Compose:
- Reproducible local setup for app, Redis, Prometheus, and Grafana.

## Architectural Decisions And Tradeoffs

### Per-server channels instead of one global channel

Decision: publish to `server:{serverId}` channels.

Pros:
- Avoids every server receiving every message.
- Less wasted work at higher scale.
- Direct messages go only to servers that may deliver them.

Cons:
- Requires accurate routing metadata in Redis.
- More channels exist as server count grows.

### Redis Pub/Sub instead of Kafka

Decision: use Redis Pub/Sub for inter-server real-time delivery.

Pros:
- Very simple.
- Low latency.
- Easy to run locally.
- Good enough for online notifications/chat where best-effort delivery is acceptable.

Cons:
- No durable message log.
- If a subscriber is down, the message is lost.
- No replay, offset management, or consumer group semantics.

What to say: "For this project I optimized for low-latency online delivery. If the requirement became guaranteed delivery or offline replay, I would introduce Kafka, Redis Streams, or a database-backed outbox."

### Redis TTL for user presence

Decision: store `user_online` with 60-second TTL and refresh every 30 seconds.

Pros:
- Handles server crash without needing graceful cleanup.
- Simple failure detection.
- Prevents stale user presence from living forever.

Cons:
- Presence is eventually consistent.
- A user may appear online for up to about 60 seconds after an ungraceful failure.
- Heartbeats create Redis write load.

### Local fan-out for room messages

Decision: Redis routes to active servers, then each server sends to its local room members.

Pros:
- Reduces Redis traffic for rooms.
- Avoids one Redis publish per room member.
- Keeps WebSocket ownership local.

Cons:
- A slow local client can still affect delivery if writes are not fully decoupled.
- Need careful room membership cleanup on disconnect.

### Concurrent maps for session storage

Decision: use `ConcurrentHashMap` based indexes.

Pros:
- Safe for concurrent WebSocket callbacks.
- Fast local lookup by user, room, or session.
- No database lookup for every send.

Cons:
- State is per-instance and lost on restart.
- Requires Redis to coordinate across instances.

## Strengths Of The Project

- Solves a real distributed systems problem: stateful WebSockets under horizontal scaling.
- Has both direct messaging and group/room fan-out.
- Separates local delivery from cross-server routing.
- Uses Redis sets and TTLs appropriately.
- Has observability through Prometheus and Grafana.
- Includes containerized local infrastructure.
- Has tests for core registry and parsing behavior.
- Handles multiple connections for the same user.
- Cleans local and Redis state on disconnect.
- Uses `ConcurrentWebSocketSessionDecorator` to protect WebSocket sends with buffer and time limits.

## Limitations You Should Admit

- Authentication is simplified: `userId` comes from a query parameter. In production, use JWT or session auth during handshake.
- `JWT_SECRET` exists in config but is not currently used.
- CORS/origin is open with `setAllowedOriginPatterns("*")`; production should restrict origins.
- Redis Pub/Sub is not durable. Offline clients will not receive missed messages.
- No acknowledgements, retries, idempotency keys, or message persistence.
- Room server mapping has no TTL, so a hard server crash can leave stale `room:{roomId}` entries.
- Room messages are authorized only by local membership, not a persistent ACL.
- No rate limiting or abuse protection.
- No payload size validation beyond WebSocket/session defaults.
- No integration tests with real Redis or multi-server Docker setup.
- Metrics are good for basic visibility but do not include percentiles/histograms for all paths.

Framing matters: limitations are not failures if you can explain the next design step.

## How To Improve It

Security:
- Replace query-param user ID with JWT validation in `UserIdHandshakeInterceptor`.
- Restrict allowed origins.
- Add room-level authorization.
- Add rate limiting per user/IP.

Reliability:
- Add message IDs and client acknowledgements.
- Add retry/dead-letter behavior for important notifications.
- Use Redis Streams/Kafka for durable event delivery.
- Persist chat messages if history is required.

Scalability:
- Batch heartbeat refreshes instead of one scheduled heartbeat per connection.
- Add local cache for user-to-server routing with short TTL.
- Add worker pools for local fan-out.
- Add backpressure queues per client.
- Add Redis connection pool tuning.

Room presence:
- Add TTL/heartbeat for room server mappings.
- Or recompute room server presence from server heartbeat keys.

Testing:
- Add Testcontainers Redis integration tests.
- Add two-server integration tests proving cross-server direct and room delivery.
- Add tests for invalid payloads, disconnect cleanup, and stale Redis mappings.

## Common Interview Questions

### Why did you build this?

I wanted to learn the real problem behind scalable WebSocket systems. A single-node WebSocket app is straightforward, but once we scale horizontally, the server that receives a message may not hold the recipient's socket. This project focuses on that routing problem using Redis presence and Pub/Sub.

### What real-world impact does it create?

It can support real-time user experiences: live order updates, delivery partner tracking events, support chat, restaurant dashboard updates, collaborative room updates, or operational alerts. For companies like Zomato, real-time delivery and restaurant workflows need low-latency server-to-client updates.

### Why not just use REST polling?

Polling creates unnecessary repeated requests and still gives delayed updates. WebSocket keeps one persistent connection open, so the server can push events as soon as they happen. This is better for low-latency updates and reduces request overhead at scale.

### Why Redis?

Redis gives three useful primitives for this app: sets for routing maps, TTL keys for presence, and Pub/Sub for low-latency inter-server messaging. It is simple, fast, and easy to run locally. The tradeoff is that Pub/Sub is best-effort, not durable.

### What happens if the recipient is on another server?

The sender's server looks up `user_servers:{receiverId}` in Redis, checks whether `user_online:{receiverId}:{serverId}` exists, then publishes the message to `server:{serverId}`. The target server receives it from Redis and sends it through its local WebSocket session.

### What happens if a user opens two tabs?

The registry supports multiple connections per user. Locally, `clientsByUser` maps one user ID to multiple connection IDs. Redis maps the user to the server ID. On delivery, the server sends the message to all local sessions for that user.

### What happens if the server crashes?

Local WebSocket sessions are lost. User presence eventually expires because `user_online:{userId}:{serverId}` has a TTL. However, room server mappings currently do not have TTL, so a production improvement would be adding room presence expiry or server-level heartbeats.

### Why do you need heartbeat?

Graceful disconnect is not guaranteed. A server or network can fail without running cleanup code. Heartbeat-backed TTL keys make presence self-healing: if the server stops refreshing the key, Redis expires it.

### How do you prevent a user from sending as someone else?

The server ignores any client-supplied sender identity. It sets `senderId` from the `userId` stored during the WebSocket handshake. Production would still need real authentication so users cannot choose arbitrary `userId` values.

### How do room messages avoid sending to everyone?

A room maps to only the server IDs that currently have members in that room. Redis publishes once per active server. Each server then sends only to local sessions in that room.

### What are the time complexities?

- Direct message route lookup: roughly O(Su), where Su is number of servers the user is connected to.
- Local direct delivery: O(Cu), where Cu is number of local connections for that user.
- Room routing: O(Sr), where Sr is number of servers with room members.
- Local room delivery: O(Cr), where Cr is number of local room members on that server.

### Why use `ConcurrentWebSocketSessionDecorator`?

Spring WebSocket sessions should not be written concurrently without care. The decorator adds send-time and buffer limits, reducing the risk that concurrent sends or slow clients break the session behavior.

### Is this exactly-once delivery?

No. It is best-effort real-time delivery. Redis Pub/Sub can drop messages if subscribers are unavailable, and WebSocket sends can fail. For exactly-once-like behavior, I would need message IDs, persistence, acknowledgements, retries, and idempotent client handling.

### What would you monitor?

Active connections, active rooms, direct and room messages received, messages delivered, unexpected disconnects, room joins/leaves, and latency reports. I would also add Redis command latency, Pub/Sub lag-like indicators, send failures, and per-endpoint error rates.

## Mistakes To Avoid In The Interview

- Do not say Redis Pub/Sub guarantees delivery. It does not.
- Do not say the app has production authentication. It currently uses query-param `userId`.
- Do not say all presence state has TTL. User online keys have TTL; room server sets currently do not.
- Do not say the app stores chat history. It only routes live messages.
- Do not say it is a Zomato clone. Say it is reusable real-time infrastructure relevant to Zomato-like systems.
- Do not mention Go goroutines from old notes; this implementation is Java/Spring.

## 2-Minute Architecture Answer

"This project is a scalable WebSocket messaging backend. The difficult part is horizontal scaling, because WebSocket connections are stateful and each server only knows about the sockets connected to itself. I solve that using Redis. When a user connects, the server registers local session state in memory and writes Redis presence: a set mapping user to server IDs, plus a TTL key proving the user is alive on that server. Each server also subscribes to its own Redis channel.

When user A sends a direct message to user B, server A parses the message, sets the trusted sender ID from the handshake, looks up user B's server IDs in Redis, checks active TTL keys, and publishes the payload to the target server channels. The receiving server consumes its Redis channel and sends the message to the local WebSocket sessions for user B.

For rooms, each server registers itself in `room:{roomId}` only when it has at least one local member. A room message is published once per active server, and each server performs local fan-out to its own room members. This reduces Redis traffic compared with publishing once per user.

The project also includes Prometheus metrics and Grafana dashboards so I can observe active connections, rooms, message counts, delivered messages, disconnects, room joins/leaves, and latency reports. The main tradeoff is that Redis Pub/Sub is low-latency but not durable, so for guaranteed delivery I would add persistence, message IDs, acknowledgements, and probably Kafka or Redis Streams."

## Files To Know Cold

- `src/main/java/com/easc/websocketapp/websocket/AppWebSocketHandler.java`
- `src/main/java/com/easc/websocketapp/connection/SessionRegistry.java`
- `src/main/java/com/easc/websocketapp/redis/RedisRoutingService.java`
- `src/main/java/com/easc/websocketapp/redis/RedisPresenceService.java`
- `src/main/java/com/easc/websocketapp/redis/RedisServerSubscriber.java`
- `src/main/java/com/easc/websocketapp/metrics/MetricsService.java`
- `src/test/java/com/easc/websocketapp/connection/SessionRegistryTest.java`


##Question Bank

These are the most likely SDE1 interview questions someone can ask from that project. I’m grouping them the way a real interviewer usually probes: from high-level understanding to deep implementation details.

Project Overview

Explain this project in 60 seconds.
What problem were you trying to solve?
Why did you choose this project?
What exactly did you build yourself?
What was the most challenging part of the project?
What part are you most proud of?
If I open the app, what can a user actually do?
What does “horizontally scalable” mean in your project?
Why is this better than a simple single-server chat app?
What were the main components of your system?
Architecture
11. Walk me through the end-to-end architecture.
12. What happens when a user connects to your platform?
13. What happens when one user sends a message to another user?
14. What happens when a user sends a room message?
15. How do multiple application instances coordinate?
16. Why did you need Redis in this architecture?
17. Why did you choose Pub/Sub instead of only in-memory routing?
18. What data lived in memory and what data lived in Redis?
19. Did you persist chat history? If not, why not?
20. What are the limitations of your current architecture?

WebSocket Fundamentals
21. Why did you choose WebSockets instead of REST polling?
22. How is WebSocket different from HTTP?
23. How is WebSocket different from SSE?
24. What is the WebSocket handshake?
25. How did you validate the handshake?
26. What happens if handshake validation fails?
27. How did you authenticate or identify users on connection?
28. How did you map a connected socket to a user?
29. How did you handle reconnects?
30. How did you detect stale or dead connections?

Connection and Session Management
31. What is your session registry?
32. How did you store active sessions?
33. Can one user have multiple active sessions?
34. How did you support delivery to multiple active user sessions?
35. What thread-safety issues did you face in session handling?
36. What concurrent data structures did you use and why?
37. How did you clean up sessions on disconnect?
38. What happens if the app crashes before cleanup runs?
39. How did you track room membership?
40. How did you prevent memory leaks in session management?

Scalability
41. Why can’t a single-instance WebSocket server scale well by itself?
42. What breaks when users connect across multiple instances?
43. How does Redis Pub/Sub help horizontal scaling?
44. How did you route a message to the correct server instance?
45. How did you know which instance a user was connected to?
46. What happens if a user is connected to server A and the sender is on server B?
47. What happens when you add a new application instance dynamically?
48. Did your architecture support load balancing?
49. What kind of load balancer behavior matters for WebSockets?
50. Did you need sticky sessions? Why or why not?

Redis
51. Why did you choose Redis for this project?
52. Why Pub/Sub instead of Redis Streams or Kafka?
53. What are the tradeoffs of Redis Pub/Sub?
54. Is Redis Pub/Sub durable?
55. What happens if a subscriber is down when a message is published?
56. How did you use Redis for user presence tracking?
57. How did you implement TTL refresh?
58. What key design did you use in Redis?
59. How did you avoid stale presence data?
60. What happens if Redis goes down?

Reliability and Delivery
61. What do you mean by “deliver messages reliably” here?
62. Does your system guarantee message delivery?
63. Does it guarantee ordering?
64. What delivery semantics does your current design provide: at-most-once, at-least-once, or exactly-once?
65. What happens if the recipient disconnects while a message is being routed?
66. What happens if the sender’s server crashes mid-delivery?
67. How would you make this system more reliable?
68. How would you add offline message delivery?
69. How would you handle duplicate messages?
70. Would you add message IDs or acknowledgments?

Presence, Heartbeat, and TTL
71. Why did you implement heartbeat-based TTL refresh?
72. How does heartbeat help with presence accuracy?
73. What heartbeat interval did you choose and why?
74. What TTL did you choose and why?
75. What happens if heartbeats are delayed because of network issues?
76. How did you avoid removing active users too early?
77. Could heartbeat traffic become expensive at scale?
78. How would you optimize presence tracking for millions of users?

Rooms and Messaging Logic
79. How did room membership work?
80. How did you broadcast to all users in a room?
81. How did you prevent sending messages to users not in the room?
82. Could a user join the same room from multiple devices?
83. How did you handle leaving rooms on disconnect?
84. What is the complexity of room broadcast in your current design?
85. How would large rooms affect performance?
86. How would you optimize fan-out for very large chat rooms?

Concurrency and Java/Spring
87. Why did you use Java 21 for this project?
88. Which Spring Boot WebSocket support did you use?
89. How did Spring help here?
90. What concurrency issues did you face in Java?
91. How did you ensure thread-safe access to session maps and room maps?
92. Did you use ConcurrentHashMap or any other concurrent collections?
93. How did you avoid race conditions during connect/disconnect?
94. What happens if a message is sent while a disconnect cleanup is running?
95. How would you test concurrency bugs in this system?

Design Decisions and Tradeoffs
96. Why Spring Boot and not Node.js for real-time messaging?
97. Why Redis and not Kafka?
98. Why Pub/Sub and not a database-backed queue?
99. Why not use STOMP if you used raw WebSockets?
100. What tradeoffs did you accept for an SDE1-level implementation?
101. If this were a production system, what would you redesign first?
102. What would you change if you had two more weeks?
103. What was intentionally out of scope?

Observability
104. What metrics did you expose to Prometheus?
105. What dashboards did you build in Grafana?
106. Which metrics matter most for a WebSocket platform?
107. How would you detect abnormal disconnect spikes?
108. How would you monitor room fan-out latency?
109. How would you debug a complaint that messages are delayed?
110. What health endpoints did you expose and what did they check?

Docker and Deployment
111. Why did you use Docker Compose?
112. What services were part of your Compose setup?
113. How did you run multiple app instances locally?
114. How did you validate horizontal scaling locally?
115. What environment variables or config did you need?
116. How would you deploy this beyond Docker Compose?
117. What would change in Kubernetes?
118. How would you handle Redis deployment in production?

Testing
119. What unit tests did you write?
120. How did you test session lifecycle?
121. How did you test routing across instances?
122. How did you test Redis configuration?
123. Did you write integration tests?
124. How did you test WebSocket flows end to end?
125. How did you test disconnect cleanup?
126. How did you test race conditions or concurrent delivery?
127. What important tests are still missing?

Failure Scenarios
128. What happens if Redis is temporarily unavailable?
129. What happens if one app instance dies suddenly?
130. What happens if the same user connects from two tabs and one disconnects?
131. What happens if room membership and session state become inconsistent?
132. How would you recover from stale Redis keys?
133. How would you debug message loss in a distributed setup?

Security
134. How did you validate that only allowed users can connect?
135. Did you do authentication or only identification?
136. How would you secure WebSocket endpoints in production?
137. How would you prevent abuse or spam on the socket connection?
138. How would you rate-limit users?
139. What input validation did you apply to messages?

Resume Deep-Dive / Ownership Check
140. Which exact parts did you personally implement?
141. Which part took the most time?
142. What bug did you hit that taught you the most?
143. Tell me about a design mistake you made and how you fixed it.
144. What did not work in your first design?
145. How did you know your system was actually scalable?
146. What numbers or evidence can you share?
147. If I ask you to open the code, which files would you show first?

Improvement / Extension Questions
148. How would you add persistent chat history?
149. How would you add message acknowledgments?
150. How would you support offline users?
151. How would you scale this to millions of concurrent connections?
152. How would you shard rooms or users?
153. How would you add end-to-end encryption?
154. How would you support typing indicators and read receipts?
155. How would you migrate from Pub/Sub to a more durable system?

Behavioral Questions Around the Project
156. Why did you choose these technologies?
157. How did you decide what to build first?
158. How did you break the project into milestones?
159. How did you handle being stuck?
160. If working in a team, what part would you own and why?
