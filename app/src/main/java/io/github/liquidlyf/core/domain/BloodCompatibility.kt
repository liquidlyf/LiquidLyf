package io.github.liquidlyf.core.domain

import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup

/**
 * Which donor groups a patient can receive from, per component (doc 07 §3).
 *
 * Must match backend/shared/blood-compatibility.json, which firestore.rules and the Worker
 * follow too; BloodCompatibilityTest checks every entry against that file.
 *
 * NEEDS MEDICAL REVIEW before launch. The app only suggests donors: the hospital always
 * cross-matches. Exact match is the default; compatible groups only with the requester's
 * (doctor-approved) opt-in.
 */
object BloodCompatibility {

    /** Donor groups [patient] can receive [component] from, in [BloodGroup.entries] order. */
    fun compatibleDonors(patient: BloodGroup, component: BloodComponent): List<BloodGroup> =
        BloodGroup.entries.filter { donor -> canReceive(patient, donor, component) }

    fun canReceive(patient: BloodGroup, donor: BloodGroup, component: BloodComponent): Boolean =
        when (component) {
            // The donor's red cells must carry no ABO antigen the patient lacks, and an
            // Rh-negative patient gets Rh-negative cells.
            BloodComponent.WHOLE_BLOOD, BloodComponent.RED_CELLS ->
                patient.abo.containsAll(donor.abo) && (!patient.isRhNegative || donor.isRhNegative)
            // Plasma is the reverse: the donor's plasma must not attack the patient's cells.
            // Rh is not considered.
            BloodComponent.PLASMA -> donor.abo.containsAll(patient.abo)
            // Platelets with "compatible OK": the plasma ABO rule, and Rh-negative patients
            // get Rh-negative donors only.
            BloodComponent.PLATELETS ->
                donor.abo.containsAll(patient.abo) && (!patient.isRhNegative || donor.isRhNegative)
        }

    /**
     * The request's `acceptedGroups`: just [patient] by default, or every compatible group when
     * [acceptCompatible]. firestore.rules accepts exactly these lists (`acceptedGroupsOk`).
     */
    fun acceptedGroups(
        patient: BloodGroup,
        component: BloodComponent,
        acceptCompatible: Boolean,
    ): List<BloodGroup> = if (acceptCompatible) compatibleDonors(patient, component) else listOf(patient)

    /** ABO antigens on the red cells: A, B, both (AB) or none (O). */
    private val BloodGroup.abo: Set<Char>
        get() = when (this) {
            BloodGroup.A_POS, BloodGroup.A_NEG -> setOf('A')
            BloodGroup.B_POS, BloodGroup.B_NEG -> setOf('B')
            BloodGroup.AB_POS, BloodGroup.AB_NEG -> setOf('A', 'B')
            BloodGroup.O_POS, BloodGroup.O_NEG -> emptySet()
        }
}
