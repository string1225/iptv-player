package com.string.iptv.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.nio.ByteOrder

@UnstableApi
class TvRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init { setEnableDecoderFallback(true) }

    override fun buildAudioRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: Handler,
        eventListener: AudioRendererEventListener, out: ArrayList<Renderer>,
    ) {
        // Select our software renderer for MP2 even when a device advertises a broken MP2 codec.
        out.add(Mp2AudioRenderer(eventHandler, eventListener, audioSink))
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector,
            enableDecoderFallback, audioSink, eventHandler, eventListener, out)
    }
}

@UnstableApi
private class Mp2AudioRenderer(handler: Handler, listener: AudioRendererEventListener, sink: AudioSink) :
    DecoderAudioRenderer<Mp2AudioDecoder>(handler, listener, sink) {
    override fun getName() = "Mp2AudioRenderer"

    override fun supportsFormatInternal(format: Format): Int = when {
        format.sampleMimeType != MimeTypes.AUDIO_MPEG_L2 -> C.FORMAT_UNSUPPORTED_TYPE
        format.drmInitData != null -> C.FORMAT_UNSUPPORTED_DRM
        format.sampleRate !in listOf(32_000, 44_100, 48_000) || format.channelCount !in 1..2 -> C.FORMAT_UNSUPPORTED_SUBTYPE
        !sinkSupportsFormat(pcmFormat(format.sampleRate, format.channelCount)) -> C.FORMAT_UNSUPPORTED_SUBTYPE
        else -> C.FORMAT_HANDLED
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): Mp2AudioDecoder =
        Mp2AudioDecoder(format.sampleRate, format.channelCount)

    override fun getOutputFormat(decoder: Mp2AudioDecoder): Format = pcmFormat(decoder.sampleRate, decoder.channelCount)

    private fun pcmFormat(rate: Int, channels: Int): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(rate).setChannelCount(channels).build()
}

@UnstableApi
private class Mp2AudioDecoder(var sampleRate: Int, var channelCount: Int) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>(arrayOfNulls(16), arrayOfNulls(16)) {
    private val pcm = Mp2FrameRecovery()

    init { setInitialInputBufferSize(4_096) }
    override fun getName() = "Mp2SoftwareDecoder"
    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    override fun createOutputBuffer() = SimpleDecoderOutputBuffer(::releaseOutputBuffer)
    override fun createUnexpectedDecodeException(error: Throwable) = DecoderException("MP2 decoder failed", error)

    override fun decode(input: DecoderInputBuffer, output: SimpleDecoderOutputBuffer, reset: Boolean): DecoderException? = try {
        if (reset) pcm.reset()
        val bytes = requireNotNull(input.data)
        val frame = pcm.decode(ByteArray(bytes.remaining()).also(bytes::get))
        if (frame == null) {
            output.init(input.timeUs, 0)
            output.shouldBeSkipped = true
            android.util.Log.w("IPTVAudio", "Skipped damaged MP2 frame")
        } else {
            sampleRate = frame.sampleRate
            channelCount = frame.channelCount
            output.init(input.timeUs, frame.samples.size * 2).order(ByteOrder.nativeOrder())
                .asShortBuffer().put(frame.samples)
        }
        null
    } catch (error: Exception) {
        DecoderException("Invalid MP2 audio frame", error)
    }
}
