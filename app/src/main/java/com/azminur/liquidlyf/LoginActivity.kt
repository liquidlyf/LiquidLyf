package com.azminur.liquidlyf

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.database
import com.google.gson.Gson

class LoginActivity : AppCompatActivity() {
    private lateinit var emailInput: EditText
    private lateinit var password: EditText
    private lateinit var forgotPasswordLink: TextView
    private lateinit var loginButton: Button
    private lateinit var rememberCheckbox: CheckBox
    private lateinit var progressBar: ProgressBar
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private val PREFS_NAME = "login_prefs"
    private val KEY_CREDENTIAL = "saved_credential"
    private val USER_DETAILS_PREFS = "user_details_prefs"
    private val KEY_USER_OBJECT = "user_object_json"


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }

        emailInput = findViewById(R.id.email_input)
        password = findViewById(R.id.password_input)
        forgotPasswordLink = findViewById(R.id.forgot_password_link)
        loginButton = findViewById(R.id.login_button)
        rememberCheckbox = findViewById(R.id.remember_checkbox)
        progressBar = findViewById(R.id.progress_bar)
        auth = Firebase.auth
        database = Firebase.database


        val resetMessage = intent.getStringExtra("reset_message")
        if (!resetMessage.isNullOrEmpty()) {
            Toast.makeText(this, resetMessage, Toast.LENGTH_SHORT).show()
        }

        val sharedPref = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedCredential = sharedPref.getString(KEY_CREDENTIAL, "")
        if (!savedCredential.isNullOrEmpty()) {
            emailInput.setText(savedCredential)
            rememberCheckbox.isChecked = true
        }

        loginButton.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val pass = password.text.toString()

            if (email.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Please enter your email and password.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (rememberCheckbox.isChecked) {
                val editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                editor.putString(KEY_CREDENTIAL, email)
                editor.apply()
            }

            loginUser(email, pass)
        }

        forgotPasswordLink.setOnClickListener {
            val intent = Intent(this, ForgotPasswordActivity::class.java)
            startActivity(intent)
        }

        rememberCheckbox.setOnCheckedChangeListener { _, isChecked ->
            val sharedPref = getSharedPreferences(PREFS_NAME,MODE_PRIVATE)
            val editor = sharedPref.edit()

            if (isChecked) {
                val credential = emailInput.text.toString()
                editor.putString(KEY_CREDENTIAL, credential)
                editor.apply()
                Toast.makeText(this, "Email saved!", Toast.LENGTH_SHORT).show()
            } else {
                editor.remove(KEY_CREDENTIAL)
                editor.apply()
                Toast.makeText(this, "Email removed!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loginUser(email: String, password: String) {
        progressBar.visibility = View.VISIBLE

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    val uid = user?.uid

                    if (user != null && user.isEmailVerified && uid != null) {
                        fetchUserDetails(uid)
                    } else {
                        progressBar.visibility = View.GONE
                        Toast.makeText(this, "Please verify your email address to log in.", Toast.LENGTH_LONG).show()

                        user?.sendEmailVerification()
                        Toast.makeText(this, "Verification email resent.", Toast.LENGTH_SHORT).show()
                        auth.signOut()
                    }

                } else {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "Authentication failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun fetchUserDetails(uid: String) {
        database.getReference("Users").child(uid).addListenerForSingleValueEvent(object :
            ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                progressBar.visibility = View.GONE
                if (snapshot.exists()) {
                    val userDetails = snapshot.getValue(User::class.java)
                    if (userDetails != null) {
                        val sharedPref = getSharedPreferences(USER_DETAILS_PREFS, MODE_PRIVATE)
                        val userJson = Gson().toJson(userDetails)
                        sharedPref.edit().putString(KEY_USER_OBJECT, userJson).apply()

                        // 2. Save login track (is_logged_in state)
                        getSharedPreferences("auth_prefs", MODE_PRIVATE).edit()
                            .putBoolean("is_logged_in", true)
                            .apply()

                        Toast.makeText(this@LoginActivity, "Login successful!", Toast.LENGTH_SHORT).show()

                        // 3. Start AfterLoginActivity and save user UID in Intent
                        val intent = Intent(this@LoginActivity, AfterLoginActivity::class.java).apply {
                            putExtra("USER_UID", uid)
                        }
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this@LoginActivity, "Login failed: User data is incomplete.", Toast.LENGTH_LONG).show()
                        auth.signOut()
                    }
                } else {
                    Toast.makeText(this@LoginActivity, "Login failed: User data not found.", Toast.LENGTH_LONG).show()
                    auth.signOut()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                progressBar.visibility = View.GONE
                Toast.makeText(this@LoginActivity, "Failed to load user data: ${error.message}", Toast.LENGTH_LONG).show()
                auth.signOut()
            }
        })
    }
}