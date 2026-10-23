package io.github.airplayreceiver.protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

/**
 * Receives RTP audio on UDP, reorders and deduplicates packets, asks the sender to resend missing
 * ones on the control channel, decrypts, and passes frames on in sequence order.
 */
final class AudioStreamReceiver {

    private static final String TAG = "Audio";
    private static final int SLOT_COUNT = 1024;
    private static final int RESET_DISTANCE = 512;

    private final MediaKeys.AudioDecryptor decryptor;
    private final ReceiverListener listener;
    private final InetAddress clientAddress;
    private final int clientControlPort;
    /** How many packets may queue behind a missing one before it is given up. */
    private final int reorderWindow;

    private final DatagramSocket dataSocket;
    private final DatagramSocket controlSocket;
    private Thread dataThread;
    private Thread controlThread;
    private volatile boolean running;

    private final byte[][] slots = new byte[SLOT_COUNT][];
    private final int[] slotSequence = new int[SLOT_COUNT];
    private final long[] slotTimestamp = new long[SLOT_COUNT];
    private boolean initialized;
    private int nextSequence;
    private int highestSequence;
    private int resendSequence;
    private int controlSequence;

    private long packetsDelivered;
    private long packetsSkipped;
    private long resendsRequested;
    private long packetsReceived;
    private long lastReport;

    AudioStreamReceiver(MediaKeys.AudioDecryptor decryptor, ReceiverListener listener,
                        InetAddress clientAddress, int clientControlPort, int reorderWindow) throws IOException {
        this.decryptor = decryptor;
        this.listener = listener;
        this.clientAddress = clientAddress;
        this.clientControlPort = clientControlPort;
        this.reorderWindow = reorderWindow;
        dataSocket = new DatagramSocket(new InetSocketAddress(0));
        dataSocket.setReceiveBufferSize(1024 * 1024);
        dataSocket.setSoTimeout(1000);
        controlSocket = new DatagramSocket(new InetSocketAddress(0));
        controlSocket.setSoTimeout(1000);
    }

    int dataPort() {
        return dataSocket.getLocalPort();
    }

    int controlPort() {
        return controlSocket.getLocalPort();
    }

    void start() {
        running = true;
        dataThread = new Thread(new Runnable() {
            @Override
            public void run() {
                receive(dataSocket, false);
            }
        }, "AudioData");
        dataThread.setPriority(Thread.MAX_PRIORITY);
        controlThread = new Thread(new Runnable() {
            @Override
            public void run() {
                receive(controlSocket, true);
            }
        }, "AudioControl");
        dataThread.start();
        controlThread.start();
    }

    boolean isRunning() {
        return running;
    }

    void stop() {
        if (!running) {
            return;
        }
        running = false;
        dataSocket.close();
        controlSocket.close();
        if (dataThread != null) dataThread.interrupt();
        if (controlThread != null) controlThread.interrupt();
        ReceiverLog.i(TAG, "Audio stopped: delivered " + packetsDelivered + ", skipped " + packetsSkipped
                + ", resend requests " + resendsRequested);
    }

