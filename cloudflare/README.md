# TWIGA Cloudflare business storage

This package adds a tenant-isolated Cloudflare Worker backed by D1, R2 and KV. Supabase Auth validates sessions; Supabase tables are the authority for workspace membership. D1 stores operational records and file metadata, R2 stores file bytes, and KV is reserved for disposable cache/state (not a system of record).

## Limits and retention
- 500 MiB (524,288,000 bytes) per business workspace, shared by all members.
- 25 MiB maximum per upload by default; change `MAX_UPLOAD_BYTES` if needed.
- Every operational record and uploaded file receives its own 90-day expiry timestamp.
- Billing, identity, membership and workspace configuration are not stored in the expiring operational tables.
- Scheduled cleanup runs daily at 02:17 UTC. Cleanup is bounded and safe to retry.

## Required Supabase migration
Apply `../backend/supabase/migrations/202611070002_twiga_storage_workspaces.sql` to the existing Supabase project. Do not expose the service-role key in the browser.

## Provision Cloudflare resources
Create:
1. D1 database named `twiga-business-data`.
2. R2 bucket named `twiga-business-files`.
3. KV namespace for `CACHE`.

Update `wrangler.toml` with the real D1 database ID and KV namespace ID. The bucket name must match exactly. From this directory run:
```sh
npx wrangler d1 migrations apply twiga-business-data --remote
npx wrangler secret put SUPABASE_ANON_KEY
npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY
npx wrangler deploy
```
Use the Supabase publishable/anon key for `SUPABASE_ANON_KEY`. Never put the service-role key in Git or frontend config.

## Routes
All routes except `/health` require a Supabase access token in the Authorization header. Workspace-specific routes also require `businessId` and verify membership server-side.

- `POST /v1/workspaces` JSON `{"name":"Business name"}` — create workspace and owner membership.
- `GET /v1/storage/usage?businessId=...`
- `POST /v1/records?businessId=...` JSON `{"type":"call_log","data":{...}}`
- `GET /v1/records?businessId=...&type=call_log&limit=50`
- `DELETE /v1/records?businessId=...&id=...`
- `POST /v1/files?businessId=...` multipart form field `file`
- `GET /v1/files?businessId=...` list files
- `GET /v1/files?businessId=...&id=...` download file
- `DELETE /v1/files?businessId=...&id=...`

## Important deployment notes
- The Worker is not deployed by committing this source. Cloudflare resource IDs, secrets and deployment must be configured in the Cloudflare account.
- The existing legacy billing/licence tables are intentionally not rewritten by this package. A later migration should connect existing paid customer records to a workspace after reviewing live data; do not auto-create duplicate billing customers.
- This first version uses the Worker as the only D1/R2 gateway. Keep D1, R2 and the service-role secret private.
- Review privacy notices and customer consent before storing call recordings, transcripts or personal data. The 90-day rule is a default retention policy; legal holds or customer-requested deletion need explicit handling.
