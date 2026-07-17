# Backend deployment — AWS Lightsail ($7/mo, 1 GB)

The Spring Boot backend + Postgres 17 + Caddy (automatic HTTPS) run on a
single Lightsail Ubuntu instance via Docker Compose. The Docker image is
built by GitHub Actions (the 1 GB VM can't fit a Gradle build) and shipped
to the VM as a tarball over SSH — no container registry involved. The
Vercel frontend proxies `/api/*` to it (`frontend/vercel.json`), so the
browser never talks to this host directly.

## One-time VM setup

1. **Create the instance** (lightsail.aws.amazon.com → Create instance):
   Ubuntu 24.04 LTS, the $7/month plan (1 GB / 2 vCPU / 40 GB SSD), with
   the `carvoeiro-manager-deploy` SSH key uploaded at creation.

2. **Networking** (instance → Networking tab): add firewall rules for
   HTTP (80) and HTTPS (443). Then create a **static IP** and attach it
   (free while attached).

3. **DuckDNS**: create a subdomain at duckdns.org and point it at the
   static IP.

4. **On the VM** (`ssh -i ~/.ssh/carvoeiro_deploy ubuntu@<STATIC_IP>`):

   ```bash
   # Swapfile — safety net for JVM/Gradle spikes on a 1 GB instance
   sudo fallocate -l 2G /swapfile
   sudo chmod 600 /swapfile
   sudo mkswap /swapfile
   sudo swapon /swapfile
   echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

   # Docker
   curl -fsSL https://get.docker.com | sudo sh
   sudo usermod -aG docker ubuntu
   # log out and back in so the docker group applies
   ```

5. **App env** (on the VM):

   ```bash
   mkdir -p ~/carvoeiro-manager/deploy
   # copy .env.example there as .env and fill in DOMAIN, DB_PASSWORD, JWT_SECRET
   ```

6. **GitHub Actions secrets** (repo → Settings → Secrets → Actions):
   - `DEPLOY_HOST` — the static IP
   - `DEPLOY_SSH_KEY` — contents of the `carvoeiro_deploy` private key

7. **First deploy**: push to `main` (or run the "Deploy backend" workflow
   manually). It builds the image, ships it, and starts everything.

8. **Point the frontend at it**: the `/api/:path*` destination in
   `frontend/vercel.json` must be `https://<subdomain>.duckdns.org/api/:path*`.

## Continuous deployment

`.github/workflows/deploy-backend.yml` runs on every push to `main`
touching `backend/` or `deploy/`: builds the image on the runner (with
GitHub Actions layer caching), `docker save`s it, rsyncs `deploy/` (never
touching the VM's `.env`) and scps the tarball, then `docker load` +
`docker compose up -d` on the VM.

## Useful commands (on the VM)

```bash
cd ~/carvoeiro-manager/deploy
docker compose ps                  # status
docker compose logs -f app         # backend logs
docker compose logs -f caddy       # TLS/proxy logs
docker compose exec db psql -U carvoeiro-manager   # psql shell
docker compose restart app         # bounce just the backend
```

Postgres data lives in the `pgdata` named volume and survives rebuilds and
restarts. Back it up with:

```bash
docker compose exec db pg_dump -U carvoeiro-manager carvoeiro-manager | gzip > backup.sql.gz
```
