package com.azminur.liquidlyf

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
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

class RegisterActivity : AppCompatActivity() {

    private lateinit var backArrow: ImageView
    private lateinit var fullName: EditText
    private lateinit var phoneNumber: EditText
    private lateinit var email: EditText
    private lateinit var bloodGroupSpinner: Spinner
    private lateinit var password: EditText
    private lateinit var confirmPassword: EditText
    private lateinit var createAccountButton: Button
    private lateinit var signInLink: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_register)
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }

        backArrow = findViewById(R.id.back_arrow)
        fullName = findViewById(R.id.full_name_input)
        phoneNumber = findViewById(R.id.phone_number_input)
        email = findViewById(R.id.email_input)
        bloodGroupSpinner = findViewById(R.id.blood_group_spinner)
        password = findViewById(R.id.password_input)
        confirmPassword = findViewById(R.id.confirm_password_input)
        createAccountButton = findViewById(R.id.create_account_button)
        signInLink = findViewById(R.id.signin_link)
        progressBar = findViewById(R.id.progress_bar)

        auth = Firebase.auth
        database = Firebase.database


        backArrow.setOnClickListener {
            finish()
        }

        val bloodGroups = resources.getStringArray(R.array.blood_groups)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bloodGroups)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        bloodGroupSpinner.adapter = adapter

        createAccountButton.setOnClickListener {
            registerUser()
        }

        signInLink.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }
    }

    private fun registerUser() {
        val name = fullName.text.toString().trim()
        val phone = phoneNumber.text.toString().trim()
        val emailAddress = email.text.toString().trim()
        val bloodGroup = bloodGroupSpinner.selectedItem.toString()
        val pass = password.text.toString()
        val confirmPass = confirmPassword.text.toString()

        if (name.isEmpty() || phone.isEmpty() || emailAddress.isEmpty() || pass.isEmpty() || confirmPass.isEmpty()) {
            Toast.makeText(this, "Please fill all required fields.", Toast.LENGTH_LONG).show()
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(emailAddress).matches()) {
            Toast.makeText(this, "Please enter a valid email address.", Toast.LENGTH_LONG).show()
            return
        }

        if (pass != confirmPass) {
            Toast.makeText(this, "Passwords do not match.", Toast.LENGTH_LONG).show()
            return
        }

        if (pass.length < 6) {
            Toast.makeText(this, "Password must be at least 6 characters long.", Toast.LENGTH_LONG)
                .show()
            return
        }

        if (bloodGroup == resources.getStringArray(R.array.blood_groups).first()) {
            Toast.makeText(this, "Please select a valid blood group.", Toast.LENGTH_LONG).show()
            return
        }

        progressBar.visibility = View.VISIBLE

        auth.createUserWithEmailAndPassword(emailAddress, pass)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val firebaseUser = auth.currentUser
                    val uid = firebaseUser?.uid ?: run {
                        progressBar.visibility = View.GONE
                        Toast.makeText(
                            this,
                            "Authentication succeeded but UID is null.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@addOnCompleteListener
                    }

                    val user = User(
                        uid = uid,
                        fullName = name,
                        phoneNumber = phone,
                        email = emailAddress,
                        bloodGroup = bloodGroup
                    )

                    database.getReference("Users").child(uid).setValue(user)
                        .addOnSuccessListener {
                            firebaseUser.sendEmailVerification()
                                .addOnCompleteListener { emailTask ->
                                    progressBar.visibility = View.GONE
                                    if (emailTask.isSuccessful) {
                                        Toast.makeText(
                                            this,
                                            "Registration successful! Please check your email to verify your account.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            this,
                                            "Registration successful, but verification email failed to send: ${emailTask.exception?.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    val intent = Intent(this, RegisterVerifyActivity::class.java)
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    startActivity(intent)
                                    finish()
                                }
                        }
                        .addOnFailureListener {
                            progressBar.visibility = View.GONE
                            Toast.makeText(
                                this,
                                "Failed to save user data: ${it.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            firebaseUser?.delete()
                        }
                } else {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this,
                        "Registration failed: ${task.exception?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }
}