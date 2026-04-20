package com.xremail.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xremail.app.backend.service.AuthRepository
import com.xremail.app.ui.theme.XREmailColors
import kotlinx.coroutines.launch

@Composable
fun SignInScreen(
    authRepository: AuthRepository,
    authError: MutableState<String?> = remember { mutableStateOf(null) },
    onSignedIn: () -> Unit = {},
    onUseMockData: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(XREmailColors.surface)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Mail,
            contentDescription = null,
            tint = XREmailColors.primary,
            modifier = Modifier.size(64.dp),
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "XrMail",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = XREmailColors.onSurface,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Sign in to access your Gmail inbox",
            style = MaterialTheme.typography.bodyMedium,
            color = XREmailColors.onSurfaceVariant,
        )

        Spacer(Modifier.height(40.dp))

        if (isLoading) {
            CircularProgressIndicator(color = XREmailColors.primary)
        } else {
            Button(
                onClick = {
                    isLoading = true
                    authError.value = null
                    scope.launch {
                        // startSignIn returns null on success (browser opened);
                        // any non-null result is a human-readable error we
                        // must surface so the user isn't staring at a dead
                        // button wondering what happened.
                        val err = authRepository.startSignIn(context)
                        if (err != null) {
                            authError.value = err
                        }
                        isLoading = false
                    }
                },
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
            ) {
                Text("Sign in with Google")
            }

            authError.value?.let { message ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    color = XREmailColors.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

            if (onUseMockData != null) {
                Spacer(Modifier.height(12.dp))
                // Dev-mode escape hatch: skips the Google OAuth round-trip and
                // drops into the app backed by MockEmailRepository. Use when
                // the backend isn't running, the xrmail://auth/success deep
                // link isn't landing, or you just want to iterate on UI
                // without a real inbox.
                TextButton(
                    onClick = onUseMockData,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = XREmailColors.onSurfaceVariant,
                    ),
                ) {
                    Text("Use mock data")
                }
            }
        }
    }
}
