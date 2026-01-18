package at.planqton.directcan.data.can

/**
 * Reassembled ISO-TP message
 */
data class IsoTpMessage(
    val canId: Long,
    val startTimestamp: Long,
    val payload: ByteArray,
    val frames: List<CanFrame>,
    val isComplete: Boolean,
    val expectedLength: Int,
    val actualLength: Int
) {
    val payloadHex: String
        get() = payload.joinToString(" ") { "%02X".format(it) }

    val payloadAscii: String
        get() = payload.map { byte ->
            val c = byte.toInt() and 0xFF
            if (c in 32..126) c.toChar() else '.'
        }.joinToString("")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IsoTpMessage) return false
        return canId == other.canId &&
                startTimestamp == other.startTimestamp &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = canId.hashCode()
        result = 31 * result + startTimestamp.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * ISO-TP frame types
 */
enum class IsoTpFrameType {
    SINGLE_FRAME,      // 0x0X - Complete message in single frame
    FIRST_FRAME,       // 0x1X - Start of multi-frame message
    CONSECUTIVE_FRAME, // 0x2X - Continuation of multi-frame message
    FLOW_CONTROL,      // 0x3X - Flow control frame
    UNKNOWN
}

/**
 * ISO-TP Reassembler - reassembles multi-frame ISO-TP messages from individual CAN frames
 */
object IsoTpReassembler {

    /**
     * Get the ISO-TP frame type from frame data
     */
    fun getFrameType(data: ByteArray): IsoTpFrameType {
        if (data.isEmpty()) return IsoTpFrameType.UNKNOWN
        return when ((data[0].toInt() and 0xF0) shr 4) {
            0 -> IsoTpFrameType.SINGLE_FRAME
            1 -> IsoTpFrameType.FIRST_FRAME
            2 -> IsoTpFrameType.CONSECUTIVE_FRAME
            3 -> IsoTpFrameType.FLOW_CONTROL
            else -> IsoTpFrameType.UNKNOWN
        }
    }

    /**
     * Check if a frame is likely an ISO-TP frame
     * Uses structural validation to reduce false positives
     */
    fun isIsoTpFrame(data: ByteArray): Boolean {
        if (data.isEmpty()) return false

        val firstByte = data[0].toInt() and 0xFF
        val frameType = (firstByte and 0xF0) shr 4

        return when (frameType) {
            0 -> {
                // Single Frame: lower nibble is length (1-7 valid for CAN 2.0)
                val length = firstByte and 0x0F
                length in 1..7 && data.size > length
            }
            1 -> {
                // First Frame: 12-bit length must be > 7 (otherwise use SF)
                if (data.size < 2) return false
                val length = ((firstByte and 0x0F) shl 8) or (data[1].toInt() and 0xFF)
                length > 7
            }
            2 -> {
                // Consecutive Frame: sequence number 0-15, always valid structure
                // Hard to validate without context, but at least check we have data
                data.size > 1
            }
            3 -> {
                // Flow Control: flow status must be 0 (CTS), 1 (Wait), or 2 (Overflow)
                val flowStatus = firstByte and 0x0F
                flowStatus in 0..2
            }
            else -> false
        }
    }

    /**
     * Get frame type name for display
     */
    fun getFrameTypeName(data: ByteArray): String {
        return when (getFrameType(data)) {
            IsoTpFrameType.SINGLE_FRAME -> "SF"
            IsoTpFrameType.FIRST_FRAME -> "FF"
            IsoTpFrameType.CONSECUTIVE_FRAME -> "CF"
            IsoTpFrameType.FLOW_CONTROL -> "FC"
            IsoTpFrameType.UNKNOWN -> "?"
        }
    }

    /**
     * Reassemble ISO-TP messages from a list of frames (all should be same CAN ID)
     */
    fun reassemble(frames: List<CanFrame>): List<IsoTpMessage> {
        if (frames.isEmpty()) return emptyList()

        val messages = mutableListOf<IsoTpMessage>()
        var currentMessage: PendingMessage? = null

        // Sort frames by timestamp
        val sortedFrames = frames.sortedBy { it.timestamp }

        for (frame in sortedFrames) {
            when (getFrameType(frame.data)) {
                IsoTpFrameType.SINGLE_FRAME -> {
                    // Complete any pending message as incomplete
                    currentMessage?.let {
                        messages.add(it.toIsoTpMessage())
                    }
                    currentMessage = null

                    // Single frame contains complete message
                    val length = frame.data[0].toInt() and 0x0F
                    val payload = frame.data.sliceArray(1..minOf(length, frame.data.size - 1))
                    messages.add(
                        IsoTpMessage(
                            canId = frame.id,
                            startTimestamp = frame.timestamp,
                            payload = payload,
                            frames = listOf(frame),
                            isComplete = true,
                            expectedLength = length,
                            actualLength = payload.size
                        )
                    )
                }

                IsoTpFrameType.FIRST_FRAME -> {
                    // Complete any pending message as incomplete
                    currentMessage?.let {
                        messages.add(it.toIsoTpMessage())
                    }

                    // Start new multi-frame message
                    // Length is 12 bits: upper 4 bits of byte 0 (masked) + all of byte 1
                    val length = ((frame.data[0].toInt() and 0x0F) shl 8) or (frame.data[1].toInt() and 0xFF)
                    val payload = frame.data.sliceArray(2 until frame.data.size)

                    currentMessage = PendingMessage(
                        canId = frame.id,
                        startTimestamp = frame.timestamp,
                        expectedLength = length,
                        frames = mutableListOf(frame),
                        payload = payload.toMutableList(),
                        nextSequence = 1
                    )
                }

                IsoTpFrameType.CONSECUTIVE_FRAME -> {
                    if (currentMessage != null) {
                        val sequenceNum = frame.data[0].toInt() and 0x0F

                        // Check sequence number (wraps at 16)
                        if (sequenceNum == currentMessage.nextSequence) {
                            currentMessage.frames.add(frame)
                            currentMessage.payload.addAll(frame.data.slice(1 until frame.data.size))
                            currentMessage.nextSequence = (currentMessage.nextSequence + 1) and 0x0F

                            // Check if complete
                            if (currentMessage.payload.size >= currentMessage.expectedLength) {
                                messages.add(currentMessage.toIsoTpMessage())
                                currentMessage = null
                            }
                        } else {
                            // Sequence error - complete as incomplete and start fresh
                            messages.add(currentMessage.toIsoTpMessage())
                            currentMessage = null
                        }
                    }
                    // Orphan CF without FF - ignore
                }

                IsoTpFrameType.FLOW_CONTROL -> {
                    // Flow control frames are for TX side, we just observe them
                    // Could be added to current message's frames for completeness
                }

                IsoTpFrameType.UNKNOWN -> {
                    // Not an ISO-TP frame, skip
                }
            }
        }

        // Add any remaining pending message
        currentMessage?.let {
            messages.add(it.toIsoTpMessage())
        }

        return messages
    }

    private data class PendingMessage(
        val canId: Long,
        val startTimestamp: Long,
        val expectedLength: Int,
        val frames: MutableList<CanFrame>,
        val payload: MutableList<Byte>,
        var nextSequence: Int
    ) {
        fun toIsoTpMessage(): IsoTpMessage {
            val trimmedPayload = payload.take(expectedLength).toByteArray()
            return IsoTpMessage(
                canId = canId,
                startTimestamp = startTimestamp,
                payload = trimmedPayload,
                frames = frames.toList(),
                isComplete = payload.size >= expectedLength,
                expectedLength = expectedLength,
                actualLength = trimmedPayload.size
            )
        }
    }
}
