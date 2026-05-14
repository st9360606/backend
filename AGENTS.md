# Backend Agent Instructions

## Project

This is the Spring Boot backend for Calai / BiteCal.

## Tech Stack

- Java 21
- Spring Boot 3
- MySQL
- Redis
- JPA
- OpenAPI
- JWT / OAuth2
- Docker / Testcontainers when applicable

## Rules

- Do not modify unrelated files.
- Do not expose internal exception details to clients.
- Do not hardcode secrets.
- Do not commit .env, local config, service account JSON, credentials, or uploaded private files.
- Every mutation API should define request DTO, response DTO, error code, and auth behavior.
- Avoid N+1 queries.
- Redis must have TTL and DB fallback.
- Billing entitlement must be verified on backend.
- Referral rewards must not bypass Google Play business rules.
- Prefer small, reviewable changes.

## Test Commands

```powershell
cd C:\Users\User\Projects\calai\backend
.\mvnw.cmd clean test