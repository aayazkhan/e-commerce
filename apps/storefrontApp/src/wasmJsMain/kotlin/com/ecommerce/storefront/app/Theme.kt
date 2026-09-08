package com.ecommerce.storefront.app

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/** Minimal, inline color/typography scheme -- matches the sellerApp/adminApp pattern of not
 * promoting shared chrome until a third app actually needs it. */
private val StorefrontColorScheme = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    background = Color(0xFFFAFAF9),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1C1917),
    onSurface = Color(0xFF1C1917),
    error = Color(0xFFDC2626),
    secondary = Color(0xFFEA580C),
)

@Composable
fun StorefrontTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StorefrontColorScheme, content = content)
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier, colors = ButtonDefaults.buttonColors()) {
        Text(text)
    }
}

@Composable
fun SecondaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
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
