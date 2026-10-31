package com.string.iptv.playback

import java.io.InputStream
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.Header
import javazoom.jl.decoder.SampleBuffer

/** One continuous MPEG-1 Layer II stream; reset synthesis history after a seek or route change. */
class Mp2PcmDecoder {
    data class PcmFrame(val samples: ShortArray, val sampleRate: Int, val channelCount: Int)

    private var input = FrameInput()
    private var bitstream = Bitstream(input)
    private var decoder = Decoder()

    fun reset() {
        bitstream.close()
        input = FrameInput()
        bitstream = Bitstream(input)
        decoder = Decoder()
    }

    fun decode(frame: ByteArray): PcmFrame {
        require(frame.size >= 4) { "MP2 frame has no header" }
        input.replace(frame)
        try {
            val header = requireNotNull(bitstream.readFrame()) { "Invalid or truncated MP2 frame" }
            require(header.layer() == 2 && header.version() == Header.MPEG1) { "Expected MPEG-1 Layer II" }
            require(header.framesize + 4 == frame.size) { "MP2 buffer must contain one complete frame" }
            val pcm = decoder.decodeFrame(header, bitstream) as SampleBuffer
            return PcmFrame(pcm.buffer.copyOf(pcm.bufferLength), pcm.sampleFrequency, pcm.channelCount)
        } finally {
            bitstream.closeFrame()
        }
    }

    /** JLayer retains its Bitstream inside the Layer II decoder, so only replace the frame data. */
    private class FrameInput : InputStream() {
        private var bytes = ByteArray(0)
        private var position = 0

        fun replace(frame: ByteArray) {
            check(position == bytes.size) { "Previous MP2 frame was not consumed" }
            bytes = frame
            position = 0
        }

        override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xff else -1

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(target, offset, position, position + count)
            position += count
            return count
        }
    }
}

/** A damaged transport frame may be dropped; repeated decoder failure must reach route recovery. */
class Mp2FrameRecovery {
    private val decoder = Mp2PcmDecoder()
    private var consecutiveErrors = 0

    fun reset() {
        decoder.reset()
        consecutiveErrors = 0
    }

    fun decode(frame: ByteArray): Mp2PcmDecoder.PcmFrame? = try {
        decoder.decode(frame).also { consecutiveErrors = 0 }
    } catch (error: Exception) {
        decoder.reset()
        if (++consecutiveErrors >= 3) throw error
        null
    }
}
