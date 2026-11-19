package com.noxsip.app.ui.screens

import com.noxsip.app.ui.theme.themed
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import com.noxsip.app.data.models.SavedContact
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noxsip.app.ui.theme.Green500
import com.noxsip.app.ui.theme.Indigo600
import com.noxsip.app.util.DeviceContact

@Composable
fun ContactsScreen(
    contacts: List<DeviceContact>,
    hasContactsPermission: Boolean,
    onRequestPermission: () -> Unit,
    onCallContact: (String) -> Unit,
    savedContacts: List<SavedContact> = emptyList(),
    onSaveContact: (SavedContact) -> Unit = {},
    onDeleteSavedContact: (SavedContact) -> Unit = {},
    onOpenChat: (String, String) -> Unit = { _, _ -> },
    detectMessengers: suspend (String) -> Pair<Boolean, Boolean> = { _ -> false to false },
    pendingAdd: AddContactRequest? = null,
    onPendingConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 Phone, 1 Client, 2 Office
    var formDraft by remember { mutableStateOf<SavedContact?>(null) }
    var phoneMenu by remember { mutableStateOf<DeviceContact?>(null) }

    // কল লগ থেকে "Client/Office-এ যোগ" এলে ওই ট্যাবে গিয়ে ফর্ম খুলি
    LaunchedEffect(pendingAdd) {
        if (pendingAdd != null) {
            tab = if (pendingAdd.category == SavedContact.CATEGORY_OFFICE) 2 else 1
            formDraft = SavedContact(
                category = pendingAdd.category,
                name = pendingAdd.name,
                number = pendingAdd.number
            )
            onPendingConsumed()
        }
    }

    val category = if (tab == 2) SavedContact.CATEGORY_OFFICE else SavedContact.CATEGORY_CLIENT
    val inCategory = remember(savedContacts, category) { savedContacts.filter { it.category == category } }
    val filteredSaved = remember(inCategory, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) inCategory else {
            val digits = q.filter { it.isDigit() || it == '+' }
            inCategory.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.company.contains(q, ignoreCase = true) ||
                    it.number.contains(q, ignoreCase = true) ||
                    (digits.isNotEmpty() && it.number.filter { c -> c.isDigit() || c == '+' }.contains(digits))
            }
        }
    }

    val filteredContacts = remember(contacts, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            contacts
        } else {
            val cleanDigits = q.filter { it.isDigit() || it == '+' }
            contacts.filter { contact ->
                contact.name.contains(q, ignoreCase = true) ||
                        contact.number.contains(q, ignoreCase = true) ||
                        (cleanDigits.isNotEmpty() && contact.normalizedDigits.contains(cleanDigits))
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Slim Search Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(Indigo600),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search Google & phone contacts...",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                )
                            }
                            innerTextField()
                        }
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { searchQuery = "" }
                            )
                        }
                    }
                }
            )
        }

        // Phone | Client | Office ট্যাব (কল লগের ফিল্টারের মতো)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(themed(Color(0xFFF2F3F5), Color(0xFF1A1A1A)))
                .border(1.dp, themed(Color(0xFFE3E5EA), Color(0xFF333333)), RoundedCornerShape(20.dp))
                .padding(3.dp)
        ) {
            listOf("Phone", "Client", "Office").forEachIndexed { i, label ->
                val selected = tab == i
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(17.dp))
                        .background(if (selected) themed(Color.White, Color(0xFF0E0E0E)) else Color.Transparent)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { tab = i }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = 13.sp,
                        maxLines = 1,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) themed(Color(0xFF111111), Color(0xFFF8FAFC)) else themed(Color(0xFF444B55), Color(0xFFD4D4D8))
                    )
                }
            }
        }

        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )

        if (tab != 0) {
            SavedContactsTab(
                category = category,
                contacts = filteredSaved,
                totalInCategory = inCategory.size,
                onAdd = { formDraft = SavedContact(category = category, name = "", number = "") },
                onCall = { onCallContact(it.number.filter { c -> c.isDigit() || c == '+' }) },
                onEdit = { formDraft = it },
                onDelete = onDeleteSavedContact,
                onOpenChat = onOpenChat
            )
        } else if (!hasContactsPermission) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Indigo600.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Contacts,
                            contentDescription = null,
                            tint = Indigo600,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Text(
                        text = "Contacts Permission Required",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Button(
                        onClick = onRequestPermission,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo600)
                    ) {
                        Text("Allow Access to Contacts", fontSize = 13.sp)
                    }
                }
            }
        } else if (filteredContacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Indigo600.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                            tint = Indigo600
                        )
                    }
                    Text(
                        text = if (searchQuery.isEmpty()) "No Contacts Found" else "No matching contacts",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(filteredContacts) { index, contact ->
                    ContactRow(
                        contact = contact,
                        onCall = { onCallContact(contact.normalizedDigits) },
                        onLongClick = { phoneMenu = contact }
                    )
                    if (index < filteredContacts.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp, end = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }
    }

    // ফোনবুকের কন্টাক্টে লং-প্রেস: Client/Office-এ যোগ
    phoneMenu?.let { c ->
        AlertDialog(
            onDismissRequest = { phoneMenu = null },
            title = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        SavedContact.CATEGORY_CLIENT to "Add to Client",
                        SavedContact.CATEGORY_OFFICE to "Add to Office"
                    ).forEach { (cat, label) ->
                        Text(
                            text = label,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Indigo600,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    phoneMenu = null
                                    tab = if (cat == SavedContact.CATEGORY_OFFICE) 2 else 1
                                    formDraft = SavedContact(category = cat, name = c.name, number = c.number)
                                }
                                .padding(horizontal = 6.dp, vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { phoneMenu = null }) { Text("Cancel") } }
        )
    }

    formDraft?.let { draft ->
        SavedContactFormDialog(
            draft = draft,
            detectMessengers = detectMessengers,
            onSave = { onSaveContact(it); formDraft = null },
            onDismiss = { formDraft = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    contact: DeviceContact,
    onCall: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onCall, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Indigo600.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                val initial = contact.name.firstOrNull { it.isLetter() }?.uppercaseChar()
                if (initial != null) {
                    Text(
                        text = initial.toString(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Indigo600
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = Indigo600,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = contact.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = contact.number,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        IconButton(
            onClick = onCall,
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                Icons.Filled.Call,
                contentDescription = "Call",
                tint = Green500,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
