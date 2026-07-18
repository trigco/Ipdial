@file:Suppress("OPT_IN_USAGE", "EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_IS_NOT_ENABLED")
package com.ipdial.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.InterceptPlatformTextInput
import kotlinx.coroutines.awaitCancellation
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ipdial.data.model.Contact
import com.ipdial.data.model.KeypadDesign
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.IPDialTopBar
import com.ipdial.ui.AccountSelectionDialog

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun DialpadScreen(
    vm: SipViewModel, 
    onOpenDrawer: () -> Unit,
    onNavigateToAccounts: () -> Unit = {}
) {
    val dialTextFieldValue by vm.dialString.collectAsState()
    val dialString = dialTextFieldValue.text
    val lastDialedNumber by vm.lastDialedNumber.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val mostCalled by vm.mostCalledContacts.collectAsState()
    val keypadDesign by vm.keypadDesign.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    val suggestedContacts = remember(dialString, contacts) {
        if (dialString.isBlank()) emptyList()
        else {
            contacts.asSequence().filter { contact ->
                contact.name.contains(dialString, ignoreCase = true) ||
                contact.numbers.any { it.filter { it.isDigit() }.contains(dialString) } ||
                contact.name.lowercase().mapNotNull { T9_MAP[it] }.joinToString("").contains(dialString)
            }.take(5).toList()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IPDialTopBar(
                accounts = accounts, 
                vm = vm, 
                onOpenDrawer = onOpenDrawer,
                onAddAccount = onNavigateToAccounts
            )

            // Suggested contacts space
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (dialString.isEmpty() && mostCalled.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Text(
                                text = "Most Called",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp)
                            )
                        }
                        itemsIndexed(mostCalled, key = { _, it -> it.id }) { _, contact ->
                            SuggestedContactRow(contact) { num ->
                                vm.clearDial()
                                num.filter { it.isDigit() || it == '+' }.forEach { vm.dialPad(it) }
                                vm.makeCall()
                            }
                        }
                    }
                } else if (suggestedContacts.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(suggestedContacts, key = { it.id }) { contact ->
                            SuggestedContactRow(contact) { num ->
                                vm.clearDial()
                                num.filter { it.isDigit() || it == '+' }.forEach { vm.dialPad(it) }
                                vm.makeCall()
                            }
                        }
                    }
                } else if (dialString.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No matching contacts",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            // Dial display row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (clipboardManager.hasText()) {
                            DropdownMenuItem(
                                text = { Text("Paste") },
                                onClick = {
                                    showMenu = false
                                    clipboardManager.getText()?.text?.let { text ->
                                        text.filter { it.isDigit() || it == '+' }.forEach { vm.dialPad(it) }
                                    }
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Add to contact") },
                            onClick = { 
                                showMenu = false
                                val intent = android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
                                    type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
                                    putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, dialString)
                                }
                                context.startActivity(intent)
                            }
                        )
                    }
                }

                InterceptPlatformTextInput(
                    interceptor = { _, _ -> awaitCancellation() }
                ) {
                    BasicTextField(
                        value = dialTextFieldValue,
                        onValueChange = { vm.setDialString(it) },
                        textStyle = MaterialTheme.typography.displayMedium.copy(
                            fontSize = if (dialString.length > 10) 36.sp else 46.sp,
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        maxLines = 1,
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }

                AnimatedVisibility(
                    visible = dialString.isNotEmpty(),
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClick = { vm.backspace() },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.clearDial()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = "Backspace",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (dialString.isEmpty()) Spacer(Modifier.size(48.dp))
            }

            Spacer(Modifier.height(8.dp))

            // Modern Premium Keypad Grid
            val keys = listOf(
                Triple("1", "⠀", null),
                Triple("2", "ABC", null),
                Triple("3", "DEF", null),
                Triple("4", "GHI", null),
                Triple("5", "JKL", null),
                Triple("6", "MNO", null),
                Triple("7", "PQRS", null),
                Triple("8", "TUV", null),
                Triple("9", "WXYZ", null),
                Triple("*", "", null),
                Triple("0", "+", null),
                Triple("#", "", null),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                keys.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        row.forEach { (digit, sub, _) ->
                            DialKey(
                                digit = digit,
                                subLabel = sub,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.dialPad(digit[0])
                                },
                                onLongClick = if (digit == "0") {
                                    {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        vm.dialPad('+')
                                    }
                                } else null
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Clean Premium FAB for Calling
            FloatingActionButton(
                onClick = { 
                    if (dialString.isEmpty() && !lastDialedNumber.isNullOrEmpty()) {
                        vm.setDialString(androidx.compose.ui.text.input.TextFieldValue(lastDialedNumber!!))
                    } else if (dialString.isNotEmpty()) {
                        vm.makeCall()
                    }
                },
                containerColor = com.ipdial.ui.theme.AccentGreen, 
                contentColor = Color.White,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 12.dp),
                modifier = Modifier.size(76.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Call",
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(Modifier.height(32.dp))
        }
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

private val T9_MAP = mapOf(
    'a' to '2', 'b' to '2', 'c' to '2',
    'd' to '3', 'e' to '3', 'f' to '3',
    'g' to '4', 'h' to '4', 'i' to '4',
    'j' to '5', 'k' to '5', 'l' to '5',
    'm' to '6', 'n' to '6', 'o' to '6',
    'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
    't' to '8', 'u' to '8', 'v' to '8',
    'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9'
)

@Composable
fun SuggestedContactRow(contact: Contact, onNumberClick: (String) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (contact.photoUri != null) {
                val request = remember(contact.photoUri) {
                    coil.request.ImageRequest.Builder(context)
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
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = contact.name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            contact.numbers.forEach { number ->
                Text(
                    text = number,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNumberClick(number) }
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

// Fixed size, perfectly circular buttons following Premium iOS/Pixel guidelines
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialKey(
    digit: String,
    subLabel: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.9f else 1.0f)
    val bgColor = if (isPressed) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent

    Box(
        modifier = Modifier
            .size(80.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(bgColor)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null, // Disable default ripple for a custom scale effect
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = digit,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = 36.sp
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subLabel.isNotBlank()) {
                Text(
                    text = subLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    letterSpacing = 2.sp,
                    fontSize = 10.sp
                )
            }
        }
    }
}
