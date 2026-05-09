package com.example.weatherapp.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.weatherapp.R

@Composable
fun SetupScreen(viewModel: SetupViewModel) {
    val state by viewModel.state

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                state.locations.forEachIndexed { index, location ->
                    val isSelected = state.targetLocationId == location.id
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) {
                                    viewModel.selectAddNew()
                                } else {
                                    viewModel.selectReplacement(location.id)
                                }
                            },
                            label = { Text("${index + 1}: ${location.name}") },
                            colors = selectedChipColors(),
                            border = selectedChipBorder(isSelected)
                        )
                        IconButton(onClick = { viewModel.deleteLocation(location.id) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = "Delete ${location.name}",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::updateQuery,
                label = { Text("Add new city or place") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = viewModel::searchNow, enabled = !state.isSearching) {
                Text("Search")
            }
        }
        state.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
        state.results.forEach { result ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(result.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(result.admin1, result.country, result.timezone).joinToString(" - "),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "%.4f, %.4f".format(result.latitude, result.longitude),
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = { viewModel.save(result) }) {
                    Text(if (state.targetLocationId == null) "Add location" else "Replace location")
                }
            }
        }
    }
}

@Composable
private fun selectedChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
private fun selectedChipBorder(isSelected: Boolean) = FilterChipDefaults.filterChipBorder(
    enabled = true,
    selected = isSelected,
    borderColor = MaterialTheme.colorScheme.surfaceVariant,
    selectedBorderColor = MaterialTheme.colorScheme.primary,
    borderWidth = 1.dp,
    selectedBorderWidth = 2.dp
)
