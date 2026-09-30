package io.github.arumoy.nfcwallet

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.arumoy.nfcwallet.ui.theme.NFCWalletTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Which screen the app is currently showing. */
private enum class Screen { HOME, ADD }

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private var nfcAdapter: NfcAdapter? = null

    private lateinit var repository: CardRepository

    // App state
    private val cardsState = mutableStateOf<List<SavedCard>>(emptyList())
    private val screenState = mutableStateOf(Screen.HOME)

    // The card currently being edited (null when adding a new card).
    private val editingCardState = mutableStateOf<SavedCard?>(null)

    // Result of the most recent NFC read while on the Add screen.
    private val scannedCardState = mutableStateOf<CardData?>(null)

    // Card waiting to be shown as a bubble once overlay permission is granted.
    private var pendingBubbleCard: SavedCard? = null

    private val overlayPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            // Returning from the overlay-permission settings screen: try again.
            val card = pendingBubbleCard
            pendingBubbleCard = null
            if (card != null) {
                if (android.provider.Settings.canDrawOverlays(this)) {
                    startBubbleService(card)
                } else {
                    android.widget.Toast.makeText(
                        this,
                        "Permission not granted — can't show the floating bubble.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        repository = CardRepository(this)
        cardsState.value = repository.getCards()

        setContent {
            NFCWalletTheme {
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current

                var nfcState by remember { mutableStateOf(getNfcState()) }
                val screen by remember { screenState }
                val cards by remember { cardsState }
                val scanned by remember { scannedCardState }
                val editingCard by remember { editingCardState }

                // NFC reader mode is only active while the Add screen is visible.
                DisposableEffect(lifecycleOwner, screen) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                nfcState = getNfcState()
                                if (screen == Screen.ADD && nfcState == NfcState.ENABLED) {
                                    enableReaderMode()
                                }
                            }
                            Lifecycle.Event.ON_PAUSE -> disableReaderMode()
                            else -> {}
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)

                    // React immediately to screen changes too (not just lifecycle events).
                    if (screen == Screen.ADD && getNfcState() == NfcState.ENABLED) {
                        enableReaderMode()
                    } else {
                        disableReaderMode()
                    }

                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        disableReaderMode()
                    }
                }

                when (screen) {
                    Screen.HOME -> HomeScreen(
                        cards = cards,
                        onAddClick = {
                            editingCardState.value = null
                            scannedCardState.value = null
                            screenState.value = Screen.ADD
                        },
                        onEdit = { card ->
                            editingCardState.value = card
                            scannedCardState.value = null
                            screenState.value = Screen.ADD
                        },
                        onBubble = { card -> launchBubbleForCard(card) },
                        onDelete = { id ->
                            cardsState.value = repository.deleteCard(id)
                        }
                    )
                    Screen.ADD -> AddCardScreen(
                        nfcState = nfcState,
                        scanned = scanned,
                        editingCard = editingCard,
                        onEnableNfc = {
                            context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                        },
                        onBack = {
                            editingCardState.value = null
                            scannedCardState.value = null
                            screenState.value = Screen.HOME
                        },
                        onSave = { card ->
                            cardsState.value = if (editingCardState.value != null) {
                                repository.updateCard(card)
                            } else {
                                repository.addCard(card)
                            }
                            editingCardState.value = null
                            scannedCardState.value = null
                            screenState.value = Screen.HOME
                        }
                    )
                }
            }
        }
    }

    private fun getNfcState(): NfcState = when {
        nfcAdapter == null -> NfcState.NOT_SUPPORTED
        !nfcAdapter!!.isEnabled -> NfcState.DISABLED
        else -> NfcState.ENABLED
    }

    private fun enableReaderMode() {
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        nfcAdapter?.enableReaderMode(this, this, flags, null)
    }

    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }

    /**
     * Starts the floating bubble for [card]. If the app lacks the "display over
     * other apps" permission, sends the user to the system settings screen to
     * grant it, then starts the bubble on return.
     */
    private fun launchBubbleForCard(card: SavedCard) {
        if (android.provider.Settings.canDrawOverlays(this)) {
            startBubbleService(card)
        } else {
            pendingBubbleCard = card
            android.widget.Toast.makeText(
                this,
                "Allow \"Display over other apps\" to use the floating bubble.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startBubbleService(card: SavedCard) {
        val intent = Intent(this, BubbleOverlayService::class.java).apply {
            putExtra(BubbleOverlayService.EXTRA_CARD_ID, card.id)
        }
        startForegroundService(intent)
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) return
        // Only auto-fill when the Add screen is active.
        if (screenState.value != Screen.ADD) return
        val isoDep = IsoDep.get(tag) ?: return
        val data = EmvReader(isoDep).readCard()
        CoroutineScope(Dispatchers.Main).launch {
            scannedCardState.value = data
        }
    }
}

