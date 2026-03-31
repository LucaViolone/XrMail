# XR Email — Master Cursor Prompt
## AI-Native Spatial Email Client for Samsung Galaxy XR (Android XR)

> **Target Device:** Samsung Galaxy XR Headset (Snapdragon XR2+ Gen 2, 4K Micro-OLED per eye, 109° FoV, eye/hand/voice tracking)
> **Platform:** Android XR (Developer Preview 3+)
> **Primary SDK:** Jetpack XR SDK — Jetpack Compose for XR, Material Design for XR, Jetpack SceneCore, ARCore for Jetpack XR
> **Glasses variant:** Jetpack Compose Glimmer (for AI Glasses with additive/transparent displays **only**)
> **Language:** Kotlin · **Min SDK:** 24 · **Compile SDK:** 36+

---

## 1 — PRODUCT VISION

Build an AI-native spatial email client that eliminates inbox drudgery. The app reads, triages, drafts, and organizes email **proactively** — the user stays in flow while email works around them. On Galaxy XR, email becomes ambient, glanceable, and voice-first. Screens float in the user's space. Hands, eyes, and voice are the only inputs.

### Core Design Principles (from the planning doc)

1. **Emails I want are read to me** — AI auto-classifies every incoming message as *ignore*, *summary*, or *full read*. Summaries are spoken; full reads can be displayed or narrated.
2. **It responds for me** — AI drafts context-aware replies. Before sending it checks with the user in the way they prefer: audio summary for low-stakes, full visual display + spoken brief for high-stakes.
3. **Perfectly organized** — Spatial indexing lets the user voice-search contacts, threads, labels. Long conversation chains get contextual overlays showing key decisions, action items, and attachments.
4. **Peripheral-vision-friendly** — Notifications and status live at the edge of the FoV. The user never needs to shift focus unless they choose to.
5. **Passively personalized** — Over time, the system learns tone, priority rules, and formatting preferences from natural voice corrections. No settings screens.

---

## 2 — HARDWARE CAPABILITIES (Samsung Galaxy XR)

Use these capabilities in the prototype. Reference the Android XR developer docs at `developer.android.com/develop/xr`.

| Capability | Details | API / Extension |
|---|---|---|
| **Eye Tracking** | 4 interior cameras, coarse + fine gaze, pupil position, gaze direction. **Privacy: the system renders hover effects — raw gaze data is NOT shared with apps.** Custom gaze hit-testing against Compose composables is not possible from Kotlin. For attention-awareness (e.g. pause TTS when user looks away), use ARCore Face Tracking blendshapes. | `XR_ANDROID_eye_tracking`, `XR_EXT_eye_gaze_interaction`, `android.permission.EYE_TRACKING_COARSE` (system-rendered hover only) |
| **Hand Tracking** | 6 world-facing cameras, full hand skeleton with joint poses, pinch/poke/aim/grip gestures. **System navigation owns the primary hand.** All custom gestures must use `Hand.getPrimaryHandSide()` and bind to the **opposite (secondary) hand only.** | `XR_EXT_hand_tracking`, `XR_EXT_hand_interaction`, ARCore `Hand.left(session)` / `Hand.right(session)`, `android.permission.HAND_TRACKING` |
| **Voice Input** | 6-mic beamforming array, noise cancellation. Gemini Live API for conversational commands with bidirectional audio + function calling. System dictation via voice-to-text. | Gemini Live API, `android.permission.RECORD_AUDIO` |
| **Passthrough** | 2× 6.5MP stereoscopic cameras, 12ms latency, mixed reality overlay. | Default in Home Space; toggle with touchpad double-tap. |
| **Display** | 3552×3840 per eye Micro-OLED, 109° H / 100° V FoV, 72 Hz default / 90 Hz max, 96% DCI-P3. | Compose for XR uses dp-to-dmm (0.868 dp/dmm). |
| **Spatial Audio** | 2× 2-way speakers (woofer + tweeter), Dolby Atmos. | Android `AudioManager`, spatial audio APIs. |
| **Touchpad** | Right-temple capacitive strip. Tap, swipe, long-press. Touch-and-hold = Gemini. | Standard Android input events. |
| **Tilt** | Device tilt detection for scroll and navigation. No permission needed. | `TiltGesture.observe(session)` (alpha10+) |

### Input Hierarchy (design for all, don't depend on just one)
```
1. Voice  → Primary for commands, search, dictation, confirmations (Gemini Live API)
2. Gaze   → System-rendered hover highlighting (platform handles this automatically)
3. Pinch  → Explicit selection / confirm (gaze + pinch = click, secondary hand only for custom)
4. Tilt   → Device tilt for scrolling email lists (TiltGesture API)
5. Swipe  → Scroll, dismiss, navigate (hand gesture or touchpad, secondary hand for custom)
6. Touchpad → Fallback physical input for precision
```

### ⚠️ Eye Tracking Architecture Note

You **cannot** do custom gaze hit-testing against Compose composables from Kotlin. The `EYE_TRACKING_FINE` permission provides system-rendered hover effects only — raw gaze coordinates are not exposed to apps. For attention-aware features (e.g. pause TTS when user looks away), use **ARCore Face Tracking blendshapes**:

```kotlin
// Attention-aware TTS pause via face tracking blendshapes
session.configure(session.config.copy(faceTracking = FaceTrackingMode.BLEND_SHAPES))
Face.getUserFace(session)?.state?.collect { state ->
    val eyesClosed = state.blendShapes[FACE_BLEND_SHAPE_TYPE_EYES_CLOSED_L] ?: 0f
    if (eyesClosed > 0.8f) ttsManager.pause()
}
```

### ⚠️ Hand Tracking Architecture Note

System navigation owns the **primary hand**. All custom gestures must detect the primary hand side and bind to the **opposite hand only**:

```kotlin
val primarySide = Hand.getPrimaryHandSide(session)
val gestureHand = if (primarySide == HandSide.RIGHT) Hand.left(session) else Hand.right(session)
// Bind all custom gestures to gestureHand
```

---

## 3 — ANDROID XR SDK ARCHITECTURE

