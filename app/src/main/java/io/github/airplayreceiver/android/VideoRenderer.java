package io.github.airplayreceiver.android;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.github.airplayreceiver.protocol.ReceiverLog;
import io.github.airplayreceiver.protocol.SequenceParameterSetRewriter;

/**
 * Decodes the mirrored H.264 stream with the synchronous MediaCodec API on a single thread, which
 * owns the codec for its whole life. Frames since the last key frame are kept so that decoding can
 * resume after the display surface is recreated, because the sender only sends key frames rarely.
 */
final class VideoRenderer {

    interface SizeListener {
        void onVideoSize(int width, int height);
    }

    private static final String TAG = "VideoRenderer";
    private static final String MIME_TYPE = "video/avc";
    private static final int MAXIMUM_CACHED_FRAMES = 600;
    private static final int MAXIMUM_CACHED_BYTES = 24 * 1024 * 1024;
    private static final int QUEUE_LIMIT = 90;

    private static final class Parameters {
        final byte[] sps;
        final byte[] pps;
        final int width;
        final int height;

        Parameters(byte[] sps, byte[] pps, int width, int height) {
            this.sps = sps;
            this.pps = pps;
            this.width = width;
            this.height = height;
        }
    }

    private static final class Frame {
        final byte[] data;
        final long presentationMicroseconds;
        final boolean keyFrame;

        Frame(byte[] data, long presentationMicroseconds, boolean keyFrame) {
            this.data = data;
            this.presentationMicroseconds = presentationMicroseconds;
            this.keyFrame = keyFrame;
        }
    }

    private static final class SurfaceChange {
        final Surface surface;
        final CountDownLatch handled = new CountDownLatch(1);

        SurfaceChange(Surface surface) {
            this.surface = surface;
        }
    }

    private static final Object RESET = new Object();

    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private volatile SizeListener sizeListener;
    private volatile int videoWidth;
    private volatile int videoHeight;
    private Thread thread;
    private volatile boolean running;

