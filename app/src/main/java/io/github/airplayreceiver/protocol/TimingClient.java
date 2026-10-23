package io.github.airplayreceiver.protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

/**
 * The AirPlay 1 timing exchange: an NTP-like protocol on UDP where the receiver queries the
 * sender's clock every few seconds. The receiver also answers timing queries from the sender.
 */
final class TimingClient {

    private static final String TAG = "Timing";
    private static final long SECONDS_FROM_1900_TO_1970 = 2208988800L;
    private static final int REQUEST_INTERVAL_MILLISECONDS = 3000;

    private final InetAddress clientAddress;
    private final int clientPort;
    private final DatagramSocket socket;
    private Thread thread;
    private volatile boolean running;
    private volatile long offsetNanoseconds;

    TimingClient(InetAddress clientAddress, int clientPort) throws IOException {
        this.clientAddress = clientAddress;
        this.clientPort = clientPort;
        socket = new DatagramSocket(new InetSocketAddress(0));
        socket.setSoTimeout(500);
    }

    int localPort() {
        return socket.getLocalPort();
    }

    void start() {
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "TimingClient");
        thread.start();
    }

    void stop() {
        running = false;
        socket.close();
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** Sender clock minus receiver clock, from the most recent exchange. */
    long offsetNanoseconds() {
        return offsetNanoseconds;
    }

    private void loop() {
        byte[] buffer = new byte[128];
        long nextRequest = 0;
        while (running) {
            long now = System.currentTimeMillis();
            if (clientPort > 0 && now >= nextRequest) {
                sendRequest();
                nextRequest = now + REQUEST_INTERVAL_MILLISECONDS;
            }
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException timeout) {
                continue;
            } catch (IOException error) {
                if (running) ReceiverLog.w(TAG, "Receive failed: " + error);
                continue;
            }
            if (packet.getLength() < 32) {
                continue;
            }
            int type = buffer[1] & 0x7F;
            long receiveTime = ntpNow();
            if (type == 0x53) {
                // Reply: originate (our send time) at 8, sender receive at 16, sender transmit at 24.
                long originate = readLong(buffer, 8);
                long remoteReceive = readLong(buffer, 16);
                long remoteTransmit = readLong(buffer, 24);
                long offset = ((remoteReceive - originate) + (remoteTransmit - receiveTime)) / 2;
                offsetNanoseconds = ntpToNanoseconds(offset);
            } else if (type == 0x52) {
                byte[] reply = new byte[32];
                reply[0] = (byte) 0x80;
                reply[1] = (byte) 0xD3;
                reply[3] = 7;
                System.arraycopy(buffer, 24, reply, 8, 8);
                writeLong(reply, 16, receiveTime);
                writeLong(reply, 24, ntpNow());
                try {
                    socket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                } catch (IOException error) {
                    ReceiverLog.w(TAG, "Reply failed: " + error);
                }
            }
        }
    }

    private void sendRequest() {
        byte[] request = new byte[32];
        request[0] = (byte) 0x80;
        request[1] = (byte) 0xD2;
        request[3] = 7;
        writeLong(request, 24, ntpNow());
        try {
            socket.send(new DatagramPacket(request, request.length, clientAddress, clientPort));
        } catch (IOException error) {
            if (running) ReceiverLog.w(TAG, "Request failed: " + error);
        }
    }

    /** Current time as a 64-bit NTP timestamp (32.32 fixed point seconds since 1900). */
    static long ntpNow() {
        long milliseconds = System.currentTimeMillis();
        long seconds = milliseconds / 1000 + SECONDS_FROM_1900_TO_1970;
        long fraction = ((milliseconds % 1000) << 32) / 1000;
        return (seconds << 32) | fraction;
    }

    static long ntpToNanoseconds(long ntp) {
        long seconds = ntp >> 32;
        long fraction = ntp & 0xFFFFFFFFL;
        return seconds * 1000000000L + ((fraction * 1000000000L) >>> 32);
    }

    private static long readLong(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    private static void writeLong(byte[] data, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            data[offset + i] = (byte) value;
            value >>>= 8;
        }
    }
}
