# DeviceLink

A standalone Android tool that links two nearby phones for text, images and files.
It runs on normal Android phones without Fortress, root or Google Play services.

- [[Workflow]] - pair once, enable a session, send, receive and send back.
- [[Architecture]] - module boundaries, radio, encryption and file flow.
- [[Security]] - identity, consent, limits and remaining assumptions.
- [[Battery]] - explicit availability and resource teardown.
- [[Development]] - build, checks, customization and contribution workflow.

The canonical pages live in the code repository under `docs/wiki/`. Keep those
pages in the same commit as behavior changes, then publish the wiki copy.

See the repository's `docs/validation.md` for measured device results and remaining
validation work. Do not infer battery-life or radio-compatibility claims from
unit test results.
