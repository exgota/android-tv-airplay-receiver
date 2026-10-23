package io.github.airplayreceiver.android;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import io.github.airplayreceiver.protocol.AlacDecoder;
import io.github.airplayreceiver.protocol.ReceiverLog;

/**
 * Decodes AirPlay audio (AAC-ELD, AAC-LC, ALAC or PCM) and plays it through AudioTrack.
 * Frames are played as they arrive; for mirroring audio the queue is kept short so that sound stays
 * close to the picture, which is also rendered on arrival.
 */
final class AudioRenderer {

    private static final String TAG = "AudioRenderer";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int COMPRESSION_PCM = 1;
    private static final int COMPRESSION_ALAC = 2;
    private static final int COMPRESSION_AAC_LC = 4;
    private static final int COMPRESSION_AAC_ELD = 8;

    private static final Object FLUSH = new Object();

    private final LinkedBlockingDeque<Object> queue = new LinkedBlockingDeque<>();
    private Thread thread;
    private volatile boolean running;
    private volatile float volume = 1.0f;

    private int compressionType;
    private int samplesPerFrame;
    private int queueLimit;
    private boolean lowLatency;

    // Owned by the playback thread.
    private AudioTrack track;
    private MediaCodec codec;
    private AlacDecoder alacDecoder;
    private short[] alacOutput;
    private byte[] pcmBuffer = new byte[16384];
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private float appliedVolume = -1;
    private long framesPlayed;
    private long framesDropped;
    private long decodeErrors;
    /** Sample frames handed to AudioTrack; with the playback head this gives the output backlog. */
    private long framesWritten;
    private long statisticsStart;
    private long backlogSum;
    private long backlogSamples;
    private long chunksSkipped;
    private int peakLevel;

