# Cross-language vectors

`kotlin.json` is written by `sdk/core` (`InteropVectorsTest`, `DEVICELINK_WRITE_VECTORS=1`) and verified by
the Swift tests; `swift.json` is written by `ios/DeviceLinkKit` (`InteropVectorTests`, same variable) and
verified by the Kotlin tests. Each file holds signed key bundles, sealed envelopes with their expected
payloads, a pairing invite with sealed join/confirm bundles, and a signed relay request.

The private keys inside are throwaway test keys generated for these files. They protect nothing.
