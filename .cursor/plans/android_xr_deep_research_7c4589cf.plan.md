---
name: Android XR Deep Research
overview: Reference document covering Samsung Galaxy XR input model, every Jetpack XR spatial primitive (panels, orbiters, layouts), adaptive Material3 patterns, head-lock options, SceneCore, ARCore for XR, gaze + gesture input — and how each maps onto XrMail's existing alpha12/13 stack.
todos: []
isProject: false
---


# Android XR Deep Research — XrMail Reference

A consolidated reference for every Jetpack XR primitive XrMail can lean on. All version numbers match the project's current pins in [gradle/libs.versions.toml](gradle/libs.versions.toml) (`xrCompose 1.0.0-alpha12`, `xrScenecore 1.0.0-alpha13`, `xrMaterial3 1.0.0-alpha16`, `xrArcore 1.0.0-alpha12`).

---

## 1. The Hardware: Samsung Galaxy XR (ex-Project Moohan)

The "CR panels" XrMail is targeting are Samsung Galaxy XR's spatial panels, served through Android XR. Critical input facts that drive every UI decision:

- **Eye tracking**: 4 internal cameras detect pupil position. **Raw gaze is never exposed to apps** for privacy. The OS instead emits a generic hover effect on any interactable element the user looks at — i.e. you get hover via the standard Android UI framework, not coordinates.
- **Hand tracking**: 6 cameras, 26-joint skeleton (OpenXR layout). Default system gesture is **pinch** (thumb tip + index tip < ~5 cm). **Pinch-and-hold** scrolls / drags / resizes. Palm-in pinch opens system Launcher/Recents/Back, so don't bind that gesture.
- **Voice**: 6 mics → Gemini. XrMail already routes this through `voice/GeminiLiveManager` and `EmailCommandTool`.
- **Optional 6DoF controllers** + Bluetooth keyboard/mouse/trackpad. Designs must degrade gracefully when only hands+eyes are present.
- **Foveation**: eye-tracked, so the renderer puts pixels where the user is looking — keep fine type at the focal target, not the periphery.

Manifest features XrMail will eventually need:

```xml
<uses-feature android:name="android.hardware.xr.input.eye_tracking" android:required="false" />
<uses-feature android:name="android.hardware.xr.input.hand_tracking" android:required="false" />
<uses-permission android:name="android.permission.HAND_TRACKING" />
<uses-permission android:name="android.permission.HEAD_TRACKING" />
<uses-permission android:name="android.permission.SCENE_UNDERSTANDING_COARSE" />
```

---

## 2. Spatial Modes: Home Space vs Full Space

| Mode | What it gives you | What you lose |
|---|---|---|
| **Home Space** | Multitasks beside other apps. Any 2D Android app runs here unmodified. Default 1024×720 dp window. | No `SpatialPanel`, no Orbiters, no 3D models, no environments. |
| **Full Space** | Exclusive focus. All of `Subspace`, `SpatialPanel`, `Orbiter`, `SceneCore` entities, spatial environments, spatial audio, stereoscopic video. | Other apps disappear. |

Switch programmatically:

```kotlin
LocalSpatialConfiguration.current.requestFullSpaceMode()
LocalSpatialConfiguration.current.requestHomeSpaceMode()
```

Or declare default at launch in `AndroidManifest.xml` via `android.window.PROPERTY_XR_ACTIVITY_START_MODE`.

XrMail's `InteractionTierRouter` already assumes Full Space (it uses `Subspace` + `SpatialPanel` directly).

---

## 3. The Spatial Composable Catalog (`androidx.xr.compose`)

### Panels

