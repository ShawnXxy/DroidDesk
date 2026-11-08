package com.orailnoor.droiddesk.display.placement

internal data class AcceptedDisplayHandoffEvidence(
    val displayId: Int,
    val fallbackIdentity: String,
)

internal data class AcceptedDisplayHandoffEvidenceSnapshot(
    val current: AcceptedDisplayHandoffEvidence?,
    val previous: AcceptedDisplayHandoffEvidence?,
)

internal sealed interface AcceptedDisplayHandoffAdmission {
    data class Accepted(
        val connection: DisplayConnectionIdentity,
    ) : AcceptedDisplayHandoffAdmission

    data object AwaitingEvidence : AcceptedDisplayHandoffAdmission

    data object Rejected : AcceptedDisplayHandoffAdmission
}

internal class AcceptedDisplayHandoffRegistry(
    private val identityPolicy: DisplayConnectionIdentityPolicy =
        DisplayConnectionIdentityPolicy(),
) {
    private data class PendingAdmission(
        val owner: Any,
        val displayId: Int,
        val connectionKey: String,
    )

    private var pendingAdmission: PendingAdmission? = null

    fun resolve(
        evidence: AcceptedDisplayHandoffEvidence,
    ): DisplayConnectionIdentity =
        identityPolicy.resolve(
            displayId = evidence.displayId,
            platformUniqueId = null,
            fallbackIdentity = evidence.fallbackIdentity,
        )

    fun admit(
        owner: Any,
        displayId: Int,
        connectionKey: String,
        evidence: AcceptedDisplayHandoffEvidenceSnapshot,
    ): AcceptedDisplayHandoffAdmission {
        val selectedEvidence = evidence.current
            ?: evidence.previous?.takeIf { it.displayId == displayId }
        val connection = selectedEvidence?.let(::resolve)
        if (connection == null) {
            val pending = pendingAdmission
            if (pending == null) {
                pendingAdmission = PendingAdmission(owner, displayId, connectionKey)
                return AcceptedDisplayHandoffAdmission.AwaitingEvidence
            }
            return if (
                pending.owner === owner &&
                pending.displayId == displayId &&
                pending.connectionKey == connectionKey
            ) {
                AcceptedDisplayHandoffAdmission.AwaitingEvidence
            } else {
                AcceptedDisplayHandoffAdmission.Rejected
            }
        }
        return if (
            connection.displayId == displayId &&
            connection.connectionKey == connectionKey
        ) {
            pendingAdmission = null
            AcceptedDisplayHandoffAdmission.Accepted(connection)
        } else {
            val pending = pendingAdmission
            if (
                pending?.owner === owner &&
                pending.displayId == displayId &&
                pending.connectionKey == connectionKey
            ) {
                pendingAdmission = null
            }
            AcceptedDisplayHandoffAdmission.Rejected
        }
    }

    fun confirm(
        owner: Any,
        evidence: AcceptedDisplayHandoffEvidence,
    ): AcceptedDisplayHandoffAdmission {
        val pending = pendingAdmission?.takeIf { it.owner === owner }
            ?: return AcceptedDisplayHandoffAdmission.Rejected
        pendingAdmission = null
        val connection = resolve(evidence)
        return if (
            connection.displayId == pending.displayId &&
            connection.connectionKey == pending.connectionKey
        ) {
            AcceptedDisplayHandoffAdmission.Accepted(connection)
        } else {
            AcceptedDisplayHandoffAdmission.Rejected
        }
    }

    fun unbind(owner: Any) {
        if (pendingAdmission?.owner === owner) pendingAdmission = null
    }

    fun displayAdded(displayId: Int) = identityPolicy.displayAdded(displayId)

    fun displayChanged(displayId: Int) = identityPolicy.displayChanged(displayId)

    fun displayRemoved(displayId: Int) {
        if (pendingAdmission?.displayId == displayId) pendingAdmission = null
        identityPolicy.displayRemoved(displayId)
    }
}
