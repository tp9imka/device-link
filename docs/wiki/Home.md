# DeviceLink

A standalone Android tool that links two phones for text, images and files,
using nearby Wi-Fi Direct or an optional self-hosted encrypted Internet Link.
It runs on normal Android phones without Fortress, root or Google Play services.

Validated on KATIM X3M and Samsung Galaxy S24: first pairing, remembered reconnect,
two-way text and files, sharing a received file back, cancellation and receipt
persistence. Explicit text Copy/selection Copy, local chat persistence and background
clipboard writes were also verified on those phones. New internet relay and image
clipboard paths have passing automated checks and local-relay phone evidence:
two-way background image delivery with actual paste, text paste, explicit file
acceptance and sender-offline mailbox receipt. The relay passes 32 contract tests
and local/Docker startup-health checks. USB tunnels to a debug HTTP loopback relay
were used; public HTTPS/cellular deployment, sustained Doze and battery drain
remain unverified. The validation report records the exact evidence.

- [[Workflow]] - pair once, enable a session, send, receive and send back.
- [[Architecture]] - module boundaries, radio, encryption and file flow.
- [[Clipboard]] - explicit linked Copy and a private local chat sample.
- [[Internet-Link]] - opt-in HTTPS relay, key exchange, delivery and deployment.
- [[Security]] - identity, consent, limits and remaining assumptions.
- [[Battery]] - explicit availability and resource teardown.
- [[Development]] - build, checks, customization and contribution workflow.

The canonical pages live in the code repository under `docs/wiki/`. Keep those
pages in the same commit as behavior changes, then publish the wiki copy.

See the repository's [validation report](https://git.oryxlabs.internal/ivan-antsimonau/device-link/blob/main/docs/validation.md) for measured device results and remaining
validation work. Do not infer battery-life or radio-compatibility claims from
unit test results.
