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
   cd backend
   ./gradlew bootRun
   ```
   `bootRun` activates the `dev` profile, which supplies the throwaway database password and JWT
   secret from `backend/config/application-dev.yml`. Any other way of starting the backend must
   set `DB_PASSWORD` and `JWT_SECRET`, or it refuses to start.

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

See [DEPLOYMENT.md](DEPLOYMENT.md) for how production is set up and deployed (Vercel frontend,
AWS Lightsail backend). The older Render guide in `docs/deployment/` is superseded.
