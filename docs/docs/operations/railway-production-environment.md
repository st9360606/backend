# Railway production environment checklist

Use this checklist only in Railway's production environment. Store all sensitive
values as Railway secrets; do not commit them, put them in Android, or paste them
into tickets, chat logs, or public documentation.

## Required runtime profile and connectivity

| Variable | Required value / rule |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DB_URL` | Production MySQL JDBC URL |
| `DB_USERNAME`, `DB_PASSWORD` | Dedicated production database credentials |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD` | Production Redis credentials; use the same Redis instance for every backend replica |
| `APP_SECURITY_ALLOW_ORIGINS` | Only the real web origins, comma-separated. The API hostname is an API endpoint, not automatically a browser origin. |

## Required secrets

| Variable | Rule |
| --- | --- |
| `ACCOUNT_DELETION_PSEUDONYM_KEY` | Generate at least 32 bytes of cryptographically random material. Back it up in an approved secret vault. Do not rotate it casually because retained-record pseudonyms would no longer be stable. |
| `GOOGLE_WEB_CLIENT_ID_PROD` | Production OAuth web client ID only. |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_BASE64` | Base64-encoded Android Publisher service-account JSON. Prefer this Railway secret over committing a JSON file. |
| `GOOGLE_PLAY_PURCHASE_TOKEN_AES_KEY` | Production AES key used for encrypted purchase tokens; preserve it for the lifetime of retained audit data. |
| `GOOGLE_PLAY_RTDN_OIDC_AUDIENCE` | Exactly the audience configured on the Pub/Sub authenticated push subscription. |
| `GOOGLE_PLAY_RTDN_PUSH_SERVICE_ACCOUNT_EMAIL` | Dedicated minimal-permission Pub/Sub push identity; do not reuse the Android Publisher verifier identity. |
| `APP_ACTUATOR_USER`, `APP_ACTUATOR_PASS` | Unique strong credentials. Do not expose the actuator endpoint publicly. |
| `INTERNAL_API_TOKEN` | High-entropy token for internal-only API calls. |
| `GEMINI_API_KEY` | Production-only provider key. |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | Production SMTP credentials when email login is enabled. |

Example key generation in a trusted local shell (copy the resulting value directly
to Railway, not to a source file):

```powershell
$keyBytes = New-Object byte[] 48
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($keyBytes)
[Convert]::ToBase64String($keyBytes)
```

## Before deploying

1. Confirm the Railway service health check is `/healthz` and its deployment uses
   `SPRING_PROFILES_ACTIVE=prod`.
2. Confirm the production database has a fresh encrypted backup before Flyway runs.
3. Confirm all backend replicas use the same production Redis prefix
   (`caloshape-prod`) and Redis service.
4. Confirm the RTDN audience and push service-account email character-for-character
   match the Google Cloud Pub/Sub subscription configuration.
5. Confirm browser CORS includes only the website origins that actually call the
   API; Android native traffic does not need a CORS origin.
6. After deployment, use the production smoke-test checklist; never test account
   deletion or purchases using real customer accounts.
