package com.azminur.liquidlyf

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.database
import com.google.gson.Gson

class RequestsFragment : Fragment() {

    private lateinit var requestsRecyclerView: RecyclerView

    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    private val USER_DETAILS_PREFS = "user_details_prefs"
    private val KEY_USER_OBJECT = "user_object_json"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_requests, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize Firebase
        auth = Firebase.auth
        database = Firebase.database

        requestsRecyclerView = view.findViewById(R.id.requests_recycler_view)

        requestsRecyclerView.layoutManager = LinearLayoutManager(context)

        // MOCK DATA: In a complete app, you would fetch all requests linked to the current user's UID
        // from the "BloodRequests" node and map them to RequestItem objects.
        val mockRequests = listOf(
            RequestItem("Req-001", "July 15, 2024", "Dhaka Medical College Hospital", "Completed", null),
            RequestItem("Req-002", "June 20, 2024", "Kurmitola General Hospital", "Cancelled", null),
            // This item simulates an accepted request where DonorDetails are saved in the database
            RequestItem("Req-003", "May 5, 2024", "Jashore Sodor Hospital", "Accepted", DonorDetails("Azminur Rahman", "+8801234567890", "O+")),
            RequestItem("Req-004", "May 5, 2024", "Jashore Sodor Hospital", "Pending", null)
        )

        requestsRecyclerView.adapter = RequestsAdapter(mockRequests) { requestItem ->
            // Handle item click to show donor details if available
            if (requestItem.status == "Accepted" && requestItem.donor != null) {
                showDonorDetailsDialog(requestItem.requestId, requestItem.donor)
            } else {
                Toast.makeText(requireContext(), "Status: ${requestItem.status}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // UPDATED: Dialog now uses requestId for status updates
    private fun showDonorDetailsDialog(requestId: String, donor: DonorDetails) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_donor_details, null)
        val builder = AlertDialog.Builder(context)
            .setView(dialogView)
        val alertDialog = builder.create()

        val donorNameTextView = dialogView.findViewById<TextView>(R.id.donor_name)
        val donorPhoneTextView = dialogView.findViewById<TextView>(R.id.donor_phone)
        val donorBloodGroupTextView = dialogView.findViewById<TextView>(R.id.donor_blood_group)
        val callButton = dialogView.findViewById<Button>(R.id.call_button)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancel_button)
        val completeButton = dialogView.findViewById<Button>(R.id.complete_button)

        donorNameTextView.text = "Donor Name: ${donor.name}"
        donorPhoneTextView.text = "Phone: ${donor.phone}"
        donorBloodGroupTextView.text = "Blood Group: ${donor.bloodGroup}"

        callButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${donor.phone}")
            }
            startActivity(intent)
            alertDialog.dismiss()
        }

        cancelButton.setOnClickListener {
            updateRequestStatus(requestId, "Cancelled by Recipient") // Recipient cancels
            alertDialog.dismiss()
        }

        completeButton.setOnClickListener {
            updateRequestStatus(requestId, "Completed") // Recipient marks as completed
            alertDialog.dismiss()
        }

        alertDialog.show()
    }

    // NEW: Function to update the request status in Firebase
    private fun updateRequestStatus(requestId: String?, newStatus: String) {
        if (requestId.isNullOrEmpty()) {
            Toast.makeText(context, "Cannot update request: ID not found.", Toast.LENGTH_SHORT).show()
            return
        }

        val updates = mapOf<String, Any>(
            "status" to newStatus
        )

        database.getReference("BloodRequests").child(requestId).updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(context, "Request status updated to: $newStatus", Toast.LENGTH_SHORT).show()
                // In a real app, you would manually trigger a list reload here (e.g., call a ViewModel method)
            }
            .addOnFailureListener {
                Toast.makeText(context, "Failed to update status: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * SIMULATED DONOR ACCEPTANCE LOGIC: This method demonstrates how a DONOR would save their details.
     * This logic would typically be triggered by an 'Accept' button in the Donor's Dashboard or IncomingRequests view.
     */
    fun assignDonorToRequest(requestId: String, donor: User) {
        val donorDetails = DonorDetails(donor.fullName, donor.phoneNumber, donor.bloodGroup)

        val updates = mapOf<String, Any>(
            "status" to "Accepted",
            "assignedDonorUid" to donor.uid,
            "donorDetails" to donorDetails
        )

        database.getReference("BloodRequests").child(requestId).updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(context, "Request $requestId accepted! Requester notified.", Toast.LENGTH_LONG).show()
                // The database change here would trigger the Cloud Function
                // to send the notification to the Requester.
            }
            .addOnFailureListener {
                Toast.makeText(context, "Failed to accept request: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }
}


// UPDATED DATA MODEL: Now includes requestId to uniquely identify the request in the database
data class RequestItem(val requestId: String, val date: String, val hospital: String, val status: String, val donor: DonorDetails?)
data class DonorDetails(val name: String, val phone: String, val bloodGroup: String)


class RequestsAdapter(private val requests: List<RequestItem>, private val onItemClick: (RequestItem) -> Unit) :
    RecyclerView.Adapter<RequestsAdapter.RequestViewHolder>() {
    // ... (Adapter remains the same, using the updated RequestItem model)
    class RequestViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val date: TextView = view.findViewById(R.id.request_date)
        val hospital: TextView = view.findViewById(R.id.request_hospital)
        val status: TextView = view.findViewById(R.id.request_status)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_request, parent, false)
        return RequestViewHolder(view)
    }

    override fun onBindViewHolder(holder: RequestViewHolder, position: Int) {
        val request = requests[position]
        holder.date.text = request.date
        holder.hospital.text = request.hospital
        holder.status.text = request.status

        holder.itemView.setOnClickListener {
            onItemClick(request)
        }
    }

    override fun getItemCount() = requests.size
}