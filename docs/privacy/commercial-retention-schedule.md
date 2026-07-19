# Commercial retention schedule

This schedule is the implementation source for `app.retention.commercial` and
must stay aligned with the public privacy policy and the Google Play Data Safety
form.

| Data | Account-deletion treatment | Retention limit | Purpose |
| --- | --- | --- | --- |
| Health, food, photo, weight, water, activity, fasting, profile, notification, auth-token and local-cache data | Delete | Immediately; backup expiry within 90 days | Account deletion |
| Google Play entitlement audit | Pseudonymize account identifier; retain product, state, dates and token hash | Five calendar years after the terminal subscription event | Financial, refund, RTDN and dispute audit |
| Encrypted raw Google Play purchase token | Retain only while needed for an active subscription and short post-terminal recovery window | Terminal subscription event plus 180 days | Re-verification, acknowledgement and refund handling |
| Referral reward ledger and terminal referral claim | Pseudonymize deleted party; remove defer request/response payload and free-text error | Five calendar years after final grant, rejection, expiry or refund outcome | Reward and dispute audit |
| Routine referral risk signal | Retain hashed identifiers only | 24 months after the signal | Abuse prevention |
| Denied referral risk signal | Retain hashed identifiers only | Five calendar years after the signal | Fraud and abuse prevention |
| Completed deletion request | Pseudonymize account identifier | Three calendar years after completion | Compliance and support audit |

## Definitions and controls

- Hashes, HMAC pseudonyms and purchase-token hashes are pseudonymized personal
  data, not anonymous data. They remain subject to access control and deletion
  deadlines.
- Only aggregate metrics with no remaining linkable identifier may be retained
  as anonymous analytics.
- A documented legal hold may pause deletion only for a specific, legitimate
  legal obligation. It must be approved, time-bounded and reviewed before any
  extension.
- The retention worker runs daily in UTC, processes bounded batches and never
  removes active subscriptions or retryable referral rewards.
