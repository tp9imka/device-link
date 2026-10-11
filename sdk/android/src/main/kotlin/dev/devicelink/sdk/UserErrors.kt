package dev.devicelink.sdk

import dev.devicelink.sdk.core.PairingException
import dev.devicelink.sdk.core.SendOutcome
import java.io.IOException

/** Thrown by `send*` when content is over the size limit; nothing was sent. */
class ContentTooLargeException(val limitBytes: Int) :
    IllegalArgumentException(if (limitBytes >= 1024 * 1024) "Content is larger than ${limitBytes / (1024 * 1024)} MB"
        else "Content is larger than ${limitBytes / 1024} KB")

/** What the user is told after a send; `null` from [of] means every target accepted it. */
internal enum class SendProblem(val message: Int) {
    NO_DEVICES(R.string.dl_send_no_devices),
    NOTHING(R.string.dl_send_nothing),
    TOO_LARGE(R.string.dl_send_too_large),
    UNREADABLE(R.string.dl_send_unreadable),
    MAILBOX_FULL(R.string.dl_send_mailbox_full),
    STORAGE_FULL(R.string.dl_send_storage_full),
    RATE_LIMITED(R.string.dl_send_rate_limited),
    NOT_AUTHORIZED(R.string.dl_send_not_authorized),
    REJECTED(R.string.dl_send_rejected),
    UNREACHABLE(R.string.dl_error_network),
    FAILED(R.string.dl_send_failed);

    companion object {
        /** Relay error codes (`server/relay`) a user can act on. Anything else is [FAILED]. */
        private val CODES = mapOf(
            "mailbox_full" to MAILBOX_FULL,
            "storage_full" to STORAGE_FULL, "registration_capacity" to STORAGE_FULL,
            "rate_limit" to RATE_LIMITED, "rate_limited" to RATE_LIMITED,
            "recipient_not_authorized" to NOT_AUTHORIZED,
            "device_blocked" to REJECTED, "enrollment_required" to REJECTED, "invalid_authentication" to REJECTED,
            "registration_required" to REJECTED,
            "body_too_large" to TOO_LARGE,
            // No answer, or the upload was too slow for the relay: already retried by the engine.
            SendOutcome.NETWORK to UNREACHABLE, SendOutcome.TIMEOUT to UNREACHABLE, "body_timeout" to UNREACHABLE,
        )

        /** [SendOutcome.error] is a stable code (relay error, `network`, `timeout`, `send_failed`). */
        fun of(result: Result<List<SendOutcome>>, hasPeers: Boolean): SendProblem? {
            if (!hasPeers) return NO_DEVICES
            val outcomes = result.getOrElse { failure ->
                return when (failure) {
                    is ContentTooLargeException -> TOO_LARGE
                    is SecurityException, is IOException -> UNREADABLE
                    else -> FAILED
                }
            }
            if (outcomes.isEmpty()) return NOTHING
            val error = outcomes.firstOrNull { !it.accepted }?.let { it.error ?: "" } ?: return null
            return CODES[error] ?: FAILED
        }
    }
}

/** Pairing failures that get SDK wording instead of the engine's English message. */
internal enum class PairingProblem(val message: Int) {
    CODE_USED(R.string.dl_pair_code_used);

    companion object {
        // The engine reports these only as PairingException text; 409 pairing_used is a second
        // joiner during the join/confirm window, which the user experiences as "already used".
        private val USED = listOf("(pairing_used)", "expired or already used", "Pairing code expired", "Pairing expired")

        fun of(failure: Throwable): PairingProblem? {
            val message = (failure as? PairingException)?.message ?: return null
            return if (USED.any { message.contains(it) }) CODE_USED else null
        }
    }
}
