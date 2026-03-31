package com.xremail.app.ui.anchors

/**
 * Manages persistent spatial anchors for email panels.
 * Stores Anchor.UUID per email thread so panel positions reload on relaunch.
 *
 * Production implementation:
 * ```
 * // Save when user repositions a panel
 * val anchor = session.createAnchor(panelPose)
 * anchorDao.upsert(AnchorEntity(threadId, anchor.uuid.toString()))
 *
 * // Restore on relaunch
 * val entity = anchorDao.getByThread(threadId) ?: return null
 * val anchor = Anchor.load(session, UUID.fromString(entity.anchorUuid))
 * spatialPanel.setAnchor(anchor)
 * ```
 *
 * Phase 1 stub: in-memory map, no Room dependency yet.
 */
class AnchorManager {

    private val anchors = mutableMapOf<String, AnchorEntity>()

    fun save(threadId: String, anchorUuid: String) {
        anchors[threadId] = AnchorEntity(
            threadId = threadId,
            anchorUuid = anchorUuid,
        )
    }

    fun get(threadId: String): AnchorEntity? = anchors[threadId]

    fun remove(threadId: String) {
        anchors.remove(threadId)
    }

    fun getAll(): List<AnchorEntity> = anchors.values.toList()
}
