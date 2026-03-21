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
