package com.azminur.liquidlyf

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import com.google.gson.Gson

class ProfileFragment : Fragment() {
    private lateinit var updateProfileItem: ConstraintLayout
    private lateinit var changePasswordItem: ConstraintLayout
    private lateinit var profileNameTextView: TextView
    private lateinit var contactInfoTextView: TextView
    private lateinit var bloodTypeTextView: TextView

    private val USER_DETAILS_PREFS = "user_details_prefs"
    private val KEY_USER_OBJECT = "user_object_json"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateProfileItem = view.findViewById(R.id.update_profile_item)
        changePasswordItem = view.findViewById(R.id.change_password_item)
        profileNameTextView = view.findViewById(R.id.profile_name)
        contactInfoTextView = view.findViewById(R.id.contact_info)
        bloodTypeTextView = view.findViewById(R.id.blood_type)

        loadAndDisplayUserDetails()

        updateProfileItem.setOnClickListener {
            val intent = Intent(requireActivity(), UpdateProfileActivity::class.java)
            startActivity(intent)
        }

        changePasswordItem.setOnClickListener {
            val intent = Intent(requireActivity(), ChangePasswordActivity::class.java)
            startActivity(intent)
        }
    }

    private fun loadAndDisplayUserDetails() {
        val sharedPref = requireActivity().getSharedPreferences(USER_DETAILS_PREFS, Context.MODE_PRIVATE)
        val userJson = sharedPref.getString(KEY_USER_OBJECT, null)

        if (userJson != null) {
            try {
                val user = Gson().fromJson(userJson, User::class.java)

                profileNameTextView.text = user.fullName

                contactInfoTextView.text = "${user.email}\n${user.phoneNumber}"

                bloodTypeTextView.text = user.bloodGroup

            } catch (e: Exception) {
                Toast.makeText(context, "Error loading user profile data.", Toast.LENGTH_LONG).show()
                profileNameTextView.text = getString(R.string.profile)
            }
        } else {
            Toast.makeText(context, "User data not found. Please log in again.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadAndDisplayUserDetails()
    }
}