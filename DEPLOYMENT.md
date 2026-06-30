# Deployment Guide

This app has three production pieces:

- Frontend: React/Vite static app in `frontend/`.
- Backend: Spring Boot API in `backend/`.
- Database: Neon Postgres, migrated by Flyway when the backend starts.

Recommended low-cost path: deploy the backend and frontend on Render, and use Neon for the database.

Full step-by-step guide:

[docs/deployment/render.md](docs/deployment/render.md)

## Production URLs

The examples use Render's default domains:

```text
Frontend: https://carvoeiro-manager.onrender.com
Backend:  https://carvoeiro-manager-api.onrender.com
API base: https://carvoeiro-manager-api.onrender.com/api/v1
```

The frontend calls the backend through `VITE_API_URL`, which must include `/api/v1`.

## Backend Environment

Use these on the Render backend web service:

```bash
PORT=8088
DB_URL=jdbc:postgresql://YOUR_NEON_HOST/carvoeiro_manager?sslmode=require
DB_USER=carvoeiro_manager
DB_PASSWORD=your-neon-password
JWT_SECRET=replace-with-a-long-random-secret
JWT_EXP_MIN=720
CORS_ALLOWED_ORIGINS=https://carvoeiro-manager.onrender.com
```

Generate `JWT_SECRET` locally:

```bash
openssl rand -base64 48
```

Neon requires SSL/TLS, so keep `sslmode=require` in `DB_URL`.

## Frontend Environment

Use these on the Render frontend static site:

```bash
NODE_VERSION=22
VITE_API_URL=https://carvoeiro-manager-api.onrender.com/api/v1
```

`VITE_API_URL` is baked into the Vite build. If the backend URL changes, update this variable and redeploy the frontend.

## Local Verification

From the repository root:

```bash
docker compose up -d db
```

Check the backend:

```bash
cd backend
./gradlew test
./gradlew clean bootJar
```

Check the frontend:

```bash
cd ../frontend
npm ci
npm run lint
npm test -- --run
npm run build
```

## Production Checklist

- Backend health check returns `{"status":"UP"}` at `/api/v1/health`.
- `CORS_ALLOWED_ORIGINS` exactly matches the frontend origin.
- `VITE_API_URL` exactly matches the backend API base and includes `/api/v1`.
- Neon `DB_URL` uses `jdbc:postgresql://` and includes `sslmode=require`.
- Database credentials are stored only as Render environment variables.
- HTTPS is enabled for both frontend and backend.
