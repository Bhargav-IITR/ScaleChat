# WebSocket App

Scalable Spring Boot websocket server with Redis-backed routing, Prometheus metrics, Grafana dashboards, direct messaging, and room/group chat across multiple server instances.

## Features
- 1:1 websocket messaging across servers through Redis pub/sub
- Room/group chat with Redis room routing: `room:<roomId> -> set of active servers`
- Prometheus metrics endpoint with self-hosted Grafana dashboards
- Health endpoint and Docker-based local observability stack
- Redis-backed user presence and room presence tracking

## Architecture
- Clients connect to `/ws?userId=<id>`
- User presence is tracked in Redis with heartbeat-backed keys
- Direct messages resolve recipient servers from Redis and publish once per target server
- Room messages resolve room servers from Redis and broadcast once per active server
- Each server fans inbound Redis messages out to the local websocket sessions it owns

## Message Types
- Direct message:
  See `docs/message.json`
- Room join:
  ```json
  { "type": "room_join", "roomId": "general" }
  ```
- Room leave:
  ```json
  { "type": "room_leave", "roomId": "general" }
  ```
- Room message:
  See `docs/room-message.json`
- Latency report:
  See `docs/latency-report.json`

## Local Run

### Prerequisites
- Java 21
- Maven
- Docker Desktop

### App Only
1. Start Redis:
   ```sh
   docker run -d --name ws-redis -p 6379:6379 redis:7-alpine
   ```
2. Copy env file:
   ```sh
   cp .env.example .env.local
   ```
3. Run tests:
   ```sh
   mvn test
   ```
4. Start the app:
   ```sh
   mvn spring-boot:run
   ```

The websocket server listens on `http://localhost:8081` and Prometheus metrics are exposed on `http://localhost:9000/actuator/prometheus`.

### Full Monitoring Stack
Run the whole local stack:

```sh
docker compose up --build
```

This starts:
- app on `http://localhost:8081`
- Prometheus on `http://localhost:9090`
- Grafana on `http://localhost:3000`

Grafana default credentials:
- username: `admin`
- password: `admin`

## Suggested README Screenshot
Open the aggregated dashboard in Grafana after traffic is flowing and capture a screenshot for your README. The ready-to-use dashboards live under `metrics/grafana/dashboards/`.

## Prometheus Metrics
The app exports metrics such as:
- `wss_active_connections`
- `wss_active_rooms`
- `wss_messages_total`
- `wss_direct_messages_total`
- `wss_room_messages_total`
- `wss_messages_delivered_total`
- `wss_unexpected_disconnects_total`
- `wss_room_joins_total`
- `wss_room_leaves_total`
- `wss_latency_ms_sum` and `wss_latency_ms_count`

## Testing The Core Flow
1. Connect two users to `/ws?userId=user1` and `/ws?userId=user2`
2. Send the JSON from `docs/message.json`
3. Join both users to the same room with `room_join`
4. Send the JSON from `docs/room-message.json`
5. Watch deliveries in the browser console and metrics in Grafana

## Project Structure
- `src/main/java/com/easc/websocketapp/` core application code
- `src/test/java/com/easc/websocketapp/` unit tests
- `metrics/prometheus/` Prometheus scrape config
- `metrics/grafana/` provisioned dashboards and datasources
- `docs/` sample payloads and notes

# WebSocket Demo — Local Setup Guide

A step-by-step guide to running a two-server WebSocket demo with Redis pub/sub for cross-server messaging.

---

## Prerequisites

- Docker Desktop (running)
- Java + Maven installed
- Project cloned at `D:\websocket\websocket-app-main`

---

## 1. Start Redis

```bash
docker start ws-redis
```

> **First time only** — if `ws-redis` doesn't exist yet:
> ```bash
> docker run -d --name ws-redis -p 6379:6379 redis:7-alpine
> ```

---

## 2. Start Server 1

Open a **new PowerShell window** and run:

```powershell
cd D:\websocket\websocket-app-main

$env:WSS_PORT="8081"
$env:METRICS_PORT="9000"
$env:REDIS_URI="redis://localhost:6379"

mvn spring-boot:run
```

---

## 3. Start Server 2

Open **another PowerShell window** and run:

```powershell
cd D:\websocket\websocket-app-main

$env:WSS_PORT="8083"
$env:METRICS_PORT="9001"
$env:REDIS_URI="redis://localhost:6379"

mvn spring-boot:run
```

---

## 4. Open Browser Tabs

| Tab | URL |
|-----|-----|
| **Tab A** | http://localhost:8081/health |
| **Tab B** | http://localhost:8082/health |

Open **DevTools Console** (`F12`) in each tab.

