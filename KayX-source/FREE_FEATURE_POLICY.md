# KayX free/offline feature policy

1. Do not add subscriptions, in-app purchases, paid tiers, VIP flags, server-side entitlement checks, or remote feature locks.
2. Do not add analytics, advertising, fingerprinting, or non-essential telemetry SDKs.
3. Local browsing, file operations, archive tools, editor tools, and installed-app inspection must not require login or network access.
4. Optional network-storage protocols connect directly to endpoints configured by the user. No KayX relay/server is required.
5. Root/Shizuku/Dhizuku operations are gated only by actual OS/device authorization and must explain required permissions.
6. Any future diagnostics must be opt-in, scrub file paths and secrets, and be exportable locally before the user chooses to share them.
