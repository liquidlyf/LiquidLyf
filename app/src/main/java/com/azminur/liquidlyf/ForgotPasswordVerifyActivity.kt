package com.azminur.liquidlyf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.text.TextWatcher


class ForgotPasswordVerifyActivity : AppCompatActivity() {

    private lateinit var backArrow: ImageView
    private lateinit var goToEmailButton: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_forgot_password_verify)
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }

        backArrow = findViewById(R.id.back_arrow)
        goToEmailButton = findViewById(R.id.go_to_email_button)
        progressBar = findViewById(R.id.progress_bar)

        backArrow.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        goToEmailButton.setOnClickListener {
            openEmailApp()
        }

        progressBar.visibility = View.GONE
    }


    private fun openEmailApp() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_EMAIL)
        }

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            val websiteIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com"))
            startActivity(websiteIntent)
        }
    }
}