package com.example.antiwispr.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.antiwispr.ui.theme.Dimens

/** Single-select bottom sheet; long lists (languages, senders) live in a LazyColumn.
 *  Shared by Settings pickers and the search filter chips. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> OptionPickerSheet(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    description: (T) -> String? = { null },
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = Dimens.screenPad)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(options) { option ->
                    val isSelected = option == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                label(option),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            description(option)?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (isSelected) {
                            Icon(
                                Icons.Filled.Check, contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    InkDivider()
                }
            }
        }
    }
}
