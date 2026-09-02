# Production administrative access

## Normal access

- Use named administrator identities with least-privilege permissions and MFA through the selected identity provider.
- Separate admin, seller, support, database, and cluster roles; never grant application pods cluster-admin or PostgreSQL superuser access.
- Revoke sessions/refresh tokens after role changes or suspected compromise.
- Record actor, target, action, request ID, reason, and result in the audit stream without storing credentials.

## Bootstrap

The first administrator must be created by an approval-gated, one-shot bootstrap job or external identity provisioning procedure. It must accept a short-lived secret from the deployment system and refuse to run when a production admin already exists unless an explicit rotation mode is enabled. No default email/password is allowed.

## Break-glass

Emergency access requires an incident ID, two-person authorization where policy requires, a short TTL, restricted network path, automatic expiration, and an audit record. After use, rotate affected credentials and review all actions.

The repository has no production identity provider or pager integration, so these controls are a go-live procedure, not an executed test result.
