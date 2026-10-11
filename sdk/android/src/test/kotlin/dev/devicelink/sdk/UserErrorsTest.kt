package dev.devicelink.sdk

import dev.devicelink.sdk.core.PairingException
import dev.devicelink.sdk.core.SendOutcome
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserErrorsTest {
    private fun ok(peer: String = "a") = SendOutcome(peer, "id-$peer")
    private fun failed(error: String, peer: String = "a") = SendOutcome(peer, null, error)
    private fun problem(result: Result<List<SendOutcome>>) = SendProblem.of(result, hasPeers = true)

    @Test fun everyPeerAcceptedIsNoProblem() = assertNull(problem(Result.success(listOf(ok("a"), ok("b")))))

    @Test fun noLinkedDeviceWinsOverEverythingElse() =
        assertEquals(SendProblem.NO_DEVICES, SendProblem.of(Result.failure(ContentTooLargeException(1)), hasPeers = false))

    @Test fun oversizedFileIsTooLargeNotNothingToSend() =
        assertEquals(SendProblem.TOO_LARGE, problem(Result.failure(ContentTooLargeException(1))))

    @Test fun unreadableUriIsUnreadable() {
        assertEquals(SendProblem.UNREADABLE, problem(Result.failure(FileNotFoundException())))
        assertEquals(SendProblem.UNREADABLE, problem(Result.failure(SecurityException("no grant"))))
    }

    @Test fun emptySelectionIsNothingToSend() = assertEquals(SendProblem.NOTHING, problem(Result.success(emptyList())))

    @Test fun actionableRelayCodesAreMapped() {
        assertEquals(SendProblem.MAILBOX_FULL, problem(Result.success(listOf(failed("mailbox_full")))))
        assertEquals(SendProblem.STORAGE_FULL, problem(Result.success(listOf(failed("storage_full")))))
        assertEquals(SendProblem.RATE_LIMITED, problem(Result.success(listOf(failed("rate_limit")))))
        assertEquals(SendProblem.NOT_AUTHORIZED, problem(Result.success(listOf(failed("recipient_not_authorized")))))
        assertEquals(SendProblem.REJECTED, problem(Result.success(listOf(failed("device_blocked")))))
        assertEquals(SendProblem.TOO_LARGE, problem(Result.success(listOf(failed("body_too_large")))))
    }

    @Test fun noAnswerFromTheRelayIsUnreachable() {
        assertEquals(SendProblem.UNREACHABLE, problem(Result.success(listOf(failed(SendOutcome.NETWORK)))))
        assertEquals(SendProblem.UNREACHABLE, problem(Result.success(listOf(failed(SendOutcome.TIMEOUT)))))
        assertEquals(SendProblem.UNREACHABLE, problem(Result.success(listOf(failed("body_timeout")))))
    }

    @Test fun localFailureIsNotBlamedOnTheNetwork() =
        assertEquals(SendProblem.FAILED, problem(Result.success(listOf(failed(SendOutcome.SEND_FAILED)))))

    @Test fun unregisteredSenderIsRejected() =
        assertEquals(SendProblem.REJECTED, problem(Result.success(listOf(failed("registration_required")))))

    @Test fun partialFailureReportsTheFailingPeer() =
        assertEquals(SendProblem.MAILBOX_FULL, problem(Result.success(listOf(ok("a"), failed("mailbox_full", "b")))))

    @Test fun unknownFailureStaysGeneric() =
        assertEquals(SendProblem.FAILED, problem(Result.success(listOf(failed("message_conflict")))))

    @Test fun usedAndExpiredPairingCodesGetTheFriendlyText() {
        assertEquals(PairingProblem.CODE_USED, PairingProblem.of(PairingException("Relay refused pairing (pairing_used)")))
        assertEquals(PairingProblem.CODE_USED, PairingProblem.of(PairingException("Pairing code expired or already used")))
        assertNull(PairingProblem.of(PairingException("Scan the code on the other device")))
    }
}
