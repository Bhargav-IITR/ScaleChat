# WebSocket App

This project is a scalable WebSocket application built with Java and Spring Boot, designed for real-time communication and optimized for performance and reliability. It leverages Redis for presence and routing, and includes integrated metrics for monitoring via Prometheus and Grafana.

## Features
- Real-time WebSocket communication
- Redis-backed presence and routing
- Health monitoring endpoints
- Metrics collection (Prometheus)
- Grafana dashboards for visualization
- Docker support for easy deployment

## Project Structure
- `src/main/java/com/easc/websocketapp/` — Main application source code
  - `config/` — Application configuration classes
  - `connection/` — WebSocket session management
  - `metrics/` — Metrics service integration
  - `model/` — WebSocket message models
  - `redis/` — Redis integration and services
  - `web/` — REST controllers
  - `websocket/` — WebSocket handlers and configuration
- `src/main/resources/` — Application properties
- `metrics/prometheus/` — Prometheus configuration
- `metrics/grafana/` — Grafana dashboards and provisioning
- `docs/` — Documentation and reports
- `docker-compose.yml`, `Dockerfile` — Containerization
- `pom.xml` — Maven build configuration

## Getting Started

### Prerequisites
- Java 17+
- Maven
- Docker (optional, for containerized deployment)
- Redis server

### Build & Run
1. **Build the project:**
   ```sh
   mvn clean install
   ```
2. **Run with Maven:**
   ```sh
   mvn spring-boot:run
   ```
3. **Run with Docker Compose:**
   ```sh
   docker-compose up --build
   ```

### Configuration
- Edit `src/main/resources/application.properties` for application settings.
- Redis connection and presence settings are managed in `config/` and `redis/` packages.

### Metrics & Monitoring
- Prometheus scrapes metrics from the app (see `metrics/prometheus/prometheus.yml`).
- Grafana dashboards are provisioned in `metrics/grafana/dashboards/`.

## Testing
- Unit tests are located in `src/test/java/com/easc/websocketapp/`.
- Run tests with:
   ```sh
   mvn test
   ```

## Documentation
- See `docs/` for latency reports, optimization notes, and Redis integration details.

## License
This project is licensed under the MIT License.

## Authors
- EASC Team

---
For questions or contributions, please open an issue or submit a pull request.