package com.azminur.liquidlyf

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.Firebase
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

class ChangePasswordActivity : AppCompatActivity() {

    private lateinit var backArrow: ImageView
    private lateinit var currentPasswordInput: EditText
    private lateinit var newPasswordInput: EditText
    private lateinit var confirmNewPasswordInput: EditText
    private lateinit var saveChangesButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var auth: FirebaseAuth

    private val MIN_PASSWORD_LENGTH = 6

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_change_password)
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }

        auth = Firebase.auth
        backArrow = findViewById(R.id.back_arrow)
        currentPasswordInput = findViewById(R.id.current_password_input)
        newPasswordInput = findViewById(R.id.new_password_input)
        confirmNewPasswordInput = findViewById(R.id.confirm_new_password_input)
        saveChangesButton = findViewById(R.id.save_changes_button)
        progressBar = findViewById(R.id.progress_bar)

        backArrow.setOnClickListener {
            finish()
        }

        saveChangesButton.setOnClickListener {
            changePassword()
        }
    }
    private fun changePassword() {
        val currentPassword = currentPasswordInput.text.toString()
        val newPassword = newPasswordInput.text.toString()
        val confirmNewPassword = confirmNewPasswordInput.text.toString()

        val user = auth.currentUser
        val email = user?.email

        // 1. Basic Validation
        if (currentPassword.isEmpty() || newPassword.isEmpty() || confirmNewPassword.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields.", Toast.LENGTH_LONG).show()
            return
        }
        if (newPassword != confirmNewPassword) {
            Toast.makeText(this, "New passwords do not match.", Toast.LENGTH_LONG).show()
            return
        }
        if (newPassword.length < MIN_PASSWORD_LENGTH) {
            Toast.makeText(this, "New password must be at least $MIN_PASSWORD_LENGTH characters.", Toast.LENGTH_LONG).show()
            return
        }
        if (user == null || email == null) {
            Toast.makeText(this, "User session expired. Please log in again.", Toast.LENGTH_LONG).show()
            return
        }

        progressBar.visibility = View.VISIBLE

        val credential = EmailAuthProvider.getCredential(email, currentPassword)

        user.reauthenticate(credential)
            .addOnCompleteListener { reauthTask ->
                if (reauthTask.isSuccessful) {
                    user.updatePassword(newPassword)
                        .addOnCompleteListener { updateTask ->
                            progressBar.visibility = View.GONE
                            if (updateTask.isSuccessful) {
                                Toast.makeText(this, "Password changed successfully!", Toast.LENGTH_SHORT).show()
                                finish()
                            } else {
                                Toast.makeText(this, "Failed to update password: ${updateTask.exception?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                } else {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "Current password incorrect or session expired.", Toast.LENGTH_LONG).show()
                }
            }
    }
}