### 3.1 Dependencies
```kotlin
// build.gradle.kts
dependencies {
    // Core XR
    implementation("androidx.xr.runtime:runtime:1.0.0-alpha12")
    implementation("androidx.xr.scenecore:scenecore:1.0.0-alpha13")
    implementation("androidx.xr.compose:compose:1.0.0-alpha12")
    implementation("androidx.xr.compose.material3:material3:1.0.0-alpha16")
    implementation("androidx.xr.arcore:arcore:1.0.0-alpha12")

    // Glimmer (for AI Glasses variant ONLY — additive/transparent displays)
    // Do NOT use on headset — use standard SpatialPanel + Material3 for XR instead
    implementation("androidx.xr.compose.glimmer:glimmer:1.0.0-alpha01")

    // Standard Android
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")

    // AI / LLM — Firebase AI Logic (supersedes com.google.ai.client.generativeai)
    // Adds on-device Gemini Nano fallback + cloud Gemini in one API
    implementation("com.google.firebase:firebase-ai")

    // Email — Gmail REST API + FCM push (replaces IMAP IDLE)
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.apis:google-api-services-gmail:v1-rev20231218-2.0.0")
    implementation("com.google.firebase:firebase-messaging")

    // Notifications — Home Space fallback
    implementation("androidx.core:core-ktx:1.16.0")
}
```

### ⚠️ Glimmer vs Headset Display Mode

**Glimmer is exclusively for AI Glasses** (additive/transparent displays). On the Galaxy XR headset, use standard `SpatialPanel` + Material3 for XR. Use a `DisplayBlendMode` check for a single APK that covers both:

```kotlin
val displayMode = XrDevice.getCurrentDevice(session).getPreferredDisplayBlendMode()
if (displayMode == DisplayBlendMode.ADDITIVE) {
    // AI Glasses — use Glimmer components
    GlimmerEmailList(emails)
} else {
    // Headset — use standard spatial panels
    SpatialEmailLayout(emails)
}
```

### 3.2 Manifest Requirements
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <property
            android:name="android.window.PROPERTY_XR_ACTIVITY_START_MODE"
            android:value="XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED" />

        <!-- Permissions -->
        <uses-permission android:name="android.permission.HAND_TRACKING" />
        <uses-permission android:name="android.permission.EYE_TRACKING_COARSE" />
        <uses-permission android:name="android.permission.RECORD_AUDIO" />
        <uses-permission android:name="android.permission.INTERNET" />
        <uses-permission android:name="android.permission.SCENE_UNDERSTANDING" />
        <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

        <!-- Hardware features (all optional for 2D fallback) -->
        <uses-feature android:name="android.hardware.xr.input.hand_tracking" android:required="false" />
        <uses-feature android:name="android.hardware.xr.input.eye_tracking" android:required="false" />
        <uses-feature android:name="android.software.xr.api.spatial" android:required="false" />

        <activity
            android:name=".MainActivity"
            android:enableOnBackInvokedCallback="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- FCM push for Gmail notifications -->
        <service
            android:name=".push.EmailXRMessagingService"
            android:exported="false">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

### 3.3 Core Compose for XR Concepts

**Spatial Panels** — The fundamental building block. Each panel is a floating 2D surface in 3D space.
```kotlin
Subspace {
    SpatialPanel(
        SubspaceModifier
            .height(824.dp)
            .width(1400.dp),
        dragPolicy = MovePolicy(),
        resizePolicy = ResizePolicy(),
    ) {
        InboxScreen()
    }
}
```

**Orbiters** — Floating toolbars/controls attached to panel edges.
```kotlin
Subspace {
    SpatialPanel(SubspaceModifier.width(1200.dp).height(800.dp)) {
        EmailReadingView()
    }
    Orbiter(
        position = OrbiterEdge.Bottom,
        offset = EdgeOffset.inner(20.dp),
    ) {
        QuickActionBar()
    }
}
```

**Spatial Layouts** — Arrange multiple panels in space.
```kotlin
SpatialRow(
    SubspaceModifier,
    curveRadius = 1200.dp,
) {
    SpatialPanel(SubspaceModifier.width(400.dp).height(800.dp)) {
        EmailListPanel()
    }
    SpatialPanel(SubspaceModifier.width(800.dp).height(800.dp)) {
        EmailDetailPanel()
    }
    SpatialPanel(SubspaceModifier.width(400.dp).height(800.dp)) {
        ContextPanel()
    }
}
```

**Spatial Elevation** — Float important elements above the panel.
```kotlin
SpatialPanel(
    SubspaceModifier
        .width(360.dp)
        .height(120.dp)
        .offset(z = 40.dp),
) {
    NotificationCard(email)
}
```

**Persistent Spatial Anchors** — Store panel positions across sessions using Room + Anchor UUIDs.
```kotlin
// Save anchor when user repositions a panel
val anchor = session.createAnchor(panelPose)
anchorDao.insert(AnchorEntity(threadId = email.threadId, anchorUuid = anchor.uuid.toString()))

// Restore on relaunch
val savedUuid = anchorDao.getByThread(threadId)?.anchorUuid
if (savedUuid != null) {
    val anchor = Anchor.load(session, UUID.fromString(savedUuid))
    spatialPanel.setAnchor(anchor)
}
```

---

## 4 — INFORMATION ARCHITECTURE

