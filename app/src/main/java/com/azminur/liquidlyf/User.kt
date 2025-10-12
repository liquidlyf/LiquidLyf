package com.azminur.liquidlyf

data class User(
    var uid: String = "",
    var fullName: String = "",
    var phoneNumber: String = "",
    var email: String = "",
    var bloodGroup: String = "",
    var isAvailable: Boolean = false
)

data class DonorDetails(
    val name: String? = null,
    val phone: String? = null,
    val bloodGroup: String? = null
)

data class BloodRequest(
    var requestId: String? = null,
    var requesterUid: String? = null,
    var patientName: String? = null,
    var hospitalName: String? = null,
    var hospitalLocation: String? = null,
    var bloodGroup: String? = null,
    var quantity: String? = null,
    var status: String = "Pending",
    var assignedDonorUid: String? = null,
    var donorDetails: DonorDetails? = null,
    var timestamp: Long = System.currentTimeMillis()
)

data class RequestItem(
    val requestId: String,
    val date: String,
    val hospital: String,
    val status: String,
    val donor: DonorDetails?
)

data class IncomingRequest(val requestId: String, val bloodGroup: String, val distance: String)
data class RecentActivity(val title: String, val subtitle: String, val time: String, val iconResId: Int)