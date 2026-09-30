# NFCWallet

An Android app that reads contactless (EMV) payment cards over NFC, lets you save
card details on the device, displays them in a rolodex-style carousel, and offers
a floating "quick-copy" bubble that hovers over other apps so you can copy a saved
field into any app with a tap.

---

## ⚠️ SECURITY WARNING — READ THIS FIRST

> ### This is, by design, one of the most insecure ways imaginable to store payment card information. Do not use it with real cards you care about. Do not distribute it. Do not put it on a device you don't fully control.

This app deliberately does things that the payment industry explicitly forbids
and that every security guideline tells you never to do:

- **It stores the full card number (PAN).** Persisted on the device.
- **It stores the CVV / card verification value.** Storing the CVV after
  authorization is **flatly prohibited by PCI-DSS** (Requirement 3.2). No compliant
  system ever does this. This app does it anyway, on purpose, because the owner
  asked for a personal build.
- **It displays the full, unmasked card number** on screen — no `•••• 1234`
  masking. Anyone who glances at your screen sees the whole number.
- **It shows the CVV in plain text** on the card and in the floating bubble.
- **It renders a floating overlay over other apps** and copies card fields to the
  **system clipboard**, where other apps can read them.

The only thing standing between this data and anyone with the device is a single
Android Keystore key (see "How it's stored" below). That is **not** a substitute
for the layered protections a real payment app requires. If the device is rooted,
lost, stolen, backed up somewhere, or running hostile software, the card data is
effectively exposed.

**Why it was built this way:** every one of these behaviors was requested
explicitly for a personal, non-distributed, learning/utility build, and each was
implemented only after the security implications were flagged and acknowledged.
This README exists partly so those risks are never forgotten.

**Bottom line:** treat this as a toy / personal experiment. Never ship it, never
publish it to any store, and never use it with cards whose compromise would matter.

---

## What the app does

### Read a card over NFC
- Uses `NfcAdapter` reader mode and talks to the card via `IsoDep`.
- Selects the PPSE, parses the AID, issues GPO, reads records, and parses the
  TLV structures to extract:
  - **PAN** (from Track 2 equivalent data tag `57`/`9F6B`, or tag `5A`)
  - **Expiry** (from Track 2, or tag `5F24`), formatted `MM/YY`
  - **Card network** (Visa / Mastercard / American Express / Discover / Diners /
    RuPay) inferred from the AID
  - **Cardholder name** (EMV tag `5F20`) when the card exposes it (many
    contactless cards omit it for privacy)

### Save cards on the device
- Each saved card holds: a nickname, optional name-on-card, PAN, expiry, CVV, and
  network.
- The **nickname auto-defaults** to `Network - last4` (e.g. `Visa - 7890`) when
  left blank, filled in on scan or at save time.
- Add cards two ways: **tap a physical card** (NFC auto-fills the form) or **type
  the details manually**. Manual entry is validated (PAN 12–19 digits, expiry
  `MM/YY`, CVV 3–4 digits).

### Browse cards as a rolodex carousel
- The home screen is a **vertical `VerticalPager`** styled like a rolodex:
  cards stack behind one another with 3D tilt, scale, fade, and z-ordering.
  Scrolling up/down rotates through them; the next card peeks from behind the
  active one.
- Each card shows the network **logo as a large faded background watermark**
  (the network name is not shown as text — the logo conveys it).
- Per-card actions: 🚀 (launch floating bubble), ✏️ edit, 🗑️ delete.