```
XR Email App
├── 🏠 Home Space (Ambient Mode)
│   ├── Standard NotificationCompat notifications (spatial panels NOT available here)
│   ├── Inbox Badge via standard notification channels
│   └── Voice: "Hey Gemini, any important emails?"
│
├── 📬 Inbox View (Full Space)
│   ├── Left Panel: Smart Inbox
│   │   ├── Priority Stream (AI-sorted, top = most important)
│   │   ├── Categories: People / Updates / Promotions / Newsletters
│   │   ├── TiltGesture scroll (device tilt to scroll list)
│   │   └── Voice: "Show me emails from Dr. Chen"
│   ├── Center Panel: Email Reader
│   │   ├── Rendered email body
│   │   ├── AI Summary card (collapsible)
│   │   ├── Thread Context Overlay (for long chains)
│   │   └── TTS controls (play, pause, speed) — attention-aware via face blendshapes
│   ├── Right Panel: Context Sidebar
│   │   ├── Contact card
│   │   ├── Related emails / threads
│   │   ├── Attachments gallery
│   │   └── AI-extracted action items
│   └── Bottom Orbiter: Quick Actions
│       ├── Reply (opens compose)
│       ├── Archive / Delete
│       ├── Snooze (voice: "remind me tomorrow at 9")
│       └── Forward
│
├── ✍️ Compose / Reply (Spatial Panel)
│   ├── Draft Panel (center, elevated)
│   │   ├── To / CC / Subject fields
│   │   ├── Body editor (Gemini Live API voice dictation primary)
│   │   ├── AI draft indicator ("AI wrote this — review before sending")
│   │   └── Formatting toolbar (orbiter)
│   ├── Context Panel (side)
│   │   ├── Original thread for reference
│   │   └── Suggested responses (AI-generated via Firebase AI)
│   └── Confirmation Flow
│       ├── Low-stakes: Audio summary → voice "send" / "edit"
│       ├── High-stakes: Full visual display + read-aloud + voice confirm
│       └── Voice: "Read it back to me" / "Sounds good, send it"
│
├── 🔍 Search (Overlay)
│   ├── Voice-activated: "Find the research paper submission emails"
│   ├── Results as scrollable cards in spatial layout
│   └── Gaze (system hover) + pinch to select
│
├── 🔔 Notification System (Dual-Path)
│   ├── Full Space: Peripheral pills (bottom-right of FoV, ~25° off-center)
│   │   ├── Grouped by: sender, thread, or category
│   │   ├── System gaze hover to expand → shows sender + subject + AI 1-line summary
│   │   ├── Pinch to open → jumps to full email
│   │   └── Voice: "Ignore" / "Read it" / "Reply with yes"
│   ├── Home Space: Standard NotificationCompat path (entirely separate)
│   │   ├── NotificationCompat.Builder with email content
│   │   ├── Notification channels per priority level
│   │   └── PendingIntent to launch Full Space on tap
│   └── FCM push triggers both paths based on current space
│
├── 🔄 Spatial Anchors (Persistent)
│   ├── Store Anchor.UUID per email thread in Room
│   ├── Reload panel positions on relaunch via Anchor.load(session, uuid)
│   └── User-repositioned panels remember their location
│
└── ⚙️ Personalization Engine (Passive)
    ├── Learns from voice corrections: "No, make it more formal"
    ├── Tracks which emails user reads vs. ignores
    ├── Builds priority model per-contact
    ├── Stores tone/style preferences for AI drafts
    └── All config through natural voice — no settings UI
```

---

## 5 — UI DESIGN SPECIFICATIONS

### 5.1 Display-Adaptive Design

**Headset (Galaxy XR):** Use standard `SpatialPanel` + Material3 for XR. Dark theme with near-black surfaces for OLED efficiency.

**AI Glasses (additive/transparent displays):** Use Glimmer components. Black = transparent on additive displays. Desaturated colors, minimal lit pixels.

```kotlin
@Composable
fun AdaptiveEmailApp(session: Session) {
    val displayMode = XrDevice.getCurrentDevice(session).getPreferredDisplayBlendMode()
    when (displayMode) {
        DisplayBlendMode.ADDITIVE -> {
            // AI Glasses — Glimmer design language
            GlimmerEmailApp()
        }
        else -> {
            // Headset — standard spatial panels + Material3 for XR
            SpatialEmailLayout()
        }
    }
}
```

**Glimmer rules (glasses only):**
- **Dark surfaces, bright content.** Black = transparent on additive displays. Use near-black (< 10% luminance) as container backgrounds, white/light text on top.
- **Desaturated color palette.** Saturated colors vanish on real-world backgrounds. Use pastels shifted toward white. Reserve color for interactive elements only.
- **Rounded corners everywhere.** Sharp corners create visual "pockets" that trap the eye. Use `24.dp`+ corner radii.
- **Google Sans Flex** for text. Variable font with optical size axis for legibility at distance. Min readable: 0.6° visual angle. Use 14dp+ font size, normal+ weight.
- **Shadows for depth hierarchy.** Dark, rich shadows replace Material elevation.
- **2-second notification transitions.** Circle (avatar) → expands to pill → reveals content.
- **Green is cheapest** (power). Blue is most expensive. Minimize lit pixels overall.

**Headset rules:**
- Standard Material3 for XR dark theme — no additive-display constraints
- Full color palette available (OLED, not additive)
- Spatial panels with drag/resize policies
- System-rendered gaze hover highlighting (automatic, no custom code needed)

### 5.2 Spatial Layout Rules

```
┌─────────────────────────────────────────────────────────┐
│                    USER'S FIELD OF VIEW                  │
│                        (109° H)                         │
│                                                         │
│  ┌─ Peripheral ─┐  ┌── Comfortable ──┐  ┌─ Peripheral ─┐│
│  │  >30° off    │  │  ±30° of center │  │  >30° off    ││
│  │  Notifications│  │  Main content   │  │  Context     ││
│  │  Status pills │  │  Email body     │  │  AI sidebar  ││
│  │  Ambient info │  │  Compose panel  │  │  Attachments ││
│  └──────────────┘  └────────────────┘  └──────────────┘│
│                                                         │
│  Bottom Orbiter: Quick Actions bar (archive/reply/etc.) │
└─────────────────────────────────────────────────────────┘
```

