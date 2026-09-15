# Deployment

Production runs on two hosts:

- **Frontend** — the React/Vite build, served by **Vercel**.
- **Backend + database** — Spring Boot, Postgres 17 and Caddy, in Docker Compose on a single
  **AWS Lightsail** Ubuntu VM (1 GB).

The browser only ever talks to Vercel. Vercel rewrites `/api/*` to the backend, so the API is
same-origin from the browser's point of view.

```text
browser ──> https://carvoeiromanager.vercel.app           (Vercel, static build)
              │  /api/*  rewrite (frontend/vercel.json)
              ▼
            https://carvoeiromanager.duckdns.org/api/*     (Lightsail VM)
              Caddy :443  (automatic HTTPS, Let's Encrypt)
                └─> app :8088  (Spring Boot)
                      └─> db :5432  (Postgres 17, volume `pgdata`)
```

> **Superseded:** `docs/deployment/render.md` and `deploy/render-*.env.example` describe an
> earlier Render setup. It is no longer used — the Render URLs return 404. They are kept for
> history only; do not follow them.

## Production URLs

| What | URL |
|---|---|
| Frontend | https://carvoeiromanager.vercel.app |
| Frontend (alias, redirects to the above) | https://carvoeiro-manager.vercel.app |
| Backend | https://carvoeiromanager.duckdns.org |
| Health check | https://carvoeiromanager.duckdns.org/api/v1/health → `{"status":"UP"}` |

Per-deployment Vercel URLs (`carvoeiro-manager-<hash>-….vercel.app`) sit behind Vercel's login
and answer with a 302. To check what is live, use the production domain above.

## How a change reaches production

Both sides deploy **automatically on push to `main`**. Nothing is deployed by hand.

### Backend — GitHub Actions

`.github/workflows/deploy-backend.yml` runs on every push to `main` that touches
`backend/**`, `deploy/**`, or the workflow file itself. It can also be started manually
(`workflow_dispatch`).

1. Builds the Docker image on the GitHub runner — the 1 GB VM cannot fit a Gradle build.
   `backend/Dockerfile` compiles with `bootJar`, which does **not** run tests.
2. `docker save`s it to a tarball.
3. `rsync`s `deploy/` to `~/carvoeiro-manager/` on the VM, **never touching the VM's `.env`**,
   and copies the tarball.
4. On the VM: `docker load`, `docker compose up -d`, `docker image prune -f`.

Runs are serialised (`concurrency: deploy-backend`, no cancellation), so two quick pushes deploy
in order.

**A commit that touches neither `backend/` nor `deploy/` does not redeploy the backend** —
frontend-only and docs-only changes leave it alone.

**Database migrations run at backend startup.** Flyway applies them when the new container boots,
so a migration ships with the deploy that contains it. If a migration fails, the app does not come
up and the health check stops answering `UP`.

Required repository secrets (Settings → Secrets → Actions):

| Secret | Value |
|---|---|
| `DEPLOY_HOST` | The VM's static IP |
| `DEPLOY_SSH_KEY` | Private key of the `carvoeiro_deploy` key pair |

### Frontend — Vercel

The Vercel project is connected to the GitHub repository and creates a **production deployment
for every push to `main`**, docs-only commits included. `frontend/vercel.json` holds the rewrites:
`/api/*` to the backend, and every other non-asset path to `index.html` for client-side routing.

Vercel environment variable:

```bash
VITE_API_URL=/api/v1
```

It must be the **relative** path. The value is baked into the build, and the live bundle is built
with `/api/v1`, which is what routes API calls through the rewrite. If it is ever unset, the build
silently falls back to `http://localhost:8088/api/v1` (`frontend/src/api/client.ts`) and every API
call from production breaks.

## Backend configuration (on the VM)

`~/carvoeiro-manager/deploy/.env` holds the secrets and is never committed. Start from
`deploy/.env.example`.