---

## 5. Connect Users via WebSocket

### Tab A Console — Connect `user1` to Server 1

```js
window.ws1 = new WebSocket("ws://localhost:8081/ws?userId=user1");
ws1.onopen    = () => console.log("user1 connected to server1");
ws1.onmessage = (e) => console.log("user1 received:", JSON.parse(e.data));
ws1.onclose   = (e) => console.log("user1 closed:", e.code, e.reason);
```

### Tab B Console — Connect `user2` to Server 2

```js
window.ws2 = new WebSocket("ws://localhost:8082/ws?userId=user2");
ws2.onopen    = () => console.log("user2 connected to server2");
ws2.onmessage = (e) => console.log("user2 received:", JSON.parse(e.data));
ws2.onclose   = (e) => console.log("user2 closed:", e.code, e.reason);
```

---

## 6. Direct Message Demo

### Send from `user1` → `user2` (Tab A Console)

```js
ws1.send(JSON.stringify({
  type: "chat_message",
  receiverID: "user2",
  payload: "hello from user1 via server1"
}));
```

**Expected behaviour:**
- Server 1 logs: received a `chat_message` from `user1`
- Server 2 logs: received a Redis pub/sub message on its own server channel
- **Tab B** prints the delivered message with `senderId: "user1"` and a timestamp

### Send the reverse — `user2` → `user1` (Tab B Console)

```js
ws2.send(JSON.stringify({
  type: "chat_message",
  receiverID: "user1",
  payload: "reply from user2 via server2"
}));
```

---

## 7. Room Message Demo

### Join the same room from both tabs

```js
// Tab A
ws1.send(JSON.stringify({ type: "room_join", roomId: "general" }));

// Tab B
ws2.send(JSON.stringify({ type: "room_join", roomId: "general" }));
```

### Send a room message from `user1` (Tab A Console)

```js
ws1.send(JSON.stringify({
  type: "room_message",
  roomId: "general",
  payload: "hello room from user1"
}));
```

**Expected behaviour:**
- **Both Tab A and Tab B** receive the room message
- Each server delivers only to its own local WebSocket clients
- Redis fans the message out to all active servers subscribed to `general`

### Optional — Leave the room (Tab B Console)

```js
ws2.send(JSON.stringify({ type: "room_leave", roomId: "general" }));
```

---

## 8. Redis Proof (Optional — Great for Interviews)

Run these in a separate terminal to visually confirm shared presence state in Redis:

```bash
# See which server(s) user1 is connected to
docker exec ws-redis redis-cli SMEMBERS user_servers:user1

# See which server(s) user2 is connected to
docker exec ws-redis redis-cli SMEMBERS user_servers:user2

# See which servers have clients in the "general" room
docker exec ws-redis redis-cli SMEMBERS room:general
```

> After both users join `room:general` from different servers, `SMEMBERS room:general` should return **two distinct server IDs** - proving cross-server coordination via Redis.

---

## 9. WebSocket Load Test

The platform was load-tested locally with `k6` using `websocket-load-test.js`. Each virtual user opened a pair of WebSocket connections split across two Spring Boot replicas, then sent one cross-server direct message per second through Redis Pub/Sub for approximately 100 seconds.

Run the validated scenario from PowerShell after starting Redis and both application replicas:

```powershell
$env:PAIRS="100"
k6 run .\websocket-load-test.js
```

### Validated result

Test environment: two local Spring Boot replicas, one Redis instance, and the k6 load generator running on the same development machine.

| Metric | Result |
| --- | ---: |
| Client pairs / k6 VUs | 100 |
| Concurrent WebSocket connections | 200 |
| Test traffic duration | 100 seconds |
| Messages sent | 9,900 |
| Messages received | 9,900 |
| Successful delivery | 100% |
| Effective message throughput | 93.91 messages/second |
| Average end-to-end latency | 58.12 ms |
| Median end-to-end latency | 20 ms |
| p90 end-to-end latency | 86 ms |
| p95 end-to-end latency | 139.04 ms |
| Maximum observed latency | 1.86 seconds |
| Application errors | 0 |
| Completed / interrupted VUs | 100 / 0 |

The test passed both configured thresholds: zero application errors and end-to-end p95 latency below 200 ms.

### Stress boundary observed

A separate 250-pair run attempted 500 concurrent WebSocket connections. It did not meet the acceptance criteria: 266 errors occurred, only 136 of 250 VUs completed, and p95 latency rose to 637.05 ms. This result is recorded as an observed local stress boundary, not as supported capacity. Results may vary on production-grade or isolated infrastructure because the applications, Redis, and load generator shared one machine.
