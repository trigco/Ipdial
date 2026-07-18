package com.ipdial.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ipdial.data.model.Contact
import com.ipdial.ui.AccountSelectionDialog
import com.ipdial.ui.IPDialTopBar
import com.ipdial.ui.NumberPickerDialog
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.theme.glass
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    vm: SipViewModel, 
    onOpenDrawer: () -> Unit,
    onNavigateToAccounts: () -> Unit = {}
) {
    val contacts by vm.contacts.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val context = LocalContext.current
    var activeContactForNumberPicker by remember { mutableStateOf<Contact?>(null) }

    val sortedContacts = remember(contacts) {
        contacts.sortedBy { it.name.trim().lowercase() }
    }
    
    val filteredContacts = remember(sortedContacts, searchQuery) {
        if (searchQuery.isBlank()) sortedContacts
        else sortedContacts.filter { 
            it.name.contains(searchQuery, ignoreCase = true) || 
            it.numbers.any { num -> num.contains(searchQuery) }
        }
    }

    val alphabet = remember { ('A'..'Z').toList() }
    val letterToFirstIndex = remember(filteredContacts) {
        val map = mutableMapOf<Char, Int>()
        filteredContacts.forEachIndexed { index, contact ->
            val firstChar = contact.name.trim().firstOrNull()?.uppercaseChar() ?: '#'
            val targetChar = if (firstChar in 'A'..'Z') firstChar else '#'
            if (!map.containsKey(targetChar)) {
                map[targetChar] = index
            }
        }
        map
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom)
    ) { padding ->
        Column(modifier = Modifier.padding(padding).background(MaterialTheme.colorScheme.background)) {
            IPDialTopBar(
                accounts = accounts, 
                vm = vm, 
                onOpenDrawer = onOpenDrawer,
                onAddAccount = onNavigateToAccounts
            )

            SearchBarRow(
                query = searchQuery,
                onQueryChange = { vm.onSearchQueryChanged(it) }
            )
            
            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 100.dp) 
                ) {
                    items(filteredContacts, key = { it.id }) { contact ->
                        ContactItem(
                            contact = contact,
                            onNumberClick = { num -> vm.makeCall(num) },
                            onContactClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        data = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contact.id)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.util.Log.e("ContactsScreen", "Cannot open contact", e)
                                }
                            }
                        )
                    }
                }

                AlphabetIndexer(
                    alphabet = alphabet,
                    letterToFirstIndex = letterToFirstIndex,
                    onLetterSelected = { _, index ->
                        coroutineScope.launch {
                            listState.scrollToItem(index)
                        }
                    }
                )
            }
        }
    }

    activeContactForNumberPicker?.let { contact ->
        NumberPickerDialog(
            numbers = contact.numbers,
            onPick = { number -> vm.makeCall(number) },
            onDismiss = { activeContactForNumberPicker = null }
        )
    }

    val showAccountSelection by vm.showAccountSelectionDialog.collectAsState()
    val enabledAccounts = remember(accounts) {
        accounts.filter { it.isEnabled }
    }

    if (showAccountSelection && enabledAccounts.isNotEmpty()) {
        AccountSelectionDialog(
            enabledAccounts = enabledAccounts,
            onAccountSelected = { vm.proceedWithCallAfterAccountSelection(it) },
            onDismiss = { vm.dismissAccountSelection() }
        )
    }
}

@Composable
fun ContactItem(contact: Contact, onNumberClick: (String) -> Unit, onContactClick: () -> Unit) {
    val context = LocalContext.current
    val isGlass = com.ipdial.ui.theme.LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None
    Surface(
        color = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = if (isGlass) 0.dp else 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .then(if (isGlass) Modifier.glass(RoundedCornerShape(16.dp)) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickableWithRipple { onContactClick() },
                contentAlignment = Alignment.Center
            ) {
                if (contact.photoUri != null) {
                    val request = remember(contact.photoUri) {
                        ImageRequest.Builder(context)
                            .data(contact.photoUri)
                            .size(96, 96)
                            .crossfade(true)
                            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Text(
                        contact.name.take(1).uppercase(), 
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.clickableWithRipple { onContactClick() }
                )
                contact.numbers.forEach { number ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableWithRipple { onNumberClick(number) }
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = number,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
