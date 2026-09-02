# Security operations

Monitor and investigate:

- suspicious login/OTP activity and authentication failure spikes;
- admin/seller permission changes, break-glass access, and unusual bulk actions;
- rate-limit/WAF blocks and bot behavior on login, OTP, search, coupon, checkout, review, and onboarding routes;
- webhook signature failures/replay attempts;
- secret access/rotation failures, certificate expiry, image policy failures, and dependency findings.

Response controls: revoke credentials/tokens, disable a provider or feature flag only through an approved emergency action, preserve redacted evidence, isolate affected principals, and reconcile durable business state. WAF/bot controls complement application authorization and validation; they do not replace them.