- **Primary content:** Within ±30° of center (comfortable gaze zone).
- **Secondary/contextual content:** 30°–50° off-center (glanceable without strain).
- **Notifications (Full Space):** Peripheral zone, bottom-right. System gaze hover to expand.
- **Notifications (Home Space):** Standard Android notification tray (NotificationCompat).
- **Never place content beyond 50°** — physically impossible for most users.
- **Vertical sweet spot:** 40° area slightly above horizon line.
- **Panel default distance:** ~1.5m from user (arm's length + buffer). Use `dp-to-dmm` scaling (0.868).
- **Persistent anchors:** Panels remember user-repositioned locations across sessions.

### 5.3 Color System

```kotlin
object XREmailTheme {
    // Container colors (dark — OLED efficient on headset, transparent on glasses)
    val surface = Color(0xFF0A0A0F)
    val surfaceVariant = Color(0xFF1A1A24)
    val surfaceElevated = Color(0xFF252535)

    // Content colors (bright — high contrast on dark surfaces)
    val onSurface = Color(0xFFE8E8EE)
    val onSurfaceVariant = Color(0xFFA0A0B0)
    val onSurfaceDim = Color(0xFF6A6A7A)

    // Accent colors (desaturated — visible on any real-world background)
    val primary = Color(0xFF7EB8D8)    // Soft blue — interactive
    val secondary = Color(0xFF8BD4A0)  // Soft green — success/sent
    val tertiary = Color(0xFFD4A88B)   // Warm coral — urgent/important
    val error = Color(0xFFD88B8B)      // Soft red — errors
    val aiAccent = Color(0xFFB8A0D8)   // Soft purple — AI-generated content

    // Notification priority
    val priorityHigh = Color(0xFFD4A88B)
    val priorityMedium = Color(0xFF7EB8D8)
    val priorityLow = Color(0xFF6A6A7A)
}
```

### 5.4 Typography

```kotlin
object XREmailTypography {
    // Use Google Sans / Google Sans Flex for Glimmer compat (glasses)
    // Standard Material3 typography on headset
    // Fallback: system sans-serif with increased weight

    val displayLarge = TextStyle(
        fontSize = 28.sp,      // ~1.2° visual angle at 1m
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp
    )
    val headlineMedium = TextStyle(
        fontSize = 22.sp,      // ~0.95° visual angle
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp
    )
    val bodyLarge = TextStyle(
        fontSize = 18.sp,      // ~0.78° visual angle
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.2.sp
    )
    val bodyMedium = TextStyle(
        fontSize = 16.sp,      // ~0.69° visual angle
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.15.sp
    )
    val labelMedium = TextStyle(
        fontSize = 14.sp,      // ~0.6° — minimum readable
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp
    )
}
```

---

## 6 — KEY SCREEN DESIGNS

### 6.1 — Inbox View (Three-Panel Spatial Layout)

```
┌──────────────┐  ┌────────────────────────┐  ┌──────────────┐
│  SMART INBOX │  │     EMAIL READER       │  │  CONTEXT     │
│              │  │                        │  │              │
│ ▸ Priority   │  │  From: Dr. Chen        │  │ 📇 Contact   │
│   ┌────────┐ │  │  Sub: Paper revision   │  │   Dr. Chen   │
│   │ ★ Dr.C │ │  │  ───────────────────   │  │   Prof, MIT  │
│   │  Paper  │ │  │                        │  │              │
│   └────────┘ │  │  🤖 AI Summary:        │  │ 📎 Attach.   │
│   ┌────────┐ │  │  "Requests changes to  │  │   paper_v3   │
│   │  Team  │ │  │   methodology section  │  │   reviews.pdf│
│   │  mtg   │ │  │   by Friday. Suggests  │  │              │
│   └────────┘ │  │   adding control grp." │  │ ✅ Actions   │
│              │  │                        │  │  □ Revise §3 │
│ ▸ Updates    │  │  [Full email body...]  │  │  □ Reply Fri │
│ ▸ Promos     │  │                        │  │  □ Attach v4 │
│              │  │                        │  │              │
│ 🔍 Search... │  │                        │  │ 🔗 Related   │
│              │  │                        │  │  Thread (12) │
│ ↕ Tilt to   │  │                        │  │              │
│   scroll     │  │                        │  │ 📌 Anchored  │
└──────────────┘  └────────────────────────┘  └──────────────┘
                  ┌────────────────────────┐
                  │ ↩ Reply  📦 Archive  ⏰ Snooze  ➡ Fwd │
                  └────────────────────────┘  ← Bottom Orbiter
```

**Interactions:**
- **System gaze hover on inbox item** → automatic highlight (platform-rendered, no custom code)
- **Pinch (secondary hand)** → select and display in center panel
- **Tilt device down** → scroll inbox list (TiltGesture API)
- **Voice: "Summarize this thread"** → AI summary card expands (Gemini Live API)
- **Voice: "Read it to me"** → TTS begins, attention-aware pause via face blendshapes
- **Swipe left on inbox item (secondary hand)** → archive
- **Swipe right (secondary hand)** → snooze (voice prompt: "When?")

### 6.2 — Compose / Reply (Elevated Draft Panel)

```
                    ┌────────────────────────┐
                    │    ✍️ COMPOSE          │  ← Elevated 15dp in Z
                    │                        │
                    │  To: [Dr. Chen ×]      │
                    │  Subject: Re: Paper... │
                    │  ─────────────────────  │
                    │                        │
                    │  🤖 AI Draft:          │
                    │  "Thank you for the    │
                    │  feedback. I'll revise  │
                    │  the methodology..."    │
                    │                        │
                    │  [AI confidence: 92%]  │
                    │  [ON_DEVICE / CLOUD]   │  ← Firebase AI inference source
                    │                        │
                    │  🎤 Dictate  ✏️ Edit   │  ← Gemini Live API (bidirectional audio)
                    └────────────────────────┘
        ┌──────────────┐              ┌──────────────┐
        │ ORIGINAL     │              │ SUGGESTIONS  │
        │ THREAD       │              │              │
        │ (reference)  │              │ Alt draft 1  │
        │              │              │ Alt draft 2  │
        │              │              │ "Make formal"│
        └──────────────┘              └──────────────┘
```

**Send Confirmation Flow:**
```
User says "Send it" (via Gemini Live API) →
  AI evaluates stakes:
    LOW (routine reply):
      → Audio: "Sending reply to Dr. Chen: confirms revision by Friday. Send?"
      → User: "Yes" → Sent ✓
    HIGH (new thread, external, contains sensitive data):
      → Visual: Full draft panel elevated + highlighted border
      → Audio: "This is a new thread to an external contact. Let me read it back..."
      → TTS reads full draft (pauses if user looks away — face blendshapes)
      → User: "Send" / "Wait, change the second paragraph..."
```

### 6.3 — Notification System (Dual-Path)

**Full Space notifications** (SpatialPanel + Orbiter — only works in Full Space):
```
                    ┌── Main content area ──┐
                    │                       │
                    │                       │
                    │                       │
                    │                       │
                    └───────────────────────┘
                                        ┌─────────────────┐
                                        │ 🔵 3 new emails │ ← Pill (collapsed)
                                        └─────────────────┘
                                               │
                                          [system gaze hover to expand]
                                               ▼
                                        ┌─────────────────────┐
                                        │ 📧 Dr. Chen         │
                                        │  "Paper revision    │
                                        │   deadline moved"   │
                                        │                     │
                                        │ 📧 Team Slack       │
                                        │  "Meeting notes..." │
                                        │                     │
                                        │ 📧 Newsletter       │
                                        │  [auto-archived]    │
                                        └─────────────────────┘
```

**Home Space notifications** (standard Android — required because spatial panels don't work here):
```kotlin
// Home Space fallback — standard NotificationCompat
class HomeSpaceNotifier(private val context: Context) {
    private val channelHigh = "email_high_priority"
    private val channelNormal = "email_normal"

    fun notify(email: ClassifiedEmail) {
        val channel = if (email.priority == Priority.HIGH) channelHigh else channelNormal
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_email)
            .setContentTitle(email.sender)
            .setContentText(email.aiSummary)
            .setContentIntent(createFullSpacePendingIntent(email))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(email.id.hashCode(), notification)
    }
}
```

**Notification Grouping Strategy:**
1. **By urgency:** High-priority float to top, low-priority auto-summarize
2. **By thread:** Multiple replies to same thread collapse into one notification
3. **By sender relationship:** Close contacts get audio alerts; unknown senders are silent
4. **Smart suppression:** If user is in Full Space composing, batch notifications until done

**Transition Animation (Full Space only):**
- Circle (badged avatar, 32dp) fades in over 500ms
- Expands to pill (avatar + subject line) over 1500ms
- Holds for 4 seconds
- Collapses back to dot or fades out over 1000ms
- Total lifecycle: ~7 seconds passive, indefinite if user gazes

### 6.4 — Thread Context Overlay (Long Conversations)

For email chains with 5+ messages, show a spatial timeline:

```
┌─ Thread: "Research Paper Submission" (12 messages over 3 weeks) ─┐
│                                                                   │
│  ●─────●─────────●───●───────────●─────●                         │
│  Oct 1  Oct 5    Oct 12 Oct 13   Oct 20 Oct 22                  │
│  Start  Draft    Review Review   Revised  Final                  │
│         shared   #1     #2       draft    submit                 │
│                                                                   │
│  🤖 AI Thread Summary (Firebase AI — on-device or cloud):        │
│  "Paper submitted Oct 22 after 2 rounds of review.               │
│   Key decisions: added control group (Oct 12), changed            │
│   statistical method (Oct 20). 3 attachments. No open items."    │
│                                                                   │
│  📎 paper_v1.pdf  paper_v2.pdf  paper_final.pdf                 │
│  ✅ All action items resolved                                    │
│  📌 Panel anchored at user's preferred position                  │
└───────────────────────────────────────────────────────────────────┘
```

### 6.5 — Peripheral Vision Utility

Design elements usable **without shifting focus** from the real world:

| Element | Location | What it shows | Interaction |
|---------|----------|---------------|-------------|
| Inbox badge | Top-right orbiter | Unread count as number | System gaze hover = expand to list |
| Priority alert | Bottom-right | Pulsing colored dot (coral = urgent) | Voice: "What's urgent?" |
| Send confirmation | Center-bottom | Brief flash: "✓ Sent to Dr. Chen" | None (auto-dismiss 3s) |
| TTS progress | Bottom-center | Thin progress bar during read-aloud | Voice: "Pause" / "Skip" — auto-pauses if eyes closed (blendshapes) |
| AI status | Top-left | Subtle animation when AI is processing | None (ambient indicator) |
| Inference source | Top-left | "ON_DEVICE" or "CLOUD" badge | None (informational) |

---

## 7 — AI ENGINE SPECIFICATION

### 7.1 Email Classification Model (Batch Processing)

**Use batch classification — one API call for ~20 emails. 95% cost reduction vs. per-email calls.**

```kotlin
// Batch classify 20 emails in a single Gemini call
val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
    modelName = "gemini-2.5-flash",
    onDeviceConfig = onDeviceConfig { mode = InferenceMode.PREFER_ON_DEVICE }
)

val batchPrompt = buildString {
    appendLine("Classify these ${emails.size} emails. Return JSON array.")
    emails.forEachIndexed { i, email ->
        appendLine("--- EMAIL $i ---")
        appendLine("From: ${email.sender}")
        appendLine("Subject: ${email.subject}")
        appendLine("Body: ${email.body.take(500)}")
    }
}

val response = model.generateContent(batchPrompt)
// response.inferenceSource == "ON_DEVICE" or "CLOUD"
```

```
Input: emails[] (headers + body + metadata, batched)
Output per email: {
    priority: "high" | "medium" | "low" | "ignore",
    action: "read_full" | "read_summary" | "auto_archive" | "needs_reply",
    summary: string (1-2 sentences),
    urgency_score: float (0-1),
    suggested_reply: string | null,
    reply_confidence: float (0-1),
    extracted_actions: ActionItem[],
    category: "people" | "updates" | "promotions" | "newsletters" | "transactional"
}
```

### 7.2 Firebase AI Logic (replaces generativeai SDK)

`com.google.ai.client.generativeai` is **superseded** by `com.google.firebase:firebase-ai` which adds on-device Gemini Nano fallback:

```kotlin
val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
    modelName = "gemini-2.5-flash",
    onDeviceConfig = onDeviceConfig { mode = InferenceMode.PREFER_ON_DEVICE }
)

val response = model.generateContent("Summarize this email thread...")
// response.inferenceSource tells you "ON_DEVICE" or "CLOUD"
// On-device = zero latency, zero cost, works offline
// Cloud = full capability, requires network
```

### 7.3 Personalization Signals (Passive Learning)

```kotlin
data class UserPreferences(
    // Learned from behavior
    val prioritySenders: Map<String, Float>,
    val readPatterns: Map<Category, ReadBehavior>,
    val replyTone: ToneProfile,
    val summaryPreference: SummaryLength,

    // Learned from voice corrections (via Gemini Live API)
    val toneAdjustments: List<ToneCorrection>,
    val signatureStyle: String?,
    val responseTimingPrefs: Map<String, Duration>,
)

enum class ReadBehavior { FULL_AUDIO, SUMMARY_AUDIO, SUMMARY_VISUAL, IGNORE }
enum class SummaryLength { ONE_LINE, PARAGRAPH, FULL }
```

### 7.4 Gemini Live API (replaces SpeechRecognizer)

The old `SpeechRecognizer` + manual intent parser approach is replaced with the **Gemini Live API** for conversational commands — bidirectional audio with built-in function calling:

```kotlin
val liveModel = Firebase.ai().liveModel(
    modelName = "gemini-2.5-flash-native-audio-preview-12-2025",
    tools = listOf(emailCommandTool),
    systemInstruction = content {
        text("Email assistant. Keep spoken responses under 10 words.")
    }
)

val session = liveModel.connect()
session.startAudioConversation(::handleEmailCommand)

// Function calling handles all voice commands naturally:
// "Archive this" → emailCommandTool.archive(currentEmailId)
// "Reply saying I'll be there" → emailCommandTool.draft(currentEmailId, "I'll be there")
// "What did Dr. Chen say about the deadline?" → emailCommandTool.search(...)
```

### 7.5 Voice Command Grammar

All voice commands are handled by Gemini Live API with function calling. The model understands natural language — these are examples, not a rigid grammar:

```
// Navigation
"Show me my inbox"
"Open emails from [contact]"
"Search for [query]"
"Go back" / "Close this"
"Show the thread" / "Show full conversation"

// Triage
"Read it to me" / "Summarize it"
"Archive this" / "Delete this"
"Snooze until [time]"
"Mark as important"
"Ignore emails like this"

// Compose
"Reply" / "Reply all" / "Forward to [contact]"
"New email to [contact]"
"Say: [dictated content]"
"Read it back to me"
"Make it more [formal/casual/brief/detailed]"
"Send it" / "Hold on" / "Save as draft"

// AI
"What's the summary?"
"What are my action items?"
"Draft a response"
"What did [contact] say about [topic]?"

// Personalization (passive — Gemini learns from corrections)
"I always want to see emails from [contact]"
"Never show me [type] emails"
"Shorter summaries" / "More detail"
```

---

## 8 — TECHNICAL IMPLEMENTATION PLAN

### 8.1 Project Structure
```
com.xremail.app/
├── MainActivity.kt                 // XR entry point
├── ui/
│   ├── theme/
│   │   ├── XREmailTheme.kt        // Colors, typography, shapes
│   │   └── SpatialTokens.kt       // dp-to-dmm conversions, spatial constants
│   ├── inbox/
│   │   ├── InboxScreen.kt         // Smart inbox list panel
│   │   ├── EmailCard.kt           // Individual email list item
│   │   └── CategoryTabs.kt        // Priority / People / Updates / Promos
│   ├── reader/
│   │   ├── EmailReaderScreen.kt   // Full email display panel
│   │   ├── AISummaryCard.kt       // Collapsible AI summary
│   │   ├── ThreadTimeline.kt      // Contextual overlay for long threads
│   │   └── TTSControls.kt         // Text-to-speech playback UI
│   ├── compose/
│   │   ├── ComposeScreen.kt       // Draft composition panel
│   │   ├── AIDraftView.kt         // AI-generated draft with edit controls
│   │   └── SendConfirmation.kt    // Multi-modal send check flow
│   ├── context/
│   │   ├── ContextSidebar.kt      // Contact card, attachments, actions
│   │   ├── ContactCard.kt         // Rich contact info
│   │   └── ActionItemsList.kt     // AI-extracted to-dos
│   ├── notifications/
│   │   ├── NotificationPill.kt    // Full Space peripheral notification component
│   │   ├── NotificationStack.kt   // Grouped notification list (Full Space)
│   │   ├── NotificationAnimations.kt // Transition animations (Full Space)
│   │   └── HomeSpaceNotifier.kt   // Standard NotificationCompat fallback
│   ├── search/
│   │   ├── SearchOverlay.kt       // Voice-activated search
│   │   └── SearchResults.kt       // Spatial result cards
│   ├── spatial/
│   │   ├── SpatialEmailLayout.kt  // Three-panel curved layout (headset)
│   │   ├── GlimmerEmailApp.kt     // Glimmer variant (glasses only)
│   │   ├── DisplayModeRouter.kt   // DisplayBlendMode check → headset vs glasses
│   │   ├── EmailOrbiters.kt       // Quick action orbiter bar
│   │   └── PeripheralWidgets.kt   // Badge, status, ambient indicators
│   └── anchors/
│       ├── AnchorManager.kt       // Persistent spatial anchor CRUD
│       └── AnchorEntity.kt        // Room entity for anchor UUIDs
├── ai/
│   ├── EmailClassifier.kt         // Batch classification via Firebase AI
│   ├── SummaryEngine.kt           // Email and thread summarization
│   ├── DraftGenerator.kt          // AI reply drafting
│   ├── PersonalizationEngine.kt   // Passive preference learning
│   └── InferenceSourceTracker.kt  // Track ON_DEVICE vs CLOUD usage
├── data/
│   ├── EmailRepository.kt         // Gmail REST API + local cache
│   ├── ContactRepository.kt       // Contact lookup and enrichment
│   ├── PreferencesStore.kt        // Learned user preferences (DataStore)
│   └── AnchorDao.kt               // Room DAO for spatial anchors
├── push/
│   ├── EmailXRMessagingService.kt // FCM push handler
│   └── EmailSyncWorker.kt         // WorkManager delta sync
├── voice/
│   ├── GeminiLiveManager.kt       // Gemini Live API session management
│   ├── EmailCommandTool.kt        // Function calling tool definitions
│   └── TTSManager.kt              // Text-to-speech (attention-aware via blendshapes)
└── tracking/
    ├── FaceAttentionTracker.kt    // ARCore face blendshapes for attention detection
    ├── SecondaryHandGestures.kt   // Custom gestures on non-primary hand only
    ├── TiltScrollController.kt    // TiltGesture API for list scrolling
    └── MultiModalInputRouter.kt   // Fuses gaze(system) + hand + voice + tilt input
```

### 8.2 Entry Point
```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            XREmailTheme {
                ApplicationSubspace {
                    XREmailApp()
                }
            }
        }
    }
}

@Composable
fun XREmailApp() {
    val viewModel: EmailViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SpatialRow(
        SubspaceModifier,
        curveRadius = 1200.dp,
    ) {
        // Left: Inbox list (with TiltGesture scroll support)
        SpatialPanel(
            SubspaceModifier.width(420.dp).height(860.dp)
        ) {
            InboxScreen(
                emails = uiState.emails,
                onEmailSelected = viewModel::selectEmail,
                onVoiceCommand = viewModel::handleVoice,
            )
        }

        // Center: Email reader or compose
        SpatialPanel(
            SubspaceModifier.width(840.dp).height(860.dp)
        ) {
            when (uiState.mode) {
                Mode.READING -> EmailReaderScreen(
                    email = uiState.selectedEmail,
                    summary = uiState.aiSummary,
                    onAction = viewModel::handleAction,
                )
                Mode.COMPOSING -> ComposeScreen(
                    draft = uiState.draft,
                    aiSuggestions = uiState.aiDrafts,
                    onSend = viewModel::sendWithConfirmation,
                )
            }
        }

        // Right: Context sidebar
        SpatialPanel(
            SubspaceModifier.width(380.dp).height(860.dp)
        ) {
            ContextSidebar(
                contact = uiState.selectedContact,
                attachments = uiState.attachments,
                actionItems = uiState.actionItems,
                relatedThreads = uiState.relatedThreads,
            )
        }
    }

    // Quick actions orbiter (attached to center panel)
    Orbiter(
        position = OrbiterEdge.Bottom,
        offset = EdgeOffset.outer(24.dp),
    ) {
        QuickActionBar(
            onReply = viewModel::startReply,
            onArchive = viewModel::archiveSelected,
            onSnooze = viewModel::snoozeSelected,
            onForward = viewModel::forwardSelected,
        )
    }

    // Full Space notification layer (not available in Home Space)
    NotificationLayer(
        notifications = uiState.pendingNotifications,
        onExpand = viewModel::expandNotification,
        onDismiss = viewModel::dismissNotification,
    )
}
```

### 8.3 Gmail REST API + FCM Push

Replaces IMAP IDLE — eliminates battery drain entirely:

```kotlin
// One-time server setup: POST gmail.users.watch → Pub/Sub → FCM
class EmailXRMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val historyId = message.data["historyId"]?.toLongOrNull() ?: return
        WorkManager.getInstance(this).enqueueUniqueWork(
            "sync_delta",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<EmailSyncWorker>()
                .setInputData(workDataOf("historyId" to historyId))
                .build()
        )
    }
}

class EmailSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val historyId = inputData.getLong("historyId", 0)
        val newEmails = gmailRepository.fetchDelta(historyId)

        // Batch classify all new emails in one Gemini call
        val classified = emailClassifier.batchClassify(newEmails)

        // Dual-path notification
        if (isInFullSpace()) {
            spatialNotifier.showPills(classified)
        } else {
            homeSpaceNotifier.notify(classified)
        }

        return Result.success()
    }
}
```

### 8.4 TiltGesture API for List Scrolling

```kotlin
// No permission needed — available since alpha10
TiltGesture.observe(session).collect { tilt ->
    when (tilt.state) {
        TiltState.DOWN -> emailListState.scrollBy(tilt.progress * 100f)
        TiltState.UP -> emailListState.scrollBy(-tilt.progress * 100f)
        TiltState.NEUTRAL -> { /* no-op */ }
    }
}
```

### 8.5 Persistent Spatial Anchors

```kotlin
// Room entity
@Entity(tableName = "spatial_anchors")
data class AnchorEntity(
    @PrimaryKey val threadId: String,
    val anchorUuid: String,
    val lastUpdated: Long = System.currentTimeMillis(),
)

// Save when user repositions a panel
fun saveAnchor(session: Session, threadId: String, pose: Pose) {
    val anchor = session.createAnchor(pose)
    anchorDao.upsert(AnchorEntity(threadId, anchor.uuid.toString()))
}

// Restore on relaunch
fun restoreAnchor(session: Session, threadId: String): Anchor? {
    val entity = anchorDao.getByThread(threadId) ?: return null
    return Anchor.load(session, UUID.fromString(entity.anchorUuid))
}
```

---

## 9 — PROTOTYPE PHASES

### Phase 1: Core Spatial Layout + Mock Data (Week 1-2) ✅ IN PROGRESS
- [x] Three-panel SpatialCurvedRow with curved layout
- [x] InboxScreen with static email list (mock data)
- [x] EmailReaderScreen with body + AI summary card
- [x] ContextSidebar with contact card + attachment list
- [x] QuickAction orbiter bar
- [x] XR Email theme (colors, typography, shapes)
- [ ] Test on Android XR Emulator
- [ ] DisplayBlendMode check for headset vs glasses routing

### Phase 2: Voice + Input (Week 3-4)
- [ ] Gemini Live API integration (replaces SpeechRecognizer)
- [ ] EmailCommandTool function definitions for voice commands
- [ ] System gaze hover (automatic — verify it works, no custom code)
- [ ] Secondary-hand-only custom gestures (pinch, swipe)
- [ ] TiltGesture API for inbox list scrolling
- [ ] TTSManager with attention-aware pause (face blendshapes)
- [ ] TTS playback controls UI

### Phase 3: AI Integration (Week 5-6)
- [ ] Firebase AI Logic integration (replaces generativeai SDK)
- [ ] Batch email classification (20 emails per API call)
- [ ] Email and thread summarization (on-device preferred)
- [ ] AI draft generation for replies
- [ ] Send confirmation flow (low-stakes audio vs. high-stakes visual)
- [ ] Action item extraction from emails
- [ ] Inference source tracking (ON_DEVICE vs CLOUD indicator)

### Phase 4: Email Backend + Notifications (Week 7-8)
- [ ] Gmail REST API integration (replaces IMAP)
- [ ] FCM push via gmail.users.watch → Pub/Sub
- [ ] EmailSyncWorker for delta sync
- [ ] Full Space peripheral notification pills with animations
- [ ] Home Space NotificationCompat fallback (separate path)
- [ ] Notification grouping logic (thread, sender, urgency)
- [ ] Thread context overlay / timeline for long conversations

### Phase 5: Persistence + Personalization (Week 9-10)
- [ ] Persistent spatial anchors (Room + Anchor.UUID)
- [ ] Panel position restore on relaunch
- [ ] Basic personalization engine (track read patterns, sender importance)
- [ ] Voice-based preference adjustments (via Gemini Live)
- [ ] Batch classification cost optimization tuning

### Phase 6: Polish + Glasses Port (Week 11-12)
- [ ] Glimmer theme variant for AI Glasses (additive display only)
- [ ] DisplayBlendMode routing tested on both form factors
- [ ] Performance optimization (minimize lit pixels for glasses, battery-conscious)
- [ ] Peripheral-vision-only mode testing
- [ ] User testing framework

---

## 10 — KEY REFERENCE LINKS

| Resource | URL |
|---|---|
| Android XR Developer Hub | https://developer.android.com/develop/xr |
| Jetpack XR SDK | https://developer.android.com/develop/xr/jetpack-xr-sdk |
| Compose for XR UI Guide | https://developer.android.com/develop/xr/jetpack-xr-sdk/develop-ui |
| Material Design for XR | https://developer.android.com/design/ui/xr/guides/spatial-ui |
| Jetpack Compose Glimmer | https://developer.android.com/develop/xr/jetpack-xr-sdk/jetpack-compose-glimmer |
| Glimmer Components | https://developer.android.com/develop/xr/jetpack-xr-sdk/jetpack-compose-glimmer/whats-included |
| Transparent Screen Design | https://design.google/library/transparent-screens |
| Figma Glimmer UI Kit | https://www.figma.com/community/file/1579881278082580424/jetpack-compose-glimmer-ui |
| XR Foundations (Input) | https://developer.android.com/design/ui/xr/guides/foundations |
| Hand Tracking API | https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/hands |
| Eye Tracking Extension | https://developer.android.com/develop/xr/openxr/extensions/XR_ANDROID_eye_tracking |
| OpenXR on Android XR | https://developer.android.com/develop/xr/openxr |
| XR Emulator Setup | https://developer.android.com/develop/xr/jetpack-xr-sdk/getting-started |
| Samsung Galaxy XR Specs | https://www.samsung.com/us/xr/galaxy-xr/galaxy-xr/ |
| Android XR SDK DP3 Blog | https://android-developers.googleblog.com/2025/12/build-for-ai-glasses-with-android-xr.html |
| Firebase AI Logic | https://firebase.google.com/docs/ai-logic |
| Gemini Live API | https://ai.google.dev/gemini-api/docs/live |
| Gmail REST API | https://developers.google.com/gmail/api |
| Gmail Push Notifications | https://developers.google.com/gmail/api/guides/push |

---

## 11 — CURSOR INSTRUCTIONS

When generating code from this prompt:

1. **Always use Jetpack Compose for XR** — `SpatialPanel`, `Subspace`, `SpatialRow`, `Orbiter`, `SubspaceModifier`. Do NOT use traditional XML layouts.
2. **Display-adaptive:** Check `DisplayBlendMode` — use Glimmer for glasses (ADDITIVE), standard Material3 for XR on headset. Never assume one form factor.
3. **Eye tracking is system-rendered.** Do NOT write custom gaze hit-testing code. The platform handles hover highlighting. For attention-awareness, use ARCore face blendshapes.
4. **Hand tracking: secondary hand only** for custom gestures. Check `Hand.getPrimaryHandSide()` and bind to the opposite hand. System navigation owns the primary hand.
5. **Voice uses Gemini Live API** with function calling — NOT `SpeechRecognizer` + manual intent parsing. Bidirectional audio, natural language understanding built in.
6. **AI uses Firebase AI Logic** (`com.google.firebase:firebase-ai`) — NOT `com.google.ai.client.generativeai`. Supports on-device Gemini Nano fallback.
7. **Batch Gemini calls.** Classify ~20 emails per API call. Never call Gemini per-email.
8. **Email backend: Gmail REST API + FCM push** — NOT IMAP IDLE. Use `gmail.users.watch` → Pub/Sub → FCM for real-time push.
9. **Dual notification paths.** Full Space: spatial pills/orbiters. Home Space: standard `NotificationCompat` (spatial panels don't work there).
10. **Persistent spatial anchors.** Store `Anchor.UUID` per thread in Room. Restore panel positions on relaunch with `Anchor.load()`.
11. **TiltGesture API** for email list scrolling. No permission needed, available since alpha10.
12. **Font size minimum 14dp.** Below this is illegible in XR at 1m viewing distance.
13. **Animations** should be smooth and slow (500ms-2000ms). No snapping. Fade in/out for notifications. Use `animateDpAsState` and `AnimatedVisibility`.
14. **Spatial panels max size:** 2560dp × 1800dp in Full Space. Typical panels: 400-900dp wide, 800-900dp tall.
15. **Don't forget `enableOnBackInvokedCallback="true"`** in the manifest for proper back navigation on spatial panels.

---

## 12 — ARCHITECTURE CORRECTIONS LOG

Tracking corrections to the original spec for reference:

| # | Issue | Original (Wrong) | Corrected |
|---|---|---|---|
| 1 | Eye tracking | Custom gaze hit-testing against composables | System-rendered hover only; use ARCore face blendshapes for attention |
| 2 | Glimmer scope | Used on headset | Glasses-only (additive displays); headset uses SpatialPanel + Material3 |
| 3 | Home Space notifications | SpatialPanel pills everywhere | Dual-path: spatial pills in Full Space, NotificationCompat in Home Space |
| 4 | Hand tracking | Both hands for custom gestures | Secondary hand only; primary hand owned by system navigation |
| 5 | Email backend | IMAP IDLE | Gmail REST API + FCM push (eliminates battery drain) |
| 6 | AI SDK | `com.google.ai.client.generativeai` | `com.google.firebase:firebase-ai` (adds on-device Gemini Nano) |
| 7 | Voice input | `SpeechRecognizer` + manual parser | Gemini Live API (bidirectional audio + function calling) |
| 8 | Classification | Per-email Gemini calls | Batch ~20 emails per call (95% cost reduction) |
| 9 | List scrolling | Hand/touchpad only | TiltGesture API (alpha10, no permission) |
| 10 | Panel persistence | Panels reset on relaunch | Persistent spatial anchors via Room + Anchor.UUID |
