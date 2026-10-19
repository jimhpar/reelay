package com.reelay.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelay.app.MainActivity
import com.reelay.app.data.PreferencesManager
import com.reelay.app.theme.ReelayTheme

class AuthActivity : ComponentActivity() {

    companion object {
        const val EXTRA_REASON = "extra_reason"
        const val REASON_OPEN_APP = "reason_open_app"
        const val REASON_SETTINGS_PROTECTION = "reason_settings_protection"
    }

    private lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        val reason = intent.getStringExtra(EXTRA_REASON) ?: REASON_OPEN_APP

        // If no password is set yet and not intercepting settings, directly proceed to MainActivity
        if (!prefs.isPasswordSet && reason == REASON_OPEN_APP) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            ReelayTheme {
                AuthScreen(
                    isPasswordSet = prefs.isPasswordSet,
                    reason = reason,
                    onVerifyPassword = { inputPassword ->
                        if (!prefs.isPasswordSet) {
                            if (inputPassword.length < 4) {
                                Toast.makeText(this, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show()
                                false
                            } else {
                                prefs.setMasterPassword(inputPassword)
                                prefs.updateLastAuthTime()
                                Toast.makeText(this, "Master Password Saved!", Toast.LENGTH_SHORT).show()
                                proceedAfterSuccess(reason)
                                true
                            }
                        } else {
                            if (prefs.verifyPassword(inputPassword)) {
                                prefs.updateLastAuthTime()
                                proceedAfterSuccess(reason)
                                true
                            } else {
                                Toast.makeText(this, "Incorrect Password! Try again.", Toast.LENGTH_SHORT).show()
                                false
                            }
                        }
                    },
                    onCancel = {
                        finish()
                    }
                )
            }
        }
    }

    private fun proceedAfterSuccess(reason: String) {
        if (reason == REASON_SETTINGS_PROTECTION) {
            Toast.makeText(this, "Settings access unlocked for 60 seconds", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            MainActivity.isSessionAuthenticated = true
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}

@Composable
fun AuthScreen(
    isPasswordSet: Boolean,
    reason: String,
    onVerifyPassword: (String) -> Boolean,
    onCancel: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val gradientBg = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0F172A),
            Color(0xFF020617)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradientBg)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E293B).copy(alpha = 0.95f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF3B82F6).copy(alpha = 0.2f),
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (reason == AuthActivity.REASON_SETTINGS_PROTECTION) Icons.Default.Shield else Icons.Default.Lock,
                            contentDescription = "Security",
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = if (reason == AuthActivity.REASON_SETTINGS_PROTECTION) {
                        "Protected Action"
                    } else if (!isPasswordSet) {
                        "Set Master Password"
                    } else {
                        "Reelay Authentication"
                    },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (reason == AuthActivity.REASON_SETTINGS_PROTECTION) {
                        "Uninstall or modifying Reelay settings requires the master password."
                    } else if (!isPasswordSet) {
                        "Create a password to protect Reelay settings and prevent unauthorized uninstallation."
                    } else {
                        "Enter your master password to unlock."
                    },
                    fontSize = 14.sp,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text(if (!isPasswordSet) "Create Password" else "Enter Password") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val success = onVerifyPassword(password)
                            if (!success) {
                                errorMessage = "Incorrect password"
                            }
                        }
                    ),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle password visibility",
                                tint = Color(0xFF94A3B8)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF475569),
                        focusedLabelColor = Color(0xFF60A5FA),
                        unfocusedLabelColor = Color(0xFF94A3B8),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage ?: "",
                        color = Color(0xFFEF4444),
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        val success = onVerifyPassword(password)
                        if (!success) {
                            errorMessage = "Incorrect password"
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (!isPasswordSet) "Save & Continue" else "Unlock",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }

                if (reason == AuthActivity.REASON_SETTINGS_PROTECTION) {
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Cancel",
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }
    }
}
