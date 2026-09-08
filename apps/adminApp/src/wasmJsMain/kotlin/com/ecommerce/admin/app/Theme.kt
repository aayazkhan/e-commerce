package com.ecommerce.admin.app

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
 * Same minimal inline scheme as sellerApp's Theme.kt -- not worth extracting to a shared
 * design-system module yet for two internal tools this small; duplicated deliberately.
 */
private val AdminColorScheme = darkColorScheme(
    primary = Color(0xFFB0722F),
    onPrimary = Color.White,
    background = Color(0xFF0F1115),
    surface = Color(0xFF171A21),
    onBackground = Color(0xFFE6E9EF),
    onSurface = Color(0xFFE6E9EF),
    error = Color(0xFFFF6B6B),
)

@Composable
fun AdminAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AdminColorScheme, content = content)
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
