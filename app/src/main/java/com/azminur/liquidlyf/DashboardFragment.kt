package com.azminur.liquidlyf

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.database.*
import com.google.gson.Gson

class DashboardFragment : Fragment() {

    private lateinit var profileImage: ImageView
    private lateinit var notification: ImageView
    private lateinit var availabilitySwitch: Switch
    private lateinit var availabilityText: TextView
    private lateinit var incomingRequestsRecyclerView: RecyclerView
    private lateinit var recentActivityRecyclerView: RecyclerView
    private lateinit var requestMaterialButton: MaterialButton

    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private var currentUser: User? = null
    private val incomingRequestsList = mutableListOf<IncomingRequest>()
    private lateinit var incomingRequestsAdapter: IncomingRequestsAdapter

    private val USER_DETAILS_PREFS = "user_details_prefs"
    private val KEY_USER_OBJECT = "user_object_json"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = Firebase.auth
        database = Firebase.database

        profileImage = view.findViewById(R.id.profile_image)
        notification = view.findViewById(R.id.notification_bell)
        availabilitySwitch = view.findViewById(R.id.availability_switch)
        availabilityText = view.findViewById(R.id.availability_text)
        incomingRequestsRecyclerView = view.findViewById(R.id.incoming_requests_recycler_view)
        recentActivityRecyclerView = view.findViewById(R.id.recent_activity_recycler_view)
        requestMaterialButton = view.findViewById(R.id.request_blood_fab)

        incomingRequestsAdapter = IncomingRequestsAdapter(requireContext(), incomingRequestsList, ::showRequestDetailsDialog)
        incomingRequestsRecyclerView.layoutManager = LinearLayoutManager(context)
        incomingRequestsRecyclerView.adapter = incomingRequestsAdapter

        recentActivityRecyclerView.layoutManager = LinearLayoutManager(context)
        recentActivityRecyclerView.adapter = RecentActivityAdapter(emptyList())

        profileImage.setOnClickListener {
            val bottomNav = requireActivity().findViewById<BottomNavigationView>(R.id.bottom_navigation_bar)
            bottomNav.selectedItemId = R.id.nav_profile
        }
        requestMaterialButton.setOnClickListener {
            val intent = Intent(requireActivity(), RequestBloodActivity::class.java)
            startActivity(intent)
        }