- **`SpatialPanel`** — 2D content (Compose tree) hosted on a planar surface in 3D. The bread-and-butter primitive XrMail already uses everywhere in [`SpatialEmailLayout.kt`](app/src/main/java/com/xremail/app/ui/spatial/SpatialEmailLayout.kt) and [`InteractionTierRouter.kt`](app/src/main/java/com/xremail/app/ui/spatial/InteractionTierRouter.kt). Dynamically rescales by user distance ("Dmm" units) so it looks the same size from 1 m or 3 m. Must live inside a `Subspace`.
- **`MainPanel`** — special panel that hosts the activity's existing 2D Compose hierarchy. Useful when porting an existing app — skip for XrMail (we're XR-native).
- **`SpatialDialog`** / **`SpatialPopup`** — transient panels with auto-positioning relative to anchor. Replace any normal `AlertDialog` in spatial mode.
- **`SpatialExternalSurface`** — for video / SurfaceView content (mono, side-by-side stereo, top-bottom stereo). Relevant if XrMail ever shows video attachments.
- **`Volume`** — wraps a SceneCore `Entity` (3D model, particle, etc.) so it can sit in the Compose tree.

Per-panel modifiers worth knowing:

- `dragPolicy = MovePolicy()` — user can grab and reposition the panel. Already on all three panels in `SpatialEmailLayout`.
- `resizePolicy = ResizePolicy()` — handles to resize. Already used.
- `SubspaceModifier.width/height/offset/padding` — sizing in dp inside the subspace.

### Layouts (children of `Subspace`)

| Layout | Purpose |
|---|---|
| `SpatialRow` | Flat horizontal row of panels. |
| `SpatialCurvedRow(curveRadius = 825.dp)` | Wraps the row around the user. **825 dp is the Google-recommended radius** — that's exactly what XrMail uses in `SpatialEmailLayout.kt:68`. |
| `SpatialColumn` | Vertical stack. |
| `SpatialBox` | Z-stack / overlay. |
| `SpatialLayoutSpacer` | Spacer with size in dp. |
| `SpatialArrangement` | `Start`/`End`/`Center`/`SpaceBetween` along main axis. |

### Orbiters

`Orbiter` is a floating UI chip pinned to the **edge** of a `SpatialPanel`, layout, or entity. Used for nav rails, action toolbars, status pills — anything that should hover beside content rather than inside it. XrMail already uses it for `NotificationPill` (top-end), `QuickActionBar` (bottom-center), `CollapsePill` (top-end), and a `NotificationBanner` in TRIAGE.

```kotlin
Orbiter(
    position = ContentEdge.Top,    // Top / Bottom / Start / End
    offset = 48.dp,                // distance from panel edge
    alignment = Alignment.End,     // alignment along the edge
) { ... }
```

Key behaviors:
- Orbiters **inherit hover/dim/focus from their parent panel** — if the panel is unfocused, they dim with it. Good for ambient toolbars.
- Orbiters do **not** count as part of the panel's bounding box, so they don't affect resize handles.
- Material3-XR auto-promotes `NavigationSuiteScaffold`'s rail/bar into Orbiters when overrides are enabled (see §5).

### Subspace

`Subspace { ... }` is the bridge from 2D Compose into 3D. Anything outside a `Subspace` is normal 2D; anything inside can use `SpatialPanel`, `SpatialRow`, etc. Two flavors:

- **`Subspace`** — anchored to the parent layout in 2D space.
- **`ApplicationSubspace`** — anchored to the activity / `ActivitySpace` (i.e. the world, not the parent panel). Use this for HUDs and for the top-level layout.

XrMail's `InteractionTierRouter` uses `Subspace` and lays panels out with explicit `SubspaceModifier.offset(x, y, z)`. The Z values (0/15/30 dp) form the project's three-plane depth system.

---

## 4. The Head-Lock Question (the most important one for XrMail)

There are four distinct anchoring behaviors. Pick deliberately per panel:

