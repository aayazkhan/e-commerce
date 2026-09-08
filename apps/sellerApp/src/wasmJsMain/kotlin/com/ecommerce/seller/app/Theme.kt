package com.ecommerce.seller.app

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Minimal, inline color/typography scheme -- not promoted to shared/design-system until a second
 * app needs it (see the frontend bootstrap plan).
 */
private val SellerColorScheme = darkColorScheme(
    primary = Color(0xFF4F8CFF),
    onPrimary = Color.White,
    background = Color(0xFF0F1115),
    surface = Color(0xFF171A21),
    onBackground = Color(0xFFE6E9EF),
    onSurface = Color(0xFFE6E9EF),
    error = Color(0xFFFF6B6B),
)

@Composable
fun SellerAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SellerColorScheme, content = content)
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier, colors = ButtonDefaults.buttonColors()) {
        Text(text)
    }
}

@Composable
fun LabeledTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.Companion.None,
        keyboardOptions = KeyboardOptions.Companion.Default,
        modifier = modifier,
    )
}