// --- Home screen ---------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    cards: List<SavedCard>,
    onAddClick: () -> Unit,
    onEdit: (SavedCard) -> Unit,
    onBubble: (SavedCard) -> Unit,
    onDelete: (String) -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("My Cards") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = "Add card")
            }
        }
    ) { innerPadding ->
        if (cards.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No cards yet.\nTap + to add one.",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(cards, key = { it.id }) { card ->
                    SavedCardItem(
                        card = card,
                        onEdit = { onEdit(card) },
                        onBubble = { onBubble(card) },
                        onDelete = { onDelete(card.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun SavedCardItem(
    card: SavedCard,
    onEdit: () -> Unit,
    onBubble: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Network logo as a large, faded background watermark.
            networkLogoRes(card.network)?.let { logoRes ->
                Image(
                    painter = painterResource(id = logoRes),
                    contentDescription = null, // decorative; the name is shown as text
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterEnd,
                    alpha = 0.4f,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = card.nickname.ifBlank { "Card" },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit card",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete card",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Text(
                text = card.formattedPan,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            if (!card.cardholderName.isNullOrBlank()) {
                Text(
                    text = card.cardholderName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = card.expiry?.let { "Exp $it" } ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = card.cvv?.let { "CVV $it" } ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

            // Launch the floating quick-copy bubble. Pinned to the bottom-right.
            IconButton(
                onClick = onBubble,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Launch floating bubble",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

// --- Add-card screen -----------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCardScreen(
    nfcState: NfcState,
    scanned: CardData?,
    editingCard: SavedCard?,
    onEnableNfc: () -> Unit,
    onBack: () -> Unit,
    onSave: (SavedCard) -> Unit
) {
    var nickname by remember { mutableStateOf(editingCard?.nickname ?: "") }
    var cardholderName by remember { mutableStateOf(editingCard?.cardholderName ?: "") }
    var pan by remember { mutableStateOf(editingCard?.pan ?: "") }
    var expiry by remember { mutableStateOf(editingCard?.expiry ?: "") }
    var cvv by remember { mutableStateOf(editingCard?.cvv ?: "") }
    var network by remember { mutableStateOf(editingCard?.network ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    // When a scan arrives, auto-fill the fields (only overwrite what the card provides).
    DisposableEffect(scanned) {
        if (scanned != null && scanned.error == null) {
            scanned.pan?.let { pan = it }
            scanned.expiry?.let { expiry = it }
            scanned.cardType?.let { network = it }
            scanned.cardholderName?.let { cardholderName = it }
            // Prefill a suggested nickname only if the user hasn't typed one.
            if (nickname.isBlank() && !scanned.pan.isNullOrBlank()) {
                nickname = defaultNickname(scanned.cardType, scanned.pan)
            }
        }
        onDispose { }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (editingCard != null) "Edit Card" else "Add Card") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // NFC status / prompt
            when (nfcState) {
                NfcState.ENABLED -> Text(
                    text = if (scanned?.error != null)
                        "Read failed: ${scanned.error}. Tap again or enter manually."
                    else
                        "Tap a card to auto-fill, or enter details manually.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (scanned?.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                NfcState.DISABLED -> {
                    Text("NFC is disabled. Enter details manually or enable NFC.")
                    OutlinedButton(onClick = onEnableNfc) { Text("Enable NFC") }
                }
                NfcState.NOT_SUPPORTED -> Text(
                    "NFC not supported. Enter details manually.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it; error = null },
                label = { Text("Nickname (auto: Network - last 4)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            OutlinedTextField(
                value = cardholderName,
                onValueChange = { cardholderName = it; error = null },
                label = { Text("Name on Card (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            OutlinedTextField(
                value = pan,
                onValueChange = { input -> pan = input.filter { it.isDigit() }.take(19); error = null },
                label = { Text("Card number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
            )
            OutlinedTextField(
                value = expiry,
                onValueChange = { expiry = it.take(5); error = null },
                label = { Text("Expiry (MM/YY)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            OutlinedTextField(
                value = cvv,
                onValueChange = { input -> cvv = input.filter { it.isDigit() }.take(4); error = null },
                label = { Text("CVV (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
            )
            OutlinedTextField(
                value = network,
                onValueChange = { network = it; error = null },
                label = { Text("Network (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (error != null) {
                Text(text = error!!, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val validationError = validateCard(nickname, pan, expiry, cvv)
                    if (validationError != null) {
                        error = validationError
                    } else {
                        onSave(
                            SavedCard(
                                id = editingCard?.id ?: java.util.UUID.randomUUID().toString(),
                                nickname = nickname.trim().ifBlank { defaultNickname(network, pan) },
                                cardholderName = cardholderName.trim().ifBlank { null },
                                pan = pan.filter { it.isDigit() },
                                expiry = expiry.trim().ifBlank { null },
                                cvv = cvv.filter { it.isDigit() }.ifBlank { null },
                                network = network.trim().ifBlank { null }
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    }
}

/**
 * Maps a card network name to its logo drawable, or null when there's no match
 * (e.g. unknown/blank network). Matching is case-insensitive and tolerant of
 * the various strings the EMV reader and manual entry can produce.
 */
fun networkLogoRes(network: String?): Int? {
    val n = network?.lowercase()?.trim() ?: return null
    return when {
        n.contains("visa") -> R.drawable.ic_network_visa
        n.contains("mastercard") || n.contains("master card") -> R.drawable.ic_network_mastercard
        n.contains("amex") || n.contains("american express") -> R.drawable.ic_network_amex
        n.contains("discover") || n.contains("diners") -> R.drawable.ic_network_discover
        n.contains("rupay") -> R.drawable.ic_network_rupay
        else -> null
    }
}

/**
 * Builds the default nickname in "Network - last4" format, e.g. "Visa - 7890".
 * Falls back to "Card" when the network is unknown, and omits the suffix when
 * fewer than 4 digits are available. Pure function for unit testing.
 */
fun defaultNickname(network: String?, pan: String): String {
    val name = network?.trim().takeUnless { it.isNullOrBlank() } ?: "Card"
    val digits = pan.filter { it.isDigit() }
    return if (digits.length >= 4) "$name - ${digits.takeLast(4)}" else name
}

/**
 * Validates manual card entry. Returns an error message, or null if valid.
 * Kept as a top-level pure function so it can be unit-tested off-device.
 * Nickname may be blank — a default is generated on save.
 */
fun validateCard(nickname: String, pan: String, expiry: String, cvv: String): String? {
    val digits = pan.filter { it.isDigit() }
    val cvvDigits = cvv.filter { it.isDigit() }
    return when {
        digits.length < 12 || digits.length > 19 -> "Card number must be 12–19 digits."
        expiry.isNotBlank() && !Regex("""^\d{2}/\d{2}$""").matches(expiry.trim()) ->
            "Expiry must be in MM/YY format."
        cvv.isNotBlank() && (cvvDigits.length < 3 || cvvDigits.length > 4) ->
            "CVV must be 3 or 4 digits."
        else -> null
    }
}