| Behavior | How to get it | When to use |
|---|---|---|
| **World-locked** | Default `SpatialPanel` inside `Subspace` with static offset. Stays where placed. | TRIAGE / FOCUS reading panes. |
| **Body-locked / lazy follow** | Panel re-poses toward user when they walk away (system handles this in some configs; otherwise drive it manually). | Long sessions where the user moves around. |
| **Head-locked (true)** | Pin a `PanelEntity` to `session.scene.spatialUser.head` via SceneCore — re-pose every frame. | HUDs, toasts, voice-activity indicators that must always be visible. |
| **Orbiter-attached** | Wrap content in `Orbiter` on a parent panel. Follows the panel, not the head. | Per-panel toolbars. |

**The catch XrMail already hit**: `LazyFollowSubspace.kt` notes that driving a spring off ArDevice head yaw at 60 Hz "thrashed recomposition of every SpatialPanel and made the UI feel frozen", and the dp offset coefficient was too small to be visible at typical panel distance. The file's TODO is the right one:

> True head-following in Jetpack XR needs `Orbiter` or an `Entity` pinned to `session.scene.spatialUser.head`, not a dp offset inside a world-anchored `Subspace`.

The proper recipe for AMBIENT_HUD is one of:

1. **Orbiter on `ApplicationSubspace`** with no parent panel → cheapest, no per-frame work, gives natural edge-pinned HUD.
2. **`PanelEntity` attached as a child of `session.scene.spatialUser.head`** via SceneCore. Set local pose once (e.g. `Pose(translation = Vector3(0f, -0.1f, -1.2f))` for "1.2 m in front, 10 cm down"); the runtime keeps it locked because the parent transform is the head.
3. For a **lazy** lock, attach to head but lerp the local pose toward identity over 200–300 ms whenever yaw delta exceeds a deadzone — cheap, no recompositions.

XrMail's current "+/-160 dp off-center" HUD pattern in `InteractionTierRouter` works while world-locked but will drift out of view if the user walks. Replacing those `SpatialPanel`s with `Orbiter` (on an `ApplicationSubspace`) or a head-anchored `PanelEntity` is the unblock for the lazy-follow TODO.

---

## 5. Material 3 for XR — the adaptive shortcut

Dependency already pinned: `androidx.xr.compose.material3:material3:1.0.0-alpha16`.

Wrap the existing M3 tree with `EnableXrComponentOverrides { ... }` and these components auto-spatialize:

- `NavigationSuiteScaffold` → its `NavigationRail` / `NavigationBar` becomes an **Orbiter**.
- `ListDetailPaneScaffold` / `SupportingPaneScaffold` → each pane becomes a **`SpatialPanel`** in a `SpatialRow`.
- `TopAppBar` → Orbiter on the top edge.
- `BasicAlertDialog` → `SpatialDialog`.

This is the easiest way to make a single codebase render correctly on phone, foldable, large screen, **and** XR. XrMail's current 3-panel `SpatialCurvedRow` is essentially a hand-rolled `ListDetailPaneScaffold + supporting pane`; switching could collapse a lot of `SpatialEmailLayout.kt`.

Use `LocalSpatialCapabilities.current` to branch when needed:

```kotlin
if (LocalSpatialCapabilities.current.isSpatialUiEnabled) { ... } else { ... 2D fallback ... }
```

---

## 6. SceneCore (`androidx.xr.scenecore` 1.0.0-alpha13)

3D scene graph with an entity-component model.

Core types:

- **`Session`** — entry point. Factory for everything. Created via `Session.create(activity)`.
- **`ActivitySpace`** — top-level entity, real-world meters, Y-up.
- **`Entity`** subtypes:
  - `PanelEntity` — a 2D panel as a SceneCore object (what `SpatialPanel` compiles to under the hood).
  - `GltfModelEntity` — load `.glb`/`.gltf` via `GltfModel.create(session, uri)`.
  - `SurfaceEntity` — stereo video surfaces.
  - `ContentlessEntity` — invisible group / pivot.
- **Components** added onto entities:
  - `MovableComponent` — drag.
  - `ResizableComponent` — resize.
  - `InteractableComponent` — pointer/hover events.