| Variable | Purpose |
|---|---|
| `DOMAIN` | Hostname Caddy serves and gets a certificate for. Live value: `carvoeiromanager.duckdns.org` |
| `DB_PASSWORD` | Shared by the app and Postgres. Generate with `openssl rand -hex 24` |
| `JWT_SECRET` | JWT signing secret. Generate with `openssl rand -hex 48` |
| `CORS_ALLOWED_ORIGINS` | Defaults to `https://carvoeiromanager.vercel.app` |

> `deploy/.env.example` shows `carvoeiro-manager.duckdns.org` (with a hyphen). The live host is
> `carvoeiromanager.duckdns.org`, and `DOMAIN` must match the destination in `frontend/vercel.json`.

Fixed in `deploy/docker-compose.yml`, not in `.env`:

- `DB_URL=jdbc:postgresql://db:5432/carvoeiro-manager`, `DB_USER=carvoeiro-manager`
- `PORT=8088`
- `JAVA_TOOL_OPTIONS=-Xmx384m -XX:MaxMetaspaceSize=128m`, to keep the JVM inside its share of the
  1 GB instance; a 2 GB swapfile absorbs rare spikes.

`JWT_EXP_MIN` is not set, so it uses the application default of 720 minutes.

Postgres listens on the VM's loopback only (`127.0.0.1:5433`) and is never exposed to the
internet; reach it through an SSH tunnel.

## Operating the VM

One-time setup — creating the instance, firewall, static IP, DuckDNS, swapfile and Docker — is in
[deploy/README.md](deploy/README.md).

```bash
ssh -i ~/.ssh/carvoeiro_deploy ubuntu@<STATIC_IP>
cd ~/carvoeiro-manager/deploy

docker compose ps                  # status
docker compose logs -f app         # backend logs
docker compose logs -f caddy       # TLS / proxy logs
docker compose restart app         # bounce only the backend
docker compose exec db psql -U carvoeiro-manager   # psql shell
```

Postgres data lives in the `pgdata` named volume and survives rebuilds and restarts. Back it up
before any risky migration:

```bash
docker compose exec db pg_dump -U carvoeiro-manager carvoeiro-manager | gzip > backup.sql.gz
```

## Checking what is live

```bash
# Is the backend up?
curl https://carvoeiromanager.duckdns.org/api/v1/health

# Last commit that should be running on the backend
git log -1 --oneline origin/main -- backend/ deploy/

# Recent backend deploys and their outcome
gh run list --workflow deploy-backend.yml --limit 5
```

`gh run list` needs a token with the `repo` scope. A `GITHUB_TOKEN` environment variable without
it overrides a correctly-scoped `gh auth login` and makes the command fail with HTTP 404.

A 401 from an API route does **not** prove the route exists: authentication rejects every
unauthenticated request before routing, so a route that does not exist answers 401 too.

## Local development and verification

```bash
docker compose up -d db          # Postgres 16 on localhost:5435 (the backend's default)
```

Backend — requires **Java 25**. If the default `java` is older, point `JAVA_HOME` at a Java 25
JDK. Tests use Testcontainers, so Docker must be running.

```bash
cd backend
./gradlew test
./gradlew clean bootJar
```

Frontend:

```bash
cd frontend
npm ci
npm run lint
npx vitest run
npm run build      # tsc -b && vite build — this is the real type-check
```

## Production checklist

- `https://carvoeiromanager.duckdns.org/api/v1/health` returns `{"status":"UP"}`.
- The latest `Deploy backend` run for the last commit touching `backend/` or `deploy/` succeeded.
- Vercel's `VITE_API_URL` is exactly `/api/v1`.
- `DOMAIN` in the VM's `.env` matches the destination host in `frontend/vercel.json`.
- `CORS_ALLOWED_ORIGINS` includes `https://carvoeiromanager.vercel.app`.
- `.env` exists only on the VM, and the deploy secrets only in GitHub Actions.
- Back up `pgdata` before shipping a risky migration.
