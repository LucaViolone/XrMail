package com.xremail.app.tracking

/**
 * Fuses system gaze (automatic hover), secondary-hand gestures, voice
 * commands (Gemini Live), and tilt input into a unified action stream.
 *
 * Note: Gaze hover highlighting is handled entirely by the platform —
 * this router does NOT do custom gaze hit-testing.
 */
class MultiModalInputRouter(
    val faceAttention: FaceAttentionTracker = FaceAttentionTracker(),
    val handGestures: SecondaryHandGestures = SecondaryHandGestures(),
    val tiltScroll: TiltScrollController = TiltScrollController(),
)