- **Pose / Transform**: `entity.setPose(Pose(translation, rotation), relativeTo = Space.PARENT|ACTIVITY|REAL_WORLD)`. Also `setScale`, `setAlpha`.
- **Spatial user**: `session.scene.spatialUser` exposes `head` and `cameraView` entities — these are the parents you need for true head-locking.

Notes:
- Most async APIs migrated from `ListenableFuture` to **Kotlin suspend functions** in alpha13. There's a `xr-scenecore-guava` artifact if you need the old API.
- `Session.create` / `Session.configure` throw `SecurityException` if a required permission (`SCENE_UNDERSTANDING`, `HAND_TRACKING`, `HEAD_TRACKING`) is missing.

XrMail doesn't use SceneCore directly today — Compose-XR hides it. Only reach for SceneCore when:
- True head-lock is needed (HUD).
- A 3D model is needed (e.g. avatar, attachment preview).
- Custom hit-test or pose math.

---

## 7. ARCore for Jetpack XR (`androidx.xr.arcore` 1.0.0-alpha12)

This is **not the phone ARCore** — it's a slim perception API for headsets.

Configure on the session:

```kotlin
val cfg = session.config.copy(
    handTracking = HandTrackingMode.BOTH,
    planeTracking = PlaneTrackingMode.HORIZONTAL_AND_VERTICAL,
    headTracking = HeadTrackingMode.ENABLED,
)
session.configure(cfg)
```

Capabilities:

- **Device pose / head pose** — `androidx.xr.arcore.device-pose` API. Stream of head transforms each frame. Needs `HEAD_TRACKING` permission.
- **Plane tracking** — floors, walls, tables. Needs `SCENE_UNDERSTANDING_COARSE`.
- **Hand tracking** — see §8.
- **Hit testing** — `androidx.xr.arcore.hitTest(session, ray)` returns intersections with planes / anchors / entities.
- **Anchors** — pin an entity to a real-world point. Survive across frames; interop with SceneCore.
- **Geospatial** — VPS availability and pose conversion (mostly for outdoor AR — not useful for XrMail).

For XrMail, the practical use cases are:

- Reading **head pose** to drive lazy-follow without recomposing every panel (read pose in a `LaunchedEffect`, mutate a `MutableState<Pose>` that only the HUD entity reads).
- Hit-testing for "place the inbox on this wall" in an enterprise mode.

---

## 8. Gaze (eye tracking)

What you actually get as a developer:

- **No coordinates.** The OS routes gaze through the existing pointer/hover pipeline. Any composable with `Modifier.hoverable { ... }` or `Modifier.pointerInput` will receive enter/exit/hover.
- A "spatial cursor" (small dot) appears on the element you look at; you select with a pinch.
- Apps that genuinely need raw gaze can declare `<uses-feature android:name="android.hardware.xr.input.eye_tracking" android:required="true" />` and request a privileged permission, but Google strongly discourages this for consumer apps.
- **Foveated rendering** is automatic — render-quality follows gaze without app intervention.

For XrMail this means: the "gaze dwell" transition `AMBIENT_HUD → NOTIFICATION_CARDS` mentioned in `InteractionTierRouter.kt:59` should be implemented as **hover + small dwell timer** on the HUD orbiter, not as raw gaze coordinates. A 400–600 ms hover dwell is the standard pattern.

```kotlin
Modifier
    .hoverable(interactionSource)
    .onPointerEventTimer(...) // emit after dwell
```

---

## 9. Gesture / Hands (`androidx.xr.arcore` Hand API)

Two layers:

### A. System-level gestures (free)

