package com.roox.clawlauncher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roox.clawlauncher.license.LicenseState
import com.roox.clawlauncher.license.LicenseStatus
import com.roox.clawlauncher.ui.theme.*

@Composable
fun LicenseScreen(
    status: LicenseStatus,
    onActivate: (String) -> Unit,
    isLoading: Boolean = false
) {
    var licenseKey by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClawDarkBg)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Logo
        Text("🦀", fontSize = 64.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text("ClawLauncher", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ClawTextPrimary)
        Text("OpenClaw for Android", fontSize = 14.sp, color = ClawTextSecondary)

        Spacer(modifier = Modifier.height(32.dp))

        // Status
        when (status.state) {
            LicenseState.VALID, LicenseState.OFFLINE_GRACE -> {
                // Should not show this screen, but just in case
                Icon(Icons.Default.CheckCircle, null, tint = ClawGreen, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("License Active", fontSize = 18.sp, color = ClawGreen, fontWeight = FontWeight.Bold)
                Text("${status.daysLeft} days remaining", fontSize = 13.sp, color = ClawTextSecondary)
            }
            LicenseState.EXPIRED -> {
                Icon(Icons.Default.AccessTime, null, tint = ClawYellow, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("License Expired", fontSize = 18.sp, color = ClawYellow, fontWeight = FontWeight.Bold)
                Text("Contact admin for renewal", fontSize = 13.sp, color = ClawTextSecondary)
            }
            LicenseState.REVOKED -> {
                Icon(Icons.Default.Block, null, tint = ClawRed, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("License Revoked", fontSize = 18.sp, color = ClawRed, fontWeight = FontWeight.Bold)
                Text("This license has been deactivated", fontSize = 13.sp, color = ClawTextSecondary)
            }
            LicenseState.INVALID -> {
                Icon(Icons.Default.Error, null, tint = ClawRed, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Invalid Key", fontSize = 18.sp, color = ClawRed, fontWeight = FontWeight.Bold)
                Text(status.message, fontSize = 13.sp, color = ClawTextSecondary, textAlign = TextAlign.Center)
            }
            LicenseState.ERROR -> {
                Icon(Icons.Default.WifiOff, null, tint = ClawYellow, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Connection Error", fontSize = 18.sp, color = ClawYellow, fontWeight = FontWeight.Bold)
                Text(status.message, fontSize = 12.sp, color = ClawTextSecondary, textAlign = TextAlign.Center)
            }
            else -> {
                Icon(Icons.Default.Key, null, tint = ClawBlue, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Enter License Key", fontSize = 18.sp, color = ClawTextPrimary, fontWeight = FontWeight.Bold)
                Text("Activate your subscription", fontSize = 13.sp, color = ClawTextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Key input
        OutlinedTextField(
            value = licenseKey,
            onValueChange = { licenseKey = it.uppercase().trim() },
            label = { Text("License Key", color = ClawTextSecondary) },
            placeholder = { Text("CLAW-XXXX-XXXX-XXXX", color = ClawTextSecondary.copy(alpha = 0.3f)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                color = ClawTextPrimary,
                textAlign = TextAlign.Center
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ClawRed,
                unfocusedBorderColor = ClawCardBgLight,
                cursorColor = ClawRed,
                focusedContainerColor = ClawCardBg,
                unfocusedContainerColor = ClawCardBg
            ),
            shape = RoundedCornerShape(14.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Activate button
        Button(
            onClick = { onActivate(licenseKey) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            enabled = licenseKey.length >= 8 && !isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = ClawRed)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = ClawTextPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Verifying...", fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Default.VpnKey, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Activate", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Info
        Text(
            "Get your license key from the admin.\nSubscription: \$5/month",
            fontSize = 11.sp,
            color = ClawTextSecondary.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )
    }
}
