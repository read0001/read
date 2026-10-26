package app.openread.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.openread.data.SwitchOffer

/** Asks whether to move the book to a source whose newest chapters are complete; nothing is switched without a yes. */
@Composable
fun SwitchOfferDialog(offer: SwitchOffer, onSwitch: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("换到更完整的书源？") },
        text = { Text(offer.message) },
        confirmButton = { TextButton(onSwitch) { Text("切换") } },
        dismissButton = { TextButton(onDismiss) { Text("先不换") } },
    )
}
