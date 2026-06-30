# Carvoeiro Manager

A Criciúma EC football-manager game combining backend and frontend components.

## Prerequisites

- Java 25
- Node 20+
- Docker

## Setup

1. Start PostgreSQL database:
   ```bash
   docker compose up -d
   ```

2. Run the backend (Gradle):
   ```bash
   ./gradlew bootRun
   ```

3. Run the frontend (Node):
   ```bash
   npm run dev
   ```

## Stopping

To stop the database:
```bash
docker compose down
```

## Deployment

See [DEPLOYMENT.md](DEPLOYMENT.md) for step-by-step production deployment instructions.
For the recommended low-cost setup, use [docs/deployment/render.md](docs/deployment/render.md).
