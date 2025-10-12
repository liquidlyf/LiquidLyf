package com.azminur.liquidlyf

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.database
import com.google.gson.Gson

class UpdateProfileActivity : AppCompatActivity() {
    private lateinit var backArrow: ImageView
    private lateinit var bloodGroupSpinner: Spinner
    private lateinit var saveButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var nameInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var profileNameTextView: TextView
    private lateinit var contactInfoTextView: TextView
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var currentUser: User

    private val USER_DETAILS_PREFS = "user_details_prefs"
    private val KEY_USER_OBJECT = "user_object_json"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_update_profile)
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }

        auth = Firebase.auth
        database = Firebase.database

        backArrow = findViewById(R.id.back_arrow)
        bloodGroupSpinner = findViewById(R.id.blood_group_spinner)
        saveButton = findViewById(R.id.save_button)
        progressBar = findViewById(R.id.progress_bar)
        nameInput = findViewById(R.id.name_input)
        emailInput = findViewById(R.id.email_input)
        phoneInput = findViewById(R.id.phone_input)
        profileNameTextView = findViewById(R.id.profile_name)
        contactInfoTextView = findViewById(R.id.contact_info)

        val bloodGroups = resources.getStringArray(R.array.blood_groups)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bloodGroups)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        bloodGroupSpinner.adapter = adapter

        loadUserDetails()

        backArrow.setOnClickListener {
            finish()
        }

        saveButton.setOnClickListener {
            saveProfileChanges()
        }
    }
    private fun loadUserDetails() {
        val sharedPref = getSharedPreferences(USER_DETAILS_PREFS, Context.MODE_PRIVATE)
        val userJson = sharedPref.getString(KEY_USER_OBJECT, null)

        if (userJson != null) {
            try {
                currentUser = Gson().fromJson(userJson, User::class.java)

                profileNameTextView.text = currentUser.fullName
                contactInfoTextView.text = "${currentUser.email}\n${currentUser.phoneNumber}"

                nameInput.setText(currentUser.fullName)

                emailInput.setText(currentUser.email)
                phoneInput.setText(currentUser.phoneNumber)

                val bloodGroups = resources.getStringArray(R.array.blood_groups)
                val selectionIndex = bloodGroups.indexOf(currentUser.bloodGroup)
                if (selectionIndex != -1) {
                    bloodGroupSpinner.setSelection(selectionIndex)
                }

            } catch (e: Exception) {
                Toast.makeText(this, "Error loading user data.", Toast.LENGTH_LONG).show()
                finish()
            }
        } else {
            Toast.makeText(this, "User data not found. Please log in again.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun saveProfileChanges() {
        val newName = nameInput.text.toString().trim()
        val newBloodGroup = bloodGroupSpinner.selectedItem.toString()
        val currentUid = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "User not authenticated.", Toast.LENGTH_SHORT).show()
            return
        }

        if (newName.isEmpty()) {
            Toast.makeText(this, "Name cannot be empty.", Toast.LENGTH_LONG).show()
            return
        }
        if (newBloodGroup == resources.getStringArray(R.array.blood_groups).first()) {
            Toast.makeText(this, "Please select a valid blood group.", Toast.LENGTH_LONG).show()
            return
        }

        progressBar.visibility = View.VISIBLE

        val updates = hashMapOf<String, Any>(
            "fullName" to newName,
            "bloodGroup" to newBloodGroup
        )

        database.getReference("Users").child(currentUid).updateChildren(updates)
            .addOnSuccessListener {
                currentUser.fullName = newName
                currentUser.bloodGroup = newBloodGroup

                val sharedPref = getSharedPreferences(USER_DETAILS_PREFS, Context.MODE_PRIVATE)
                val userJson = Gson().toJson(currentUser)
                sharedPref.edit().putString(KEY_USER_OBJECT, userJson).apply()

                progressBar.visibility = View.GONE
                Toast.makeText(this, "Profile updated successfully!", Toast.LENGTH_SHORT).show()

                finish()
            }
            .addOnFailureListener {
                progressBar.visibility = View.GONE
                Toast.makeText(this, "Failed to update profile: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }
}