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

    private data class AcceptedAdmission(
        val owner: Any,
        val displayId: Int,
        val connectionKey: String,
        val connection: DisplayConnectionIdentity,
    )

    private var pendingAdmission: PendingAdmission? = null
    private val acceptedAdmissions = mutableListOf<AcceptedAdmission>()

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
            val accepted = acceptedAdmission(owner)
            if (accepted != null) {
                return if (
                    accepted.displayId == displayId &&
                    accepted.connectionKey == connectionKey
                ) {
                    AcceptedDisplayHandoffAdmission.Accepted(accepted.connection)
                } else {
                    AcceptedDisplayHandoffAdmission.Rejected
                }
            }
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
            rememberAccepted(
                owner = owner,
                connection = connection,
            )
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
            val accepted = acceptedAdmission(owner)
            if (
                accepted?.displayId == displayId &&
                accepted.connectionKey == connectionKey
            ) {
                forgetAccepted(owner)
            }
            AcceptedDisplayHandoffAdmission.Rejected
        }
    }

    fun confirm(
        owner: Any,
        evidence: AcceptedDisplayHandoffEvidence,
    ): AcceptedDisplayHandoffAdmission {
        val connection = resolve(evidence)
        val pending = pendingAdmission?.takeIf { it.owner === owner }
        if (pending == null) {
            val accepted = acceptedAdmission(owner)
                ?: return AcceptedDisplayHandoffAdmission.Rejected
            return if (
                connection.displayId == accepted.displayId &&
                connection.connectionKey == accepted.connectionKey
            ) {
                AcceptedDisplayHandoffAdmission.Accepted(accepted.connection)
            } else {
                forgetAccepted(owner)
                AcceptedDisplayHandoffAdmission.Rejected
            }
        }
        pendingAdmission = null
        return if (
            connection.displayId == pending.displayId &&
            connection.connectionKey == pending.connectionKey
        ) {
            rememberAccepted(
                owner = owner,
                connection = connection,
            )
            AcceptedDisplayHandoffAdmission.Accepted(connection)
        } else {
            forgetAccepted(owner)
            AcceptedDisplayHandoffAdmission.Rejected
        }
    }

    fun unbind(owner: Any) {
        if (pendingAdmission?.owner === owner) pendingAdmission = null
        forgetAccepted(owner)
    }

    fun displayAdded(displayId: Int) = identityPolicy.displayAdded(displayId)

    fun displayChanged(displayId: Int) = identityPolicy.displayChanged(displayId)

    fun displayRemoved(displayId: Int) {
        if (pendingAdmission?.displayId == displayId) pendingAdmission = null
        acceptedAdmissions.removeAll { it.displayId == displayId }
        identityPolicy.displayRemoved(displayId)
    }

    private fun acceptedAdmission(owner: Any): AcceptedAdmission? =
        acceptedAdmissions.firstOrNull { it.owner === owner }

    private fun rememberAccepted(
        owner: Any,
        connection: DisplayConnectionIdentity,
    ) {
        forgetAccepted(owner)
        acceptedAdmissions += AcceptedAdmission(
            owner = owner,
            displayId = connection.displayId,
            connectionKey = connection.connectionKey,
            connection = connection,
        )
    }

    private fun forgetAccepted(owner: Any) {
        acceptedAdmissions.removeAll { it.owner === owner }
    }
}
