package com.xremail.app.ui.anchors

/**
 * Represents a persistent spatial anchor stored in Room.
 * Maps an email thread to an Anchor UUID so panel positions
 * survive app restarts.
 *
 * Production: annotate with @Entity(tableName = "spatial_anchors")
 * and use @PrimaryKey on threadId.
 */
data class AnchorEntity(
    val threadId: String,
    val anchorUuid: String,
    val lastUpdated: Long = System.currentTimeMillis(),
)
