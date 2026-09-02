# Data retention and privacy operations

Retention periods must be approved by legal/business owners per jurisdiction. The application should not retain data merely because storage is available.

| Data | Technical control to define | Validation status |
|---|---|---|
| Orders/payments/refunds | Legal retention, restricted access, financial audit trail | Policy/input pending |
| Audit/security logs | Immutable access, redaction, retention and deletion policy | Policy/input pending |
| Notifications | Delivery status and content minimization/expiry | Policy/input pending |
| Analytics | Aggregates, hashed identifiers, raw-event TTL/partition archival | Source minimization present; TTL unverified |
| Reviews/seller data | Moderation/audit retention and account deletion behavior | Policy/input pending |
| Application/security/access logs | PII/token redaction, short operational retention | Redaction audit pending |
| Media/object storage | Private bucket, lifecycle, versioning, deletion | Provider configuration pending |

Account deletion/export must trace identity, orders, reviews, analytics, notifications, seller interactions, audit, and media. Legally required transaction records should be anonymized or access-restricted rather than blindly deleted.
