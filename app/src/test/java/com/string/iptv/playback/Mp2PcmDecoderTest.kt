package com.string.iptv.playback

import androidx.media3.extractor.MpegAudioUtil
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class Mp2PcmDecoderTest {
    private fun frames(resource: String): List<ByteArray> {
        val bytes = javaClass.getResourceAsStream("/audio/$resource")!!.use { it.readBytes() }
        val result = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val length = MpegAudioUtil.getFrameSize(ByteBuffer.wrap(bytes, offset, 4).int)
            check(length > 4 && offset + length <= bytes.size)
            result += bytes.copyOfRange(offset, offset + length)
            offset += length
        }
        return result
    }

    @Test fun capturedBroadcastFormatDecodesConsecutiveStereoFramesToAudiblePcm() {
        val decoder = Mp2PcmDecoder()
        val frames = frames("mp2-48000-stereo.mp2")
        val audio = frames.map(decoder::decode)
        assertTrue(audio.size >= 8)
        assertTrue(audio.all { it.sampleRate == 48_000 && it.channelCount == 2 && it.samples.size == 2_304 })
        assertTrue(audio.flatMap { it.samples.toList() }.any { kotlin.math.abs(it.toInt()) > 500 })
        assertFalse(audio[2].samples.contentEquals(audio[3].samples))
    }

    @Test fun monoAndLowerBroadcastSampleRatesKeepTheirCorrectChannelLayout() {
        val mono = frames("mp2-44100-mono.mp2").map(Mp2PcmDecoder()::decode)
        assertTrue(mono.all { it.sampleRate == 44_100 && it.channelCount == 1 && it.samples.size == 1_152 })
        val stereo = frames("mp2-32000-stereo.mp2").map(Mp2PcmDecoder()::decode)
        assertTrue(stereo.all { it.sampleRate == 32_000 && it.channelCount == 2 && it.samples.size == 2_304 })
    }

    @Test fun discontinuitiesResetSynthesisStateBeforeReplayingTheSameFrames() {
        val decoder = Mp2PcmDecoder()
        val frames = frames("mp2-48000-stereo.mp2")
        val first = frames.map(decoder::decode)
        decoder.reset()
        val replay = frames.map(decoder::decode)
        first.zip(replay).forEach { (a, b) -> assertArrayEquals(a.samples, b.samples) }
    }

    @Test fun invalidAndTruncatedFramesFailInsteadOfProducingSilentSuccess() {
        assertThrows(IllegalArgumentException::class.java) { Mp2PcmDecoder().decode(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { Mp2PcmDecoder().decode(ByteArray(128)) }
        val valid = frames("mp2-48000-stereo.mp2").first()
        assertThrows(IllegalArgumentException::class.java) { Mp2PcmDecoder().decode(valid.copyOf(valid.size / 2)) }
    }

    @Test fun isolatedDamageResynchronizesAndLaterValidAudioResetsTheFailureBudget() {
        val decoder = Mp2FrameRecovery()
        val valid = frames("mp2-48000-stereo.mp2").first()
        repeat(4) {
            assertNull(decoder.decode(ByteArray(128)))
            assertNotNull(decoder.decode(valid))
        }
    }

    @Test fun repeatedDamageEscapesToRouteRecoveryRatherThanPlayingSilentVideoForever() {
        val decoder = Mp2FrameRecovery()
        assertNull(decoder.decode(ByteArray(128)))
        assertNull(decoder.decode(ByteArray(128)))
        assertThrows(IllegalArgumentException::class.java) { decoder.decode(ByteArray(128)) }
        decoder.reset()
        assertNotNull(decoder.decode(frames("mp2-48000-stereo.mp2").first()))
    }
}