    /** Discards queued packets; the next packet received restarts the sequence. */
    synchronized void flush() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = null;
        }
        initialized = false;
    }

    private void receive(DatagramSocket socket, boolean control) {
        byte[] buffer = new byte[2048];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException timeout) {
                continue;
            } catch (IOException error) {
                if (running) ReceiverLog.w(TAG, "Receive failed: " + error);
                continue;
            }
            int length = packet.getLength();
            if (length < 12) {
                continue;
            }
            int type = buffer[1] & 0x7F;
            if (control) {
                if (type == 0x56 && length >= 16) {
                    // Resent packet: the original RTP packet follows a 4-byte header.
                    enqueue(buffer, 4, length - 4);
                }
                // 0x54 sync packets map RTP time to NTP time; playback here is arrival-paced.
                continue;
            }
            if (type == 0x60) {
                packetsReceived++;
                enqueue(buffer, 0, length);
            }
        }
    }

    private synchronized void enqueue(byte[] packet, int offset, int length) {
        if (length < 12) {
            return;
        }
        int sequence = ((packet[offset + 2] & 0xFF) << 8) | (packet[offset + 3] & 0xFF);
        long timestamp = ((packet[offset + 4] & 0xFFL) << 24) | ((packet[offset + 5] & 0xFFL) << 16)
                | ((packet[offset + 6] & 0xFFL) << 8) | (packet[offset + 7] & 0xFFL);

        if (!initialized) {
            initialized = true;
            nextSequence = sequence;
            highestSequence = sequence;
            resendSequence = sequence;
        }
        int distance = (short) (sequence - nextSequence);
        if (distance < 0) {
            return; // duplicate (AAC-ELD frames are sent redundantly) or too late
        }
        if (distance >= RESET_DISTANCE) {
            ReceiverLog.w(TAG, "Sequence jumped from " + nextSequence + " to " + sequence + ", resetting");
            flush();
            initialized = true;
            nextSequence = sequence;
            highestSequence = sequence;
            resendSequence = sequence;
        }
        int slot = sequence % SLOT_COUNT;
        if (slots[slot] != null && slotSequence[slot] == sequence) {
            return;
        }
        byte[] payload = new byte[length - 12];
        System.arraycopy(packet, offset + 12, payload, 0, payload.length);
        slots[slot] = payload;
        slotSequence[slot] = sequence;
        slotTimestamp[slot] = timestamp;
        if ((short) (sequence - highestSequence) > 0) {
            highestSequence = sequence;
        }

        requestMissing();
        drain();
    }

    private void requestMissing() {
        if (clientControlPort <= 0) {
            return;
        }
        if ((short) (resendSequence - nextSequence) < 0) {
            resendSequence = nextSequence;
        }
        int first = -1;
        int count = 0;
        while ((short) (resendSequence - highestSequence) < 0) {
            int slot = resendSequence % SLOT_COUNT;
            boolean present = slots[slot] != null && slotSequence[slot] == resendSequence;
            if (!present) {
                if (first < 0) first = resendSequence;
                count++;
            } else if (first >= 0) {
                sendResendRequest(first, count);
                first = -1;
                count = 0;
            }
            resendSequence = (resendSequence + 1) & 0xFFFF;
        }
        if (first >= 0) {
            sendResendRequest(first, count);
        }
    }

    private void sendResendRequest(int firstSequence, int count) {
        byte[] request = new byte[8];
        request[0] = (byte) 0x80;
        request[1] = (byte) (0x55 | 0x80);
        request[2] = (byte) (controlSequence >> 8);
        request[3] = (byte) controlSequence;
        request[4] = (byte) (firstSequence >> 8);
        request[5] = (byte) firstSequence;
        request[6] = (byte) (count >> 8);
        request[7] = (byte) count;
        controlSequence = (controlSequence + 1) & 0xFFFF;
        resendsRequested++;
        try {
            controlSocket.send(new DatagramPacket(request, request.length, clientAddress, clientControlPort));
        } catch (IOException error) {
            ReceiverLog.d(TAG, "Resend request failed: " + error);
        }
    }

    private void drain() {
        while (true) {
            int slot = nextSequence % SLOT_COUNT;
            byte[] payload = slots[slot];
            if (payload != null && slotSequence[slot] == nextSequence) {
                slots[slot] = null;
                deliver(payload, slotTimestamp[slot]);
                nextSequence = (nextSequence + 1) & 0xFFFF;
                continue;
            }
            int waiting = (short) (highestSequence - nextSequence);
            if (waiting > reorderWindow) {
                packetsSkipped++;
                nextSequence = (nextSequence + 1) & 0xFFFF;
                continue;
            }
            return;
        }
    }

    private void deliver(byte[] payload, long timestamp) {
        // AAC-ELD streams open with 4-byte "no data" markers instead of audio.
        if (payload.length == 4 && payload[0] == 0x00 && payload[1] == 0x68 && payload[2] == 0x34 && payload[3] == 0x00) {
            return;
        }
        try {
            decryptor.decrypt(payload, 0, payload.length);
        } catch (Exception error) {
            ReceiverLog.e(TAG, "Audio decryption failed", error);
            return;
        }
        packetsDelivered++;
        long now = System.currentTimeMillis();
        if (now - lastReport > 5000) {
            lastReport = now;
            // Decrypted AAC-ELD frames begin with 0x8c, 0x8d, 0x8e or 0x80-0x82; ALAC frames with 0x20.
            ReceiverLog.i(TAG, "Received " + packetsReceived + ", delivered " + packetsDelivered + ", skipped "
                    + packetsSkipped + ", resend requests " + resendsRequested + ", frame of " + payload.length
                    + " bytes starts " + ReceiverLog.hex(payload, 0, Math.min(8, payload.length)));
        }
        listener.onAudioFrame(payload, timestamp);
    }
}