### Floating quick-copy bubble
- Tapping 🚀 starts a **foreground service** (`BubbleOverlayService`) that draws a
  draggable 🚀 bubble over all apps (requires the "Display over other apps"
  permission; the app routes you to the settings screen if it isn't granted).
- Tapping the bubble pops out a small card **anchored to the bubble** listing the
  card's fields (Name, Number, Expiry, CVV).
- Tapping a field **copies it to the clipboard** (marked sensitive on Android 13+)
  and shows a toast. You then paste it manually into the target app.
- ✕ collapses the pop-out but keeps the bubble floating. **Drag the bubble to the
  light-red region at the bottom** of the screen to dismiss it.

> **Note on "paste into other apps":** the app intentionally does **not** inject
> text directly into other apps. Cross-app text injection on Android requires an
> accessibility service and is the signature behavior of overlay malware, so it
> was deliberately not built. Clipboard copy + manual paste is used instead.

---

## How card data is stored ("How it's stored")

- Cards are serialized to JSON and persisted in a private `SharedPreferences` file.
- The JSON blob is **encrypted with AES-256-GCM** using a key generated in and
  never exported from the **Android Keystore** (`AndroidKeyStore`, alias
  `nfc_wallet_card_key`). Each write uses a fresh random IV; the format is
  `[ivLen][iv][ciphertext]`, Base64-encoded.
- Decryption failures fail closed (return an empty list) and never surface crypto
  internals to the UI.

This is the *one* thing done right — but as the warning above stresses, encrypting
data you should never have stored in the first place (the CVV, the full PAN) does
not make storing it safe.

---

## Architecture

| File | Responsibility |
|------|----------------|
| `MainActivity.kt` | Compose UI: home carousel, add/edit screen, NFC reader-mode wiring, overlay-permission flow, network-logo mapping, nickname/validation helpers |
| `EmvReader.kt` | EMV/contactless read flow over `IsoDep`; produces `CardData` |
| `TlvParser.kt` | BER-TLV parser + hex helpers |
| `SavedCard.kt` | Persisted card model; JSON (de)serialization; `formattedPan` |
| `CardRepository.kt` | Keystore AES-256-GCM encryption + `SharedPreferences` persistence (get/add/update/delete) |
| `BubbleOverlayService.kt` | Foreground service: draggable overlay bubble, anchored pop-out card, tap-to-copy, drag-to-dismiss |
| `NfcState.kt` | NFC availability enum (NOT_SUPPORTED / DISABLED / ENABLED) |
| `ui/theme/*` | Fixed neutral theme (no Material You / dynamic color): grey-on-black in dark, black-on-white in light |

### Network logos
Visa, Mastercard, Amex, and Discover marks are converted from
[`aaronfagan/svg-credit-card-payment-icons`](https://github.com/aaronfagan/svg-credit-card-payment-icons)
(**Apache License 2.0** — full text retained at
`app/src/main/res/raw/payment_icons_license.txt`). The RuPay mark is converted from
a public-domain Wikimedia Commons SVG.

---

## Build & run

Requirements: Android Studio / Android SDK, JDK 11+, a device or emulator with
NFC for the card-reading feature.

```bash
# Build a debug APK
./gradlew :app:assembleDebug

# Run unit tests
./gradlew :app:testDebugUnitTest
```

- **Namespace / applicationId:** `io.github.arumoy.nfcwallet`
- **minSdk:** 31 · **targetSdk / compileSdk:** 37
- **UI:** Jetpack Compose (Material 3)

### Permissions used
| Permission | Why |
|------------|-----|
| `NFC` | Read contactless cards |
| `SYSTEM_ALERT_WINDOW` | Draw the floating bubble over other apps (user-granted in Settings) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the bubble alive |
| `POST_NOTIFICATIONS` | Foreground-service notification |

---

## Tests

Pure, off-device logic is unit-tested (`app/src/test/...`):
- `SavedCard` masking/`formattedPan` and JSON round-trip (including null fields and
  CVV/cardholder persistence)
- `validateCard` rules (PAN length, expiry format, CVV length, optional fields)
- `defaultNickname` (`Network - last4`, fallbacks)

UI, NFC hardware interaction, Keystore encrypt/decrypt at runtime, and the overlay
bubble are **not** covered by automated tests and should be verified on a device.

---

## Disclaimer

This project is a personal experiment. It is not affiliated with, endorsed by, or
representative of Visa, Mastercard, American Express, Discover, Diners Club, RuPay,
or any card network or bank. Payment-network names and marks are the trademarks of
their respective owners. **Use at your own risk — and, per the warning above,
preferably don't use it with real cards at all.**