    synchronized void start(int compressionType, int samplesPerFrame, boolean screenAudio) {
        stop();
        this.compressionType = compressionType;
        this.samplesPerFrame = samplesPerFrame;
        // About 150 ms of backlog for mirroring audio; several seconds for music, which arrives early.
        int frameMilliseconds = Math.max(1, (samplesPerFrame > 0 ? samplesPerFrame : 352) * 1000 / SAMPLE_RATE);
        lowLatency = screenAudio;
        queueLimit = screenAudio ? Math.max(4, 150 / frameMilliseconds) : Math.max(64, 4000 / frameMilliseconds);
        queue.clear();
        // A restarted stream gets a new AudioTrack whose playback head starts at zero again.
        framesWritten = 0;
        statisticsStart = 0;
        backlogSum = 0;
        backlogSamples = 0;
        chunksSkipped = 0;
        peakLevel = 0;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "AudioPlayback");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
        ReceiverLog.i(TAG, "Audio start: ct=" + compressionType + " spf=" + samplesPerFrame
                + " screenAudio=" + screenAudio + " queueLimit=" + queueLimit);
    }

    synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        queue.clear();
    }

    void onFrame(byte[] frame) {
        if (!running) {
            return;
        }
        while (queue.size() >= queueLimit) {
            if (queue.pollFirst() != null) {
                framesDropped++;
            }
        }
        queue.offerLast(frame);
    }

    void flush() {
        queue.clear();
        queue.offerLast(FLUSH);
    }

    /** AirPlay volume: 0 dB is full, -30 dB the lowest step, -144 dB mute. */
    void setVolume(float decibels) {
        if (decibels <= -144f) {
            volume = 0f;
        } else {
            volume = (float) Math.pow(10.0, Math.max(decibels, -30f) / 20.0);
        }
    }

    // ------------------------------------------------------------------ playback thread

    private void loop() {
        try {
            openOutput();
            openDecoder();
            while (running) {
                Object item;
                try {
                    item = queue.pollFirst(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    break;
                }
                applyVolume();
                if (item == FLUSH) {
                    flushOutput();
                } else if (item instanceof byte[]) {
                    decode((byte[]) item);
                }
                drainCodec(0);
            }
        } catch (Throwable error) {
            ReceiverLog.e(TAG, "Audio playback thread failed", error);
        } finally {
            release();
            ReceiverLog.i(TAG, "Audio played " + framesPlayed + " frames, dropped " + framesDropped
                    + ", decode errors " + decodeErrors);
        }
    }

    private void openOutput() {
        int minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(minimum * 3 / 2, 8192);
        size -= size % 4;
        trackBufferFrames = size / (2 * CHANNELS);
        track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM);
        track.play();
        ReceiverLog.i(TAG, "AudioTrack buffer " + size + " bytes (minimum " + minimum + ")");
    }

    private void openDecoder() throws Exception {
        switch (compressionType) {
            case COMPRESSION_ALAC:
                alacDecoder = AlacDecoder.forAirPlay(samplesPerFrame);
                alacOutput = new short[alacDecoder.maximumOutputSamples()];
                break;
            case COMPRESSION_AAC_ELD:
                openAacDecoder(new byte[]{(byte) 0xF8, (byte) 0xE8, 0x50, 0x00},
                        MediaCodecInfo.CodecProfileLevel.AACObjectELD);
                break;
            case COMPRESSION_AAC_LC:
                openAacDecoder(new byte[]{0x12, 0x10}, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                break;
            case COMPRESSION_PCM:
                break;
            default:
                ReceiverLog.w(TAG, "Unsupported audio compression type " + compressionType);
                break;
        }
    }

    private void openAacDecoder(byte[] audioSpecificConfig, int profile) throws Exception {
        MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, CHANNELS);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(audioSpecificConfig));
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, profile);
        format.setInteger(MediaFormat.KEY_IS_ADTS, 0);
        try {
            codec = MediaCodec.createByCodecName("OMX.google.aac.decoder");
        } catch (Exception error) {
            codec = MediaCodec.createDecoderByType("audio/mp4a-latm");
        }
        ReceiverLog.i(TAG, "Configuring " + codec.getName() + " with " + format);
        codec.configure(format, null, null, 0);
        codec.start();
    }

    private void decode(byte[] frame) {
        try {
            if (codec != null) {
                int index = codec.dequeueInputBuffer(20000);
                if (index < 0) {
                    framesDropped++;
                    return;
                }
                ByteBuffer input = codec.getInputBuffer(index);
                input.clear();
                input.put(frame);
                codec.queueInputBuffer(index, 0, frame.length, 0, 0);
                drainCodec(5000);
            } else if (alacDecoder != null) {
                int frames = alacDecoder.decode(frame, 0, frame.length, alacOutput);
                writeShorts(alacOutput, frames * CHANNELS);
            } else if (compressionType == COMPRESSION_PCM) {
                // AirPlay PCM is big-endian; AudioTrack wants little-endian.
                for (int i = 0; i + 1 < frame.length; i += 2) {
                    byte high = frame[i];
                    frame[i] = frame[i + 1];
                    frame[i + 1] = high;
                }
                writeBytes(frame, frame.length - (frame.length & 1));
            }
        } catch (Exception error) {
            decodeErrors++;
            if (decodeErrors <= 5 || decodeErrors % 100 == 0) {
                ReceiverLog.w(TAG, "Audio decode error " + decodeErrors + ": " + error);
            }
        }
    }

    private void drainCodec(long timeoutMicroseconds) {
        while (codec != null) {
            int index = codec.dequeueOutputBuffer(bufferInfo, timeoutMicroseconds);
            if (index >= 0) {
                if (bufferInfo.size > 0) {
                    ByteBuffer output = codec.getOutputBuffer(index);
                    if (pcmBuffer.length < bufferInfo.size) {
                        pcmBuffer = new byte[bufferInfo.size];
                    }
                    output.position(bufferInfo.offset);
                    output.get(pcmBuffer, 0, bufferInfo.size);
                    codec.releaseOutputBuffer(index, false);
                    writeBytes(pcmBuffer, bufferInfo.size);
                } else {
                    codec.releaseOutputBuffer(index, false);
                }
                timeoutMicroseconds = 0;
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                ReceiverLog.i(TAG, "Audio decoder output " + codec.getOutputFormat());
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
        }
    }

    /** Extra backlog allowed beyond the AudioTrack buffer before mirroring audio is trimmed. */
    private static final int BACKLOG_ALLOWANCE_FRAMES = SAMPLE_RATE / 20;
    private int trackBufferFrames;

    /** Returns false when the chunk should be skipped to bring mirroring audio back in step. */
    private boolean admit(int sampleFrames) {
        long played = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        long backlog = Math.max(0, framesWritten - played);
        backlogSum += backlog;
        backlogSamples++;
        long now = System.nanoTime();
        if (statisticsStart == 0) {
            statisticsStart = now;
        } else if (now - statisticsStart > 5000000000L) {
            ReceiverLog.i(TAG, "Audio output backlog average " + (backlogSum / backlogSamples * 1000 / SAMPLE_RATE)
                    + " ms, queue " + queue.size() + ", chunks skipped " + chunksSkipped
                    + ", peak level " + (peakLevel * 100 / 32768) + "%, volume " + volume
                    + ", decode errors " + decodeErrors);
            peakLevel = 0;
            statisticsStart = now;
            backlogSum = 0;
            backlogSamples = 0;
        }
        // AudioTrack only starts once its buffer is full, so trimming waits until the head moves,
        // and the limit sits above the buffer size.
        if (lowLatency && played > 0 && backlog > trackBufferFrames + BACKLOG_ALLOWANCE_FRAMES) {
            chunksSkipped++;
            return false;
        }
        return true;
    }

    private void writeBytes(byte[] data, int length) {
        for (int i = 0; i + 1 < length; i += 16) {
            peakLevel = Math.max(peakLevel, Math.abs((short) ((data[i] & 0xFF) | (data[i + 1] << 8))));
        }
        int sampleFrames = length / (2 * CHANNELS);
        if (!admit(sampleFrames)) {
            return;
        }
        int written = 0;
        while (written < length && running) {
            int result = track.write(data, written, length - written);
            if (result <= 0) {
                break;
            }
            written += result;
        }
        framesWritten += written / (2 * CHANNELS);
        framesPlayed++;
    }

    private void writeShorts(short[] data, int length) {
        for (int i = 0; i < length; i += 8) {
            peakLevel = Math.max(peakLevel, Math.abs(data[i]));
        }
        if (!admit(length / CHANNELS)) {
            return;
        }
        int written = 0;
        while (written < length && running) {
            int result = track.write(data, written, length - written);
            if (result <= 0) {
                break;
            }
            written += result;
        }
        framesWritten += written / CHANNELS;
        framesPlayed++;
    }

    private void applyVolume() {
        float current = volume;
        if (current != appliedVolume && track != null) {
            track.setVolume(current);
            appliedVolume = current;
        }
    }

    private void flushOutput() {
        if (track != null) {
            track.pause();
            track.flush();
            track.play();
            framesWritten = 0;
        }
        if (codec != null) {
            try {
                codec.flush();
            } catch (Exception error) {
                ReceiverLog.w(TAG, "Codec flush failed: " + error);
            }
        }
    }

    private void release() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            codec.release();
            codec = null;
        }
        alacDecoder = null;
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
            } catch (Exception ignored) {
            }
            track.release();
            track = null;
        }
    }
}
