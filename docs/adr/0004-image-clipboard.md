# 0004 - Image clipboard integration

Status: accepted and implemented as an extension of explicit linked Copy.

## Decision

Represent clipboard images as real, validated bytes in private app storage and
publish a scoped FileProvider content URI with an explicit image MIME type. Do
not coerce an image URI into pasted text. The local chat sample displays image
messages, supports explicit image Copy and image Paste with preview, and persists
its local history. Sending a local chat message does not transmit it.

Allow PNG, JPEG, WebP and GIF. Imports bound byte size, inspect actual decoder
MIME/dimensions and perform a sampled decode. The image dimension product may not
exceed 40 million pixels. Nearby clipboard images are at most 16 MiB; the relay's
complete-payload cap limits internet images to 8 MiB. General file sharing retains
its separate consent and size rules. A receiving app must support image clipboard
content to paste it; Open/Save/Share remains the general file fallback.

Nearby `ClipImageOffer` is distinct from an ordinary file offer. The authenticated
active peer auto-accepts a bounded clipboard-image transfer, receives and validates
its bytes, durably stores the file, then emits a one-shot image clipboard event.
The application imports stable private image storage, rechecks session/copy
ordering immediately before the synchronous clipboard write, and reports the
actual result through `ClipResult`. Ordinary file completion cannot claim that an
image was copied. Internet images use encrypted `CLIP_IMAGE` payloads and the
receiver's separate, default-off automatic clipboard setting.

## Ordering and resource ownership

A text copy can arrive while an older image is transferring or being imported.
The latest accepted nearby clipboard action determines which event may write;
an older completed image remains a received file but returns not-copied. The app
also tracks its own explicit clipboard actions so a delayed image import cannot
overwrite a newer local DeviceLink copy. Internet delivery uses per-sender
sequence state and the same immediate-before-write application guard.

Stop, cancellation, expiry and a replaced session invalidate pending events. The
image validation stage owns its temporary file even after the receiving stream
closes, and cancellation cleanup runs before abandoning that stage. Persisted
completed files are distinct from temporary storage and are not deleted by stage
cleanup. Image URI grants expose only files covered by the app's FileProvider.

## Limits and verification

Ordinary Android apps cannot observe another app's arbitrary background clipboard
changes, so this does not detect intervening copies made in unrelated applications.
The integration tracks actions that DeviceLink itself performs. No clipboard
listener, polling, privileged permission or Google service is introduced.

Before importing new image bytes, prune unreferenced private image/staging files
older than 24 hours. Protect current chat references, the latest image written by
DeviceLink to its own clipboard, and the sample fixture; recent files protect
in-flight imports/reuse. This policy consults app-owned metadata, not the system
clipboard. New imports are refused once stored files reach 512 MiB after cleanup;
the check precedes copying, so it is an admission threshold rather than an exact
transactional disk cap. Protected/recent files can fill it.
Cleanup runs during import rather than as permanent background work. Removing a
chat entry does not immediately invalidate a recently granted image URI.

Focused wire tests cover image offers and bounds. Transfer tests cover newer-copy
supersession, stop invalidation and staged-file cancellation. Existing physical
text clipboard checks remain valid for that version. New two-way image paste and
background delivery passed through a local debug relay, including a separate
ordinary-permission image-URI consumer on Samsung. See `docs/validation.md` for the
USB tunnel boundary and remaining public TLS, Doze and battery checks.
