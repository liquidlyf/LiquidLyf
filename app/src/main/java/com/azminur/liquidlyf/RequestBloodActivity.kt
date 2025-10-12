package com.azminur.liquidlyf

import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.database
import kotlinx.coroutines.*
import java.io.IOException
import java.util.*

class RequestBloodActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var backArrow: ImageView
    private lateinit var submitRequestButton: Button
    private lateinit var bloodGroupSpinner: Spinner
    private lateinit var progressBar: ProgressBar

    private lateinit var patientNameInput: EditText
    private lateinit var quantityInput: EditText

    // Firebase and Location
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var map: GoogleMap
    private lateinit var searchView: SearchView
    private lateinit var searchResultsRecyclerView: RecyclerView
    private lateinit var searchResultsAdapter: SearchResultsAdapter
    private val searchResultsList = mutableListOf<SearchResult>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private var selectedHospitalName: String? = null
    private var selectedHospitalLocation: LatLng? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_blood)

        auth = Firebase.auth
        database = Firebase.database

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        searchView = findViewById(R.id.search_bar)
        searchResultsRecyclerView = findViewById(R.id.search_results_recycler_view)
        patientNameInput = findViewById(R.id.patient_name_input)
        quantityInput = findViewById(R.id.quantity_input)

        searchResultsRecyclerView.layoutManager = LinearLayoutManager(this)
        searchResultsAdapter = SearchResultsAdapter(searchResultsList) { selectedResult ->
            updateMapWithMarker(selectedResult.location, selectedResult.name)
            selectedHospitalName = selectedResult.name
            selectedHospitalLocation = selectedResult.location

            searchResultsRecyclerView.visibility = View.GONE
            searchView.setQuery(selectedResult.name, false)
            searchView.clearFocus()
        }
        searchResultsRecyclerView.adapter = searchResultsAdapter

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (!query.isNullOrEmpty()) {
                    searchForHospital(query)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (!newText.isNullOrEmpty()) {
                    searchForHospital(newText)
                    searchResultsRecyclerView.visibility = View.VISIBLE
                } else {
                    searchResultsList.clear()
                    searchResultsAdapter.notifyDataSetChanged()
                    searchResultsRecyclerView.visibility = View.GONE
                }
                return true
            }
        })

        backArrow = findViewById(R.id.back_arrow)
        submitRequestButton = findViewById(R.id.submit_request_button)
        bloodGroupSpinner = findViewById(R.id.blood_group_spinner)
        progressBar = findViewById(R.id.progress_bar)

        val bloodGroups = resources.getStringArray(R.array.blood_groups)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bloodGroups)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        bloodGroupSpinner.adapter = adapter

        backArrow.setOnClickListener {
            finish()
        }

        submitRequestButton.setOnClickListener {
            submitBloodRequest()
        }
    }

    private fun submitBloodRequest() {
        val patientName = patientNameInput.text.toString().trim()
        val quantity = quantityInput.text.toString().trim()
        val bloodGroup = bloodGroupSpinner.selectedItem.toString()
        val hospitalName = selectedHospitalName
        val hospitalLat = selectedHospitalLocation?.latitude
        val hospitalLng = selectedHospitalLocation?.longitude

        val currentUid = auth.currentUser?.uid

        if (currentUid == null) {
            Toast.makeText(this, "Authentication failed. Please log in again.", Toast.LENGTH_LONG).show()
            return
        }
        if (patientName.isEmpty() || quantity.isEmpty() || hospitalName.isNullOrEmpty()) {
            Toast.makeText(this, "Please fill all fields and select a hospital.", Toast.LENGTH_LONG).show()
            return
        }
        if (bloodGroup == resources.getStringArray(R.array.blood_groups).first()) {
            Toast.makeText(this, "Please select the required blood group.", Toast.LENGTH_LONG).show()
            return
        }

        progressBar.visibility = View.VISIBLE

        val newRequest = BloodRequest(
            requesterUid = currentUid,
            patientName = patientName,
            hospitalLocation = "$hospitalLat,$hospitalLng",
            hospitalName = hospitalName,
            bloodGroup = bloodGroup,
            quantity = quantity,
            status = "Pending"
        )

        val requestsRef = database.getReference("BloodRequests")
        val newRequestRef = requestsRef.push()
        newRequest.requestId = newRequestRef.key

        newRequestRef.setValue(newRequest)
            .addOnSuccessListener {
                progressBar.visibility = View.GONE
                Toast.makeText(this, "Blood request submitted successfully! Searching for donors...", Toast.LENGTH_LONG).show()

                // IMPORTANT NOTE FOR PUSH NOTIFICATIONS:
                // To send push notifications to users with the matching blood type (Blood Group: $bloodGroup, Status: Available)
                // you MUST implement a Firebase Cloud Function (server-side code).
                // This function would be triggered by this new database entry, query the list of users with the same blood group,
                // and send a Firebase Cloud Messaging (FCM) notification to their devices.

                finish()
            }
            .addOnFailureListener {
                progressBar.visibility = View.GONE
                Toast.makeText(this, "Failed to submit request: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }


    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        val defaultLocation = LatLng(23.8103, 90.4125)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 10f))
    }

    private fun searchForHospital(query: String) = coroutineScope.launch {
        map.clear()
        searchResultsList.clear()

        withContext(Dispatchers.IO) {
            val geocoder = Geocoder(this@RequestBloodActivity, Locale.getDefault())
            try {
                val search = if(query.length > 3) "$query, Bangladesh" else "hospitals in $query, Bangladesh"
                val addresses: List<Address>? = geocoder.getFromLocationName(search, 10)

                withContext(Dispatchers.Main) {
                    if (!addresses.isNullOrEmpty()) {
                        addresses.forEach { address ->
                            val name = address.featureName ?: address.getAddressLine(0) ?: "Unknown Location"
                            val latLng = LatLng(address.latitude, address.longitude)
                            val result = SearchResult(name, latLng)
                            searchResultsList.add(result)
                        }
                    } else {
                        Toast.makeText(this@RequestBloodActivity, "No results found for '$query'", Toast.LENGTH_SHORT).show()
                    }
                    searchResultsAdapter.notifyDataSetChanged()
                }
            } catch (e: IOException) {
                Log.e("RequestBloodActivity", "Geocoder failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@RequestBloodActivity, "Network error, please try again", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateMapWithMarker(location: LatLng, title: String) {
        map.clear()
        map.addMarker(MarkerOptions().position(location).title(title))
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))
    }
}

data class SearchResult(val name: String, val location: LatLng)

class SearchResultsAdapter(
    private val results: List<SearchResult>,
    private val onResultClick: (SearchResult) -> Unit
) : RecyclerView.Adapter<SearchResultsAdapter.ResultViewHolder>() {

    class ResultViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val resultText: TextView = view.findViewById(R.id.result_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return ResultViewHolder(view)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        val result = results[position]
        holder.resultText.text = result.name
        holder.itemView.setOnClickListener { onResultClick(result) }
    }

    override fun getItemCount() = results.size
}