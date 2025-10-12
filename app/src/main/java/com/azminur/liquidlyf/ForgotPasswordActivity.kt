package com.azminur.liquidlyf

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var backArrow: ImageView
    private lateinit var emailInput: EditText
    private lateinit var sendResetLinkButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_forgot_password)
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }
        backArrow = findViewById(R.id.back_arrow)
        emailInput = findViewById(R.id.email_input)
        sendResetLinkButton = findViewById(R.id.send_reset_link_button)
        progressBar = findViewById(R.id.progress_bar)
        auth = Firebase.auth

        backArrow.setOnClickListener {
            finish()
        }

        sendResetLinkButton.setOnClickListener {
            sendPasswordReset()
        }
    }

    private fun sendPasswordReset() {
        val emailAddress = emailInput.text.toString().trim()

        if (emailAddress.isEmpty()) {
            Toast.makeText(this, "Please enter your registered email address.", Toast.LENGTH_LONG).show()
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(emailAddress).matches()) {
            Toast.makeText(this, "Please enter a valid email address.", Toast.LENGTH_LONG).show()
            return
        }

        progressBar.visibility = View.VISIBLE

        auth.sendPasswordResetEmail(emailAddress)
            .addOnCompleteListener { task ->
                progressBar.visibility = View.GONE
                if (task.isSuccessful) {
                    Toast.makeText(this, "Password reset link sent to $emailAddress", Toast.LENGTH_LONG).show()

                    val intent = Intent(this, ForgotPasswordVerifyActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this, "Failed to send reset link. Please check the email address.", Toast.LENGTH_LONG).show()
                }
            }
    }
}