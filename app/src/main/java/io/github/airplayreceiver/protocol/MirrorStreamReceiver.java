package io.github.airplayreceiver.protocol;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/**
 * Receives the screen mirroring stream: a TCP connection carrying 128-byte headers, each followed
 * by a payload. Type 0 payloads are AES-CTR encrypted H.264 access units with 4-byte length
 * prefixes; type 1 payloads carry the SPS and PPS in clear.
 */
final class MirrorStreamReceiver {

    private static final String TAG = "Mirror";
    private static final int HEADER_SIZE = 128;
    private static final int MAXIMUM_PAYLOAD = 8 * 1024 * 1024;

    private final ServerSocket serverSocket;
    private final MediaKeys.MirrorDecryptor decryptor;
    private final ReceiverListener listener;
    private Thread thread;
    private volatile boolean running;
    private volatile Socket connection;

    private long framesReceived;
    private long bytesReceived;

    MirrorStreamReceiver(MediaKeys.MirrorDecryptor decryptor, ReceiverListener listener) throws IOException {
        this.decryptor = decryptor;
        this.listener = listener;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(0));
    }

    int localPort() {
        return serverSocket.getLocalPort();
    }

    void start() {
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    receive();
                } catch (Exception error) {
                    if (running) {
                        ReceiverLog.w(TAG, "Mirroring stream ended: " + error);
                    }
                } finally {
                    ReceiverLog.i(TAG, "Mirroring stream closed after " + framesReceived + " frames, "
                            + (bytesReceived / 1024) + " KiB");
                    closeQuietly();
                }
            }
        }, "MirrorStreamReceiver");
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        thread.start();
    }

    void stop() {
        running = false;
        closeQuietly();
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void closeQuietly() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        Socket current = connection;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void receive() throws Exception {
        Socket socket = serverSocket.accept();
        connection = socket;
        socket.setTcpNoDelay(true);
        socket.setReceiveBufferSize(1024 * 1024);
        ReceiverLog.i(TAG, "Mirroring connection from " + socket.getInetAddress().getHostAddress());
        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(socket.getInputStream(), 256 * 1024));
        byte[] header = new byte[HEADER_SIZE];
        long lastReport = System.currentTimeMillis();

        while (running) {
            try {
                in.readFully(header);
            } catch (EOFException | SocketException end) {
                return;
            }
            int payloadSize = readIntLittleEndian(header, 0);
            int payloadType = header[4] & 0xFF;
            int payloadOption = header[6] & 0xFF;
            if (payloadSize < 0 || payloadSize > MAXIMUM_PAYLOAD) {
                throw new IOException("Invalid mirroring payload size " + payloadSize);
            }
            byte[] payload = new byte[payloadSize];
            in.readFully(payload);
            bytesReceived += HEADER_SIZE + payloadSize;

            switch (payloadType) {
                case 0x00:
                    decryptor.decrypt(payload, 0, payloadSize);
                    handleVideo(payload, readLongLittleEndian(header, 8));
                    break;
                case 0x01:
                    handleParameterSets(header, payload, payloadOption);
                    break;
                default:
                    // 0x02 heartbeat and 0x05 streaming statistics: nothing to do.
                    break;
            }

            long now = System.currentTimeMillis();
            if (now - lastReport > 10000) {
                ReceiverLog.d(TAG, "Received " + framesReceived + " frames, " + (bytesReceived / 1024) + " KiB");
                lastReport = now;
            }
        }
    }

    private void handleVideo(byte[] payload, long timestamp) {
        // Replace each 4-byte big-endian NAL length with an Annex B start code.
        boolean keyFrame = false;
        int position = 0;
        while (position + 4 <= payload.length) {
            int nalLength = ((payload[position] & 0xFF) << 24) | ((payload[position + 1] & 0xFF) << 16)
                    | ((payload[position + 2] & 0xFF) << 8) | (payload[position + 3] & 0xFF);
            if (nalLength <= 0 || nalLength > payload.length - position - 4) {
                ReceiverLog.w(TAG, "Dropping frame with invalid NAL length " + nalLength
                        + " at " + position + " of " + payload.length + " (decryption out of step?)");
                return;
            }
            payload[position] = 0;
            payload[position + 1] = 0;
            payload[position + 2] = 0;
            payload[position + 3] = 1;
            int nalType = payload[position + 4] & 0x1F;
            if (nalType == 5) {
                keyFrame = true;
            }
            position += 4 + nalLength;
        }
        framesReceived++;
        listener.onVideoFrame(payload, TimingClient.ntpToNanoseconds(timestamp), keyFrame);
    }

    private void handleParameterSets(byte[] header, byte[] payload, int payloadOption) {
        if (payload.length < 11) {
            ReceiverLog.d(TAG, "Parameter packet without payload, option 0x" + Integer.toHexString(payloadOption));
            return;
        }
        // avcC layout: version, profile, compatibility, level, length size, SPS count, SPS length, SPS,
        // PPS count, PPS length, PPS.
        int spsLength = ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
        if (8 + spsLength + 3 > payload.length) {
            ReceiverLog.w(TAG, "Malformed SPS/PPS packet");
            return;
        }
        byte[] sps = new byte[spsLength];
        System.arraycopy(payload, 8, sps, 0, spsLength);
        int ppsOffset = 8 + spsLength + 1;
        int ppsLength = ((payload[ppsOffset] & 0xFF) << 8) | (payload[ppsOffset + 1] & 0xFF);
        if (ppsOffset + 2 + ppsLength > payload.length) {
            ReceiverLog.w(TAG, "Malformed PPS in parameter packet");
            return;
        }
        byte[] pps = new byte[ppsLength];
        System.arraycopy(payload, ppsOffset + 2, pps, 0, ppsLength);

        int width = (int) Float.intBitsToFloat(readIntLittleEndian(header, 56));
        int height = (int) Float.intBitsToFloat(readIntLittleEndian(header, 60));
        int sourceWidth = (int) Float.intBitsToFloat(readIntLittleEndian(header, 40));
        int sourceHeight = (int) Float.intBitsToFloat(readIntLittleEndian(header, 44));
        ReceiverLog.i(TAG, "H.264 profile " + (payload[1] & 0xFF) + " level " + (payload[3] & 0xFF)
                + ", picture " + width + "x" + height + ", source " + sourceWidth + "x" + sourceHeight
                + ", option 0x" + Integer.toHexString(payloadOption));
        listener.onVideoParameters(sps, pps, width, height);
    }

    private static int readIntLittleEndian(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long readLongLittleEndian(byte[] data, int offset) {
        return (readIntLittleEndian(data, offset) & 0xFFFFFFFFL)
                | ((long) readIntLittleEndian(data, offset + 4) << 32);
    }
}
