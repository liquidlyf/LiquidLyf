package io.github.liquidlyf.core.designsystem

import androidx.annotation.StringRes
import io.github.liquidlyf.R
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.model.PatientCondition
import io.github.liquidlyf.core.model.ReportReason
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.Urgency

/* String resources for the model enums. The model stays plain Kotlin; text lives in res/values. */

/** Short symbol, e.g. "AB−". */
@get:StringRes
val BloodGroup.labelRes: Int
    get() = when (this) {
        BloodGroup.A_POS -> R.string.blood_group_a_pos
        BloodGroup.A_NEG -> R.string.blood_group_a_neg
        BloodGroup.B_POS -> R.string.blood_group_b_pos
        BloodGroup.B_NEG -> R.string.blood_group_b_neg
        BloodGroup.AB_POS -> R.string.blood_group_ab_pos
        BloodGroup.AB_NEG -> R.string.blood_group_ab_neg
        BloodGroup.O_POS -> R.string.blood_group_o_pos
        BloodGroup.O_NEG -> R.string.blood_group_o_neg
    }

/** Spoken form for TalkBack, e.g. "AB negative". */
@get:StringRes
val BloodGroup.spokenLabelRes: Int
    get() = when (this) {
        BloodGroup.A_POS -> R.string.blood_group_a_pos_spoken
        BloodGroup.A_NEG -> R.string.blood_group_a_neg_spoken
        BloodGroup.B_POS -> R.string.blood_group_b_pos_spoken
        BloodGroup.B_NEG -> R.string.blood_group_b_neg_spoken
        BloodGroup.AB_POS -> R.string.blood_group_ab_pos_spoken
        BloodGroup.AB_NEG -> R.string.blood_group_ab_neg_spoken
        BloodGroup.O_POS -> R.string.blood_group_o_pos_spoken
        BloodGroup.O_NEG -> R.string.blood_group_o_neg_spoken
    }

@get:StringRes
val Urgency.labelRes: Int
    get() = when (this) {
        Urgency.CRITICAL -> R.string.urgency_critical
        Urgency.URGENT -> R.string.urgency_urgent
        Urgency.PLANNED -> R.string.urgency_planned
    }

@get:StringRes
val RequestStatus.labelRes: Int
    get() = when (this) {
        RequestStatus.OPEN -> R.string.request_status_open
        RequestStatus.FULFILLED -> R.string.request_status_fulfilled
        RequestStatus.CANCELLED -> R.string.request_status_cancelled
        RequestStatus.EXPIRED -> R.string.request_status_expired
        RequestStatus.REMOVED -> R.string.request_status_removed
    }

@get:StringRes
val ResponseStatus.labelRes: Int
    get() = when (this) {
        ResponseStatus.OFFERED -> R.string.response_status_offered
        ResponseStatus.CONFIRMED -> R.string.response_status_confirmed
        ResponseStatus.DECLINED -> R.string.response_status_declined
        ResponseStatus.WITHDRAWN -> R.string.response_status_withdrawn
        ResponseStatus.DONATED -> R.string.response_status_donated
    }

@get:StringRes
val BloodComponent.labelRes: Int
    get() = when (this) {
        BloodComponent.WHOLE_BLOOD -> R.string.component_whole_blood
        BloodComponent.RED_CELLS -> R.string.component_red_cells
        BloodComponent.PLATELETS -> R.string.component_platelets
        BloodComponent.PLASMA -> R.string.component_plasma
    }

@get:StringRes
val PatientCondition.labelRes: Int
    get() = when (this) {
        PatientCondition.SURGERY -> R.string.condition_surgery
        PatientCondition.ACCIDENT -> R.string.condition_accident
        PatientCondition.THALASSEMIA -> R.string.condition_thalassemia
        PatientCondition.DENGUE -> R.string.condition_dengue
        PatientCondition.DELIVERY -> R.string.condition_delivery
        PatientCondition.CANCER -> R.string.condition_cancer
        PatientCondition.ANEMIA -> R.string.condition_anemia
        PatientCondition.OTHER -> R.string.condition_other
    }

@get:StringRes
val ReportReason.labelRes: Int
    get() = when (this) {
        ReportReason.FAKE_REQUEST -> R.string.report_reason_fake
        ReportReason.ASKING_MONEY -> R.string.report_reason_money
        ReportReason.ABUSIVE -> R.string.report_reason_abusive
        ReportReason.SPAM -> R.string.report_reason_spam
        ReportReason.WRONG_INFO -> R.string.report_reason_wrong_info
        ReportReason.OTHER -> R.string.report_reason_other
    }

val Gender.labelRes: Int
    @StringRes get() = when (this) {
        Gender.MALE -> R.string.gender_male
        Gender.FEMALE -> R.string.gender_female
        Gender.OTHER -> R.string.gender_other
    }