        loadAndSetupUserAvailability()
    }

    private fun loadAndSetupUserAvailability() {
        val userJson = requireActivity().getSharedPreferences(USER_DETAILS_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_USER_OBJECT, null)

        if (userJson != null) {
            currentUser = Gson().fromJson(userJson, User::class.java)
        }

        val userRef = auth.currentUser?.uid?.let { database.getReference("Users").child(it) }

        if (userRef != null) {
            userRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val isAvailable = snapshot.child("isAvailable").getValue(Boolean::class.java) ?: false
                    availabilitySwitch.isChecked = isAvailable
                    updateAvailabilityText(isAvailable)

                    if (isAvailable) {
                        fetchIncomingRequests(currentUser?.bloodGroup ?: "")
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("Dashboard", "Failed to read availability.", error.toException())
                }
            })

            availabilitySwitch.setOnCheckedChangeListener { _, isChecked ->
                userRef.child("isAvailable").setValue(isChecked)
                    .addOnSuccessListener {
                        updateAvailabilityText(isChecked)
                        currentUser = currentUser?.copy(isAvailable = isChecked)

                        if (isChecked) {
                            fetchIncomingRequests(currentUser?.bloodGroup ?: "")
                        } else {
                            incomingRequestsList.clear()
                            incomingRequestsAdapter.notifyDataSetChanged()
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(context, "Failed to update availability.", Toast.LENGTH_SHORT).show()
                        availabilitySwitch.isChecked = !isChecked // Revert UI on failure
                    }
            }
        }
    }

    private fun updateAvailabilityText(isAvailable: Boolean) {
        if (isAvailable) {
            availabilityText.text = getString(R.string.ready_to_donate_available)
            availabilityText.setTextColor(resources.getColor(R.color.green_status, null))
        } else {
            availabilityText.text = "Not Ready to donate (Unavailable)"
            availabilityText.setTextColor(resources.getColor(R.color.red_status, null))
        }
    }

    private fun fetchIncomingRequests(bloodGroup: String) {
        if (bloodGroup.isBlank() || auth.currentUser?.uid == null) {
            incomingRequestsList.clear()
            incomingRequestsAdapter.notifyDataSetChanged()
            return
        }

        database.getReference("BloodRequests")
            .orderByChild("bloodGroup").equalTo(bloodGroup)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    incomingRequestsList.clear()

                    for (requestSnapshot in snapshot.children) {
                        val request = requestSnapshot.getValue(BloodRequest::class.java)

                        if (request != null && request.status == "Pending" && request.requesterUid != auth.currentUser!!.uid) {
                            val incomingRequest = IncomingRequest(
                                requestId = request.requestId ?: "",
                                bloodGroup = request.bloodGroup ?: "",
                                distance = request.hospitalName ?: "Unknown Location"
                            )
                            incomingRequestsList.add(incomingRequest)
                        }
                    }
                    incomingRequestsAdapter.notifyDataSetChanged()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("Dashboard", "Failed to fetch requests.", error.toException())
                    incomingRequestsList.clear()
                    incomingRequestsAdapter.notifyDataSetChanged()
                }
            })
    }

    private fun saveRejectionStatus(requestId: String) {
        val currentUid = auth.currentUser?.uid ?: return
        if (requestId.isBlank()) return

        // Save the rejected request ID under the user's profile
        val userRef = database.getReference("Users").child(currentUid).child("rejectedRequests").child(requestId)
        userRef.setValue(true)
            .addOnSuccessListener {
                Toast.makeText(context, "Request rejected and won't reappear.", Toast.LENGTH_SHORT).show()
                // Manually remove from local list to update UI immediately
                incomingRequestsList.removeAll { it.requestId == requestId }
                incomingRequestsAdapter.notifyDataSetChanged()
            }
            .addOnFailureListener {
                Toast.makeText(context, "Failed to save rejection status.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showRequestDetailsDialog(request: IncomingRequest) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_donor_details, null)
        val builder = AlertDialog.Builder(context).setView(dialogView)
        // Buttons must be added before create(), or they never appear.
        builder.setNegativeButton("Reject") { dialog, _ ->
            saveRejectionStatus(request.requestId)
            dialog.dismiss()
        }
        val alertDialog = builder.create()

        dialogView.findViewById<Button>(R.id.cancel_button)?.visibility = View.GONE
        dialogView.findViewById<Button>(R.id.complete_button)?.visibility = View.GONE

        val dialogTitle = dialogView.findViewById<TextView>(R.id.dialog_title)
        val donorNameTextView = dialogView.findViewById<TextView>(R.id.donor_name)
        val donorPhoneTextView = dialogView.findViewById<TextView>(R.id.donor_phone)
        val donorBloodGroupTextView = dialogView.findViewById<TextView>(R.id.donor_blood_group)
        val callButton = dialogView.findViewById<Button>(R.id.call_button)

        dialogTitle.text = "Blood Request Details"
        donorNameTextView.text = "Blood Group: ${request.bloodGroup}"
        donorPhoneTextView.text = "Location: ${request.distance}"
        donorBloodGroupTextView.text = "Status: Pending"

        callButton.text = "ACCEPT"
        callButton.setBackgroundColor(context?.getColor(R.color.green_status) ?: 0)

        callButton.setOnClickListener {
            val currentDonor = currentUser
            if (currentDonor != null && request.requestId.isNotBlank()) {
                assignDonorToRequest(request.requestId, currentDonor)
            } else {
                Toast.makeText(context, "Cannot accept: User/Request details missing.", Toast.LENGTH_SHORT).show()
            }
            alertDialog.dismiss()
        }

        alertDialog.show()
    }

    private fun assignDonorToRequest(requestId: String, donor: User) {
        val donorDetails = DonorDetails(donor.fullName, donor.phoneNumber, donor.bloodGroup)

        val updates = mapOf<String, Any>(
            "status" to "Accepted",
            "assignedDonorUid" to donor.uid,
            "donorDetails" to donorDetails
        )

        database.getReference("BloodRequests").child(requestId).updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(context, "Request accepted! Requester will be notified.", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener {
                Toast.makeText(context, "Failed to accept request: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun setupRecentActivityRecyclerView() {}

}

class IncomingRequestsAdapter(
    private val context: Context,
    private val requests: List<IncomingRequest>,
    private val onItemClick: (IncomingRequest) -> Unit
) : RecyclerView.Adapter<IncomingRequestsAdapter.RequestViewHolder>() {

    class RequestViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val requestInfo: TextView = view.findViewById(R.id.request_info_text)
        val actionButton: Button = view.findViewById(R.id.action_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_incoming_request, parent, false)
        return RequestViewHolder(view)
    }

    override fun onBindViewHolder(holder: RequestViewHolder, position: Int) {
        val request = requests[position]
        holder.requestInfo.text = "Required: ${request.bloodGroup}\nLocation: ${request.distance}"

        holder.actionButton.text = "Action"
        holder.actionButton.setOnClickListener {
            onItemClick(request)
        }
    }

    override fun getItemCount() = requests.size
}

class RecentActivityAdapter(private val activities: List<RecentActivity>) :
    RecyclerView.Adapter<RecentActivityAdapter.ActivityViewHolder>() {

    class ActivityViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.activity_icon)
        val info: TextView = view.findViewById(R.id.activity_info)
        val time: TextView = view.findViewById(R.id.activity_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ActivityViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_activity, parent, false)
        return ActivityViewHolder(view)
    }

    override fun onBindViewHolder(holder: ActivityViewHolder, position: Int) {
        val activity = activities[position]
        holder.icon.setImageResource(activity.iconResId)
        holder.info.text = "${activity.title}\n${activity.subtitle}"
        holder.time.text = activity.time
    }

    override fun getItemCount() = activities.size
}