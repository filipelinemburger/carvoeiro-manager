# Render + Neon Deployment Guide

This guide deploys:

- Neon for PostgreSQL.
- Render Web Service for the Spring Boot API.
- Render Static Site for the React/Vite frontend.

The examples below use Render's default `onrender.com` domains first. You can add custom domains later.

```text
Backend:  https://carvoeiro-manager-api.onrender.com
Frontend: https://carvoeiro-manager.onrender.com
API base: https://carvoeiro-manager-api.onrender.com/api/v1
```

## 1. Know The Cheap Options

For a demo or small side project, start here:

```text
Neon Postgres: Free
Render static site: Free
Render backend web service: Free, but spins down after 15 minutes idle
Estimated total: $0/month
```

For a low-cost setup that feels better to use:

```text
Neon Postgres: Free while you fit within Neon free limits
Render static site: Free
Render backend web service: Starter, about $7/month
Estimated total: about $7/month
```

The Java backend may be tight on Render's 512 MB Free/Starter instances. If deploys or runtime memory fail, upgrade the backend web service to Render's Standard instance.

## 2. Push The Repo

Render deploys from GitHub, GitLab, or Bitbucket.

From your local machine:

```bash
git status
git add README.md DEPLOYMENT.md backend/Dockerfile backend/.dockerignore backend/src/main/resources/application.yml docs/deployment/render.md deploy/render-backend.env.example deploy/render-frontend.env.example
git commit -m "Add Render and Neon deployment guide"
git push
```

If you already have other uncommitted app changes, include only the files you actually want to publish.

## 3. Create The Neon Database

In Neon:

1. Create or sign in to your Neon account.
2. Create a new project.
3. Choose a region reasonably close to your Render backend region.
4. Use PostgreSQL 16 or newer.
5. Create a database named:

```text
carvoeiro-manager
```

6. Create or use a role named:

```text
carvoeiro-manager
```

7. Open the **Connection Details** widget.
8. Select the `carvoeiro-manager` database and `carvoeiro-manager` role.
9. Turn **Connection pooling** off for the first deployment.
10. Copy the direct connection string.

The Neon connection string looks like this:

```text
postgresql://USER:PASSWORD@HOST/DATABASE?sslmode=require&channel_binding=require
```

For this Spring Boot app, convert it to three Render backend environment variables:

```bash
DB_URL=jdbc:postgresql://HOST/DATABASE?sslmode=require
DB_USER=USER
DB_PASSWORD=PASSWORD
```

Example:

```bash
DB_URL=jdbc:postgresql://ep-cool-darkness-a1b2c3d4.us-east-2.aws.neon.tech/carvoeiro-manager?sslmode=require
DB_USER=carvoeiro-manager
DB_PASSWORD=the-password-from-neon
```

Keep `sslmode=require`. Neon requires SSL/TLS connections.

## 4. Create The Backend Web Service On Render

In the Render Dashboard:

1. Click **New +**.
2. Select **Web Service**.
3. Connect your repository.
4. Select the branch you deploy from, usually `main`.
5. Set **Name** to:

```text
carvoeiro-manager-api
```

6. Set **Region** close to your Neon project region.
7. Set **Root Directory** to:

```text
backend
```

8. Set **Language** or **Runtime** to:

```text
Docker
```

9. Keep **Dockerfile Path** as:

```text
Dockerfile
```

10. Choose a plan:

```text
Demo only: Free
Better low-cost usage: Starter
```

11. Set the health check path:

```text
/api/v1/health
```

## 5. Add Backend Environment Variables

On the Render backend service, open **Environment** and add:

```bash
PORT=8088
DB_URL=jdbc:postgresql://YOUR_NEON_HOST/carvoeiro-manager?sslmode=require
DB_USER=carvoeiro-manager
DB_PASSWORD=your-neon-password
JWT_SECRET=replace-with-a-long-random-secret
JWT_EXP_MIN=720
CORS_ALLOWED_ORIGINS=https://carvoeiro-manager.onrender.com
```

You can also paste the template from [deploy/render-backend.env.example](../../deploy/render-backend.env.example) into Render's **Add from .env** dialog, then replace the placeholder values.

Generate `JWT_SECRET` locally:

```bash
openssl rand -base64 48
```

Save the variables and deploy the backend.

## 6. Verify The Backend

When the backend deploy finishes, open:

```text
https://carvoeiro-manager-api.onrender.com/api/v1/health
```

Expected response:

```json
{"status":"UP"}
```