    // Owned by the decoder thread.
    private MediaCodec codec;
    private Surface surface;
    private Parameters parameters;
    private final List<Frame> cache = new ArrayList<>();
    private int cachedBytes;
    private boolean cacheValid;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private long framesDecoded;
    private long framesDropped;
    private long lastPresentation;
    private final java.util.HashMap<Long, Long> queuedAt = new java.util.HashMap<>();
    private long statisticsStart;
    private long statisticsFrames;
    private long latencySum;
    private long latencyMaximum;

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "VideoDecoder");
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        thread.start();
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

    void setSizeListener(SizeListener listener) {
        sizeListener = listener;
        if (listener != null && videoWidth > 0) {
            listener.onVideoSize(videoWidth, videoHeight);
        }
    }

    /**
     * Hands the decoder a display surface, or takes it away. Removal waits until the codec has let
     * go of the old surface, because the surface is destroyed as soon as the caller returns.
     */
    void setSurface(Surface newSurface) {
        SurfaceChange change = new SurfaceChange(newSurface);
        queue.offer(change);
        if (newSurface == null) {
            try {
                change.handled.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Forgets the stream; used when mirroring ends. */
    void reset() {
        removeQueued(true);
        queue.offer(RESET);
    }

    /** Removes queued frames (and optionally parameters); surface changes stay, since callers may await them. */
    private void removeQueued(boolean includeParameters) {
        for (Object item : queue.toArray()) {
            if (item instanceof Frame || (includeParameters && item instanceof Parameters)) {
                queue.remove(item);
            }
        }
    }

    void onParameters(byte[] sps, byte[] pps, int width, int height) {
        videoWidth = width;
        videoHeight = height;
        SizeListener listener = sizeListener;
        if (listener != null) {
            listener.onVideoSize(width, height);
        }
        queue.offer(new Parameters(sps, pps, width, height));
    }

    void onFrame(byte[] data, long timestampNanoseconds, boolean keyFrame) {
        if (queue.size() > QUEUE_LIMIT) {
            // The decoder has fallen far behind; keep only what is needed to resume at a key frame.
            ReceiverLog.w(TAG, "Decoder backlog of " + queue.size() + " items, waiting for next key frame");
            removeQueued(false);
        }
        queue.offer(new Frame(data, timestampNanoseconds / 1000, keyFrame));
    }

    // ------------------------------------------------------------------ decoder thread

    private void loop() {
        try {
            while (running) {
                Object item = queue.poll();
                if (item == null) {
                    if (codec != null) {
                        // Wait inside the codec so a decoded frame is shown the moment it is ready.
                        drainOutput(4000);
                        continue;
                    }
                    try {
                        item = queue.poll(10, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        break;
                    }
                }
                if (item instanceof Frame) {
                    handleFrame((Frame) item);
                } else if (item instanceof Parameters) {
                    handleParameters((Parameters) item);
                } else if (item instanceof SurfaceChange) {
                    handleSurface(((SurfaceChange) item).surface);
                    ((SurfaceChange) item).handled.countDown();
                } else if (item == RESET) {
                    releaseCodec();
                    parameters = null;
                    clearCache();
                }
                drainOutput(0);
            }
        } catch (Throwable error) {
            ReceiverLog.e(TAG, "Video decoder thread failed", error);
        } finally {
            releaseCodec();
            clearCache();
            parameters = null;
            ReceiverLog.i(TAG, "Decoded " + framesDecoded + " frames, dropped " + framesDropped);
        }
    }

    private void handleParameters(Parameters next) {
        boolean changed = parameters == null || !Arrays.equals(parameters.sps, next.sps)
                || !Arrays.equals(parameters.pps, next.pps);
        parameters = next;
        if (changed) {
            // New parameters are always followed by a key frame, so older frames are useless.
            clearCache();
            if (surface != null) {
                configureCodec();
            }
        }
    }

    private void handleSurface(Surface newSurface) {
        if (newSurface == surface) {
            return;
        }
        releaseCodec();
        surface = newSurface;
        if (surface != null && parameters != null) {
            configureCodec();
            if (codec != null && cacheValid) {
                ReceiverLog.i(TAG, "Replaying " + cache.size() + " cached frames");
                for (Frame frame : new ArrayList<>(cache)) {
                    queueFrame(frame);
                }
            }
        }
    }

    private void handleFrame(Frame frame) {
        if (frame.keyFrame) {
            clearCache();
            cacheValid = true;
        }
        if (cacheValid) {
            cache.add(frame);
            cachedBytes += frame.data.length;
            if (cache.size() > MAXIMUM_CACHED_FRAMES || cachedBytes > MAXIMUM_CACHED_BYTES) {
                clearCache();
            }
        }
        if (codec != null) {
            queueFrame(frame);
        }
    }

    private void clearCache() {
        cache.clear();
        cachedBytes = 0;
        cacheValid = false;
    }

    private void configureCodec() {
        releaseCodec();
        Parameters current = parameters;
        if (current == null || surface == null || !surface.isValid()) {
            return;
        }
        int width = current.width > 0 ? current.width : 1920;
        int height = current.height > 0 ? current.height : 1080;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            byte[] sps = SequenceParameterSetRewriter.rewrite(current.sps);
            ReceiverLog.i(TAG, "SPS " + ReceiverLog.hex(current.sps) + " rewritten to " + ReceiverLog.hex(sps));
            format.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(current.pps)));
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(width * height, 1024 * 1024));
            // MediaTek extensions read by the Fire TV's ACodec: output each picture as soon as it is
            // decoded instead of filling the picture buffer first. Other decoders ignore unknown keys.
            format.setInteger("vdec-lowlatency", 1);
            format.setInteger("vdec-no-reorder", 1);
            codec = MediaCodec.createDecoderByType(MIME_TYPE);
            if (Build.VERSION.SDK_INT >= 30 && codec.getCodecInfo().getCapabilitiesForType(MIME_TYPE)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                // The standard form of the MediaTek switches above, on Android 11 and later.
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                ReceiverLog.i(TAG, "Decoder supports low-latency mode");
            }
            if (Build.VERSION.SDK_INT >= 23) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0); // real-time
            }
            ReceiverLog.i(TAG, "Configuring " + codec.getName() + " for " + width + "x" + height);
            codec.configure(format, surface, null, 0);
            codec.start();
            lastPresentation = 0;
        } catch (Exception error) {
            ReceiverLog.e(TAG, "Could not start the H.264 decoder", error);
            releaseCodec();
        }
    }

    private void releaseCodec() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
            queuedAt.clear();
        }
    }

    private void queueFrame(Frame frame) {
        long deadline = System.currentTimeMillis() + 500;
        while (codec != null && running) {
            int index;
            try {
                index = codec.dequeueInputBuffer(10000);
            } catch (Exception error) {
                ReceiverLog.e(TAG, "dequeueInputBuffer failed, restarting decoder", error);
                configureCodec();
                return;
            }
            if (index >= 0) {
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null || buffer.capacity() < frame.data.length) {
                    ReceiverLog.w(TAG, "Frame of " + frame.data.length + " bytes does not fit the input buffer");
                    codec.queueInputBuffer(index, 0, 0, frame.presentationMicroseconds, 0);
                    framesDropped++;
                    return;
                }
                buffer.clear();
                buffer.put(frame.data);
                // Presentation times must increase; sender clock jumps would otherwise stall some decoders.
                long presentation = Math.max(frame.presentationMicroseconds, lastPresentation + 1);
                lastPresentation = presentation;
                if (queuedAt.size() < 256) {
                    queuedAt.put(presentation, System.nanoTime());
                }
                codec.queueInputBuffer(index, 0, frame.data.length, presentation,
                        frame.keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                return;
            }
            drainOutput(0);
            if (System.currentTimeMillis() > deadline) {
                framesDropped++;
                ReceiverLog.w(TAG, "No decoder input buffer for 500 ms, dropping a frame");
                return;
            }
        }
    }

    private void drainOutput(long timeoutMicroseconds) {
        while (codec != null) {
            int index;
            try {
                index = codec.dequeueOutputBuffer(bufferInfo, timeoutMicroseconds);
            } catch (Exception error) {
                ReceiverLog.e(TAG, "dequeueOutputBuffer failed", error);
                configureCodec();
                return;
            }
            if (index >= 0) {
                codec.releaseOutputBuffer(index, true);
                framesDecoded++;
                recordLatency(bufferInfo.presentationTimeUs);
                if (framesDecoded == 1) {
                    ReceiverLog.i(TAG, "First frame rendered");
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                ReceiverLog.i(TAG, "Decoder output format " + codec.getOutputFormat());
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
        }
    }

    /** Logs decoder latency (input queued to output released) and frame rate every five seconds. */
    private void recordLatency(long presentation) {
        Long queued = queuedAt.remove(presentation);
        long now = System.nanoTime();
        if (queued != null) {
            long latency = (now - queued) / 1000000;
            latencySum += latency;
            latencyMaximum = Math.max(latencyMaximum, latency);
            statisticsFrames++;
        }
        if (statisticsStart == 0) {
            statisticsStart = now;
        } else if (now - statisticsStart > 5000000000L && statisticsFrames > 0) {
            double seconds = (now - statisticsStart) / 1e9;
            ReceiverLog.i(TAG, String.format(java.util.Locale.US,
                    "%.1f fps, decoder latency average %d ms, maximum %d ms, queue %d",
                    statisticsFrames / seconds, latencySum / statisticsFrames, latencyMaximum, queue.size()));
            statisticsStart = now;
            statisticsFrames = 0;
            latencySum = 0;
            latencyMaximum = 0;
            if (queuedAt.size() > 128) {
                queuedAt.clear();
            }
        }
    }

    private static byte[] withStartCode(byte[] nal) {
        byte[] result = new byte[nal.length + 4];
        result[3] = 1;
        System.arraycopy(nal, 0, result, 4, nal.length);
        return result;
    }
}