- Pinch (thumb-tip + index-tip together) → click on the gaze target.
- Pinch-and-hold + drag → scroll, move panel, resize.
- Palm-in pinch → system menu (don't override).
- All of these surface to your composables as **standard pointer events** (down/move/up). You generally do not need to code them.

### B. Custom gestures (raw joints)

```kotlin
val handState = Hand.right(session).state.collectAsState()
val joints = handState.value.handJoints  // Map<HandJointType, Pose>
val thumbTip  = joints[HandJointType.HAND_JOINT_TYPE_THUMB_TIP]?.translation
val indexTip  = joints[HandJointType.HAND_JOINT_TYPE_INDEX_TIP]?.translation
val pinching  = thumbTip != null && indexTip != null &&
                Vector3.distance(thumbTip, indexTip) < 0.05f
```

26 joint types (OpenXR layout): `WRIST`, 5 fingers × `METACARPAL/PROXIMAL/INTERMEDIATE/DISTAL/TIP`, plus `PALM`.

Important rules:
- Use `Hand.getPrimaryHandSide(activity.contentResolver)` and **only bind custom gestures to the secondary hand** so you don't fight the system pinch.
- Check `state.trackingState == TrackingState.TRACKING` before reading joints.
- Avoid sustained-strain gestures; users tire fast.
- Different users hold their hand differently — design tolerant thresholds.

XrMail-relevant gestures:
- **Two-finger pinch + horizontal drag on TRIAGE** → snooze/archive.
- **Open-palm + fist** → "send" in compose mode.
- **Tilt-scroll** (already in `tiltScrollDelta`) — should come from `device-pose` IMU, not from hand joints.

---

## 10. Mapping the Research Back to XrMail

Concrete observations against the current code:

- [`SpatialEmailLayout.kt`](app/src/main/java/com/xremail/app/ui/spatial/SpatialEmailLayout.kt) — the 825 dp `SpatialCurvedRow` is exactly Google's recommended radius. The 3-panel split (inbox / reader / context) is a hand-rolled `ListDetailPaneScaffold + SupportingPaneScaffold`. Could be replaced with M3-XR scaffolds + `EnableXrComponentOverrides` for free phone/large-screen fallback.
- [`InteractionTierRouter.kt`](app/src/main/java/com/xremail/app/ui/spatial/InteractionTierRouter.kt) — the AMBIENT_HUD plane uses world-locked `SpatialPanel` with a hand-coded "+180 dp, -160 dp" offset. This is the wrong primitive for a HUD; it should be either an `Orbiter` on an `ApplicationSubspace` or a `PanelEntity` parented to `session.scene.spatialUser.head`.
- [`LazyFollowSubspace.kt`](app/src/main/java/com/xremail/app/ui/spatial/LazyFollowSubspace.kt) — already correctly diagnosed and stubbed out. The right fix is in §4.
- The TRIAGE→FOCUS gaze-dwell transition should use Compose `hoverable` + a 500 ms dwell timer, not raw gaze.
- Custom voice/email gestures (snooze, archive) should bind to the **non-primary hand** and use distance thresholds between specific OpenXR joints.

---

## 11. Authoritative Doc Bookmarks

- Spatial UI guide — `developer.android.com/develop/xr/jetpack-xr-sdk/ui-compose`
- Material3 for XR — `developer.android.com/develop/xr/jetpack-xr-sdk/material-design`
- SceneCore guide — `developer.android.com/develop/xr/jetpack-xr-sdk/work-with-entities`
- ARCore-XR overview — `developer.android.com/develop/xr/jetpack-xr-sdk/arcore`
- Hand tracking — `developer.android.com/develop/xr/jetpack-xr-sdk/arcore/hands`
- Plane tracking — `developer.android.com/develop/xr/jetpack-xr-sdk/arcore/planes`
- Device pose — `developer.android.com/develop/xr/jetpack-xr-sdk/arcore/device-pose`
- Home↔Full Space — `developer.android.com/develop/xr/jetpack-xr-sdk/transition-home-space-to-full-space`
- XR Compose release notes — `developer.android.com/jetpack/androidx/releases/xr-compose`
- Android XR design foundations — `developer.android.com/design/ui/xr/guides/foundations`
- OpenXR on Android XR — `developer.android.com/develop/xr/openxr`
- Galaxy XR product page — `samsung.com/us/xr/galaxy-xr/galaxy-xr/`

