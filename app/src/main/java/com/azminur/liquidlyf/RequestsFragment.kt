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
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.database
import java.text.SimpleDateFormat
import java.util.*

class RequestsFragment : Fragment() {

    private lateinit var requestsRecyclerView: RecyclerView
    private lateinit var requestsAdapter: RequestsAdapter
    private val requestsList = mutableListOf<RequestItem>()

    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private val dateFormatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_requests, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = Firebase.auth
        database = Firebase.database

        requestsRecyclerView = view.findViewById(R.id.requests_recycler_view)

        requestsRecyclerView.layoutManager = LinearLayoutManager(context)
        requestsAdapter = RequestsAdapter(requestsList) { requestItem ->
            if (requestItem.status == "Accepted" && requestItem.donor != null) {
                showDonorDetailsDialog(requestItem.requestId, requestItem.donor)
            } else {
                Toast.makeText(requireContext(), "Status: ${requestItem.status}", Toast.LENGTH_SHORT).show()
            }
        }
        requestsRecyclerView.adapter = requestsAdapter

        fetchUserRequests()
    }

    private fun fetchUserRequests() {
        val currentUid = auth.currentUser?.uid ?: return

        database.getReference("BloodRequests")
            .orderByChild("requesterUid").equalTo(currentUid)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    requestsList.clear()
                    for (requestSnapshot in snapshot.children) {
                        val request = requestSnapshot.getValue(BloodRequest::class.java)
                        if (request != null) {
                            val dateString = request.timestamp?.let { dateFormatter.format(Date(it)) } ?: "N/A"

                            val requestItem = RequestItem(
                                requestId = request.requestId ?: "",
                                date = dateString,
                                hospital = request.hospitalName ?: "Unknown Hospital",
                                status = request.status ?: "Unknown",
                                donor = request.donorDetails
                            )
                            requestsList.add(requestItem)
                        }
                    }
                    requestsList.sortByDescending { it.requestId }
                    requestsAdapter.notifyDataSetChanged()
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(context, "Failed to load requests: ${error.message}", Toast.LENGTH_LONG).show()
                }
            })
    }

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
            updateRequestStatus(requestId, "Cancelled by Recipient")
            alertDialog.dismiss()
        }

        completeButton.setOnClickListener {
            updateRequestStatus(requestId, "Completed")
            alertDialog.dismiss()
        }

        alertDialog.show()
    }

    private fun updateRequestStatus(requestId: String?, newStatus: String) {
        if (requestId.isNullOrEmpty()) {
            Toast.makeText(context, "Cannot update request: ID not found.", Toast.LENGTH_SHORT).show()
            return
        }

        if (newStatus == "Completed" || newStatus == "Cancelled by Recipient") {
            val completionUpdates = hashMapOf<String, Any?>(
                "status" to newStatus,
                "assignedDonorUid" to null,
                "donorDetails" to null
            )

            database.getReference("BloodRequests").child(requestId).updateChildren(completionUpdates as Map<String, Any>)
                .addOnSuccessListener {
                    Toast.makeText(context, "Request status updated to: $newStatus", Toast.LENGTH_LONG).show()
                }
                .addOnFailureListener {
                    Toast.makeText(context, "Failed to update status: ${it.message}", Toast.LENGTH_LONG).show()
                }
        } else {
            val updates = mapOf<String, Any>(
                "status" to newStatus
            )
            database.getReference("BloodRequests").child(requestId).updateChildren(updates)
                .addOnSuccessListener {
                    Toast.makeText(context, "Request status updated to: $newStatus", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    Toast.makeText(context, "Failed to update status: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }
}


class RequestsAdapter(private val requests: List<RequestItem>, private val onItemClick: (RequestItem) -> Unit) :
    RecyclerView.Adapter<RequestsAdapter.RequestViewHolder>() {

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

        val color = when (request.status) {
            "Completed" -> holder.itemView.context.getColor(R.color.green_status)
            "Cancelled by Recipient" -> holder.itemView.context.getColor(R.color.red_status)
            "Accepted" -> holder.itemView.context.getColor(R.color.blue_button)
            else -> holder.itemView.context.getColor(R.color.black)
        }
        holder.status.setTextColor(color)

        holder.itemView.setOnClickListener {
            onItemClick(request)
        }
    }

    override fun getItemCount() = requests.size
}