If it fails, check the Render backend logs. The most likely problems are:

- `DB_URL` is missing `jdbc:postgresql://`.
- `DB_URL` is missing `sslmode=require`.
- You copied the pooled Neon host and hit a migration/pooling issue.
- `DB_USER` or `DB_PASSWORD` does not match Neon.

## 7. Create The Frontend Static Site On Render

In the Render Dashboard:

1. Click **New +**.
2. Select **Static Site**.
3. Connect the same repository.
4. Select the branch you deploy from, usually `main`.
5. Set **Name** to:

```text
carvoeiro-manager
```

6. Set **Root Directory** to:

```text
frontend
```

7. Set **Build Command** to:

```bash
npm ci && npm run build
```

8. Set **Publish Directory** to:

```text
dist
```

9. Add environment variables:

```bash
NODE_VERSION=22
VITE_API_URL=https://carvoeiro-manager-api.onrender.com/api/v1
```

10. Create the static site.

`VITE_API_URL` is baked into the frontend at build time. If you change the backend URL later, update this variable and redeploy the frontend.

## 8. Add The React Router Rewrite

This app uses React Router, so refreshing routes such as `/dashboard` must still serve `index.html`.

On the Render Static Site:

1. Open **Redirects/Rewrites**.
2. Add a rewrite rule:

```text
Action: Rewrite
Source: /*
Destination: /index.html
```

3. Save the rule.

## 9. Update Backend CORS

After the frontend static site has its final Render URL, update the backend environment variable:

```bash
CORS_ALLOWED_ORIGINS=https://carvoeiro-manager.onrender.com
```

Then redeploy the backend.

If you use both the Render URL and a custom domain during setup, use a comma-separated value:

```bash
CORS_ALLOWED_ORIGINS=https://carvoeiro-manager.onrender.com,https://carvoeiro-manager.example.com
```

## 10. Smoke Test

Open the frontend:

```text
https://carvoeiro-manager.onrender.com
```

Check:

1. Register or log in.
2. Create or open a save game.
3. Refresh a protected frontend route.
4. Open browser dev tools and confirm API requests are not blocked by CORS.
5. Confirm the backend health URL still returns `{"status":"UP"}`.

## 11. Add Custom Domains

Optional, but recommended when you are ready:

```text
Frontend: https://carvoeiro-manager.example.com
Backend:  https://api.carvoeiro-manager.example.com
```

For each Render service:

1. Open the service.
2. Go to **Settings**.
3. Find **Custom Domains**.
4. Add the domain.
5. Follow Render's DNS instructions.
6. Click **Verify** after DNS propagates.

Render manages TLS certificates and redirects HTTP to HTTPS automatically.

After adding custom domains:

1. Update frontend `VITE_API_URL`:

```bash
VITE_API_URL=https://api.carvoeiro-manager.example.com/api/v1
```

2. Redeploy the frontend.
3. Update backend `CORS_ALLOWED_ORIGINS`:

```bash
CORS_ALLOWED_ORIGINS=https://carvoeiro-manager.example.com
```

4. Redeploy the backend.

## 12. Updating The App Later

Push to the connected branch:

```bash
git push
```

Render automatically deploys services connected to that branch.

If only frontend files changed, only the static site needs to rebuild. If only backend files changed, only the web service needs to rebuild. You can also trigger manual deploys from each service's **Manual Deploy** menu.

## 13. Neon Maintenance Notes

Neon Free is good for demos, side projects, and early testing. Watch the project usage dashboard for storage, compute hours, and egress.

Use Neon backups, restore points, or branching before risky changes. Flyway runs automatically when the backend starts, so database migrations happen during backend deploys.

If you later need connection pooling, enable Neon pooling and test carefully. For the first production deploy, the direct connection string is simpler and friendlier to Flyway.

## References

- [Render Web Services](https://render.com/docs/web-services)
- [Render Docker Deploys](https://render.com/docs/docker)
- [Render Static Sites](https://render.com/docs/static-sites)
- [Render Redirects and Rewrites](https://render.com/docs/redirects-rewrites)
- [Render Custom Domains](https://render.com/docs/custom-domains)
- [Render Environment Variables](https://render.com/docs/configure-environment-variables)
- [Render Pricing](https://render.com/pricing)
- [Neon: Connect from any application](https://neon.com/docs/connect/connect-from-any-app)
- [Neon: Connect securely](https://neon.com/docs/connect/connect-securely)
- [Neon Pricing](https://neon.com/pricing)
