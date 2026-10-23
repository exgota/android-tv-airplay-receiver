package io.github.airplayreceiver.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small multicast DNS responder (RFC 6762 / RFC 6763) that publishes the "_airplay._tcp" and
 * "_raop._tcp" services for one IPv4 address. It answers PTR, SRV, TXT and A queries and sends
 * announcements on start and goodbye packets on stop.
 */
public final class MulticastDnsResponder {

    private static final String TAG = "MulticastDns";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int PORT = 5353;
    private static final String GROUP = "224.0.0.251";

    private static final int TYPE_A = 1;
    private static final int TYPE_PTR = 12;
    private static final int TYPE_TXT = 16;
    private static final int TYPE_SRV = 33;
    private static final int TYPE_ANY = 255;
    private static final int CLASS_IN = 1;
    private static final int CACHE_FLUSH = 0x8000;

    private static final int HOST_TTL = 120;
    private static final int SERVICE_TTL = 4500;
    private static final String SERVICE_ENUMERATION = "_services._dns-sd._udp.local";

    /** One advertised service instance. */
    public static final class Service {
        final String instanceName;
        final String serviceType;
        final int port;
        final byte[] txtRecord;

        public Service(String instanceName, String serviceType, int port, byte[] txtRecord) {
            this.instanceName = instanceName;
            this.serviceType = serviceType;
            this.port = port;
            this.txtRecord = txtRecord;
        }

        String fullName() {
            return instanceName + "." + serviceType;
        }
    }

    private final InetAddress address;
    private final NetworkInterface networkInterface;
    private final String hostName;
    private final List<Service> services;

    private volatile MulticastSocket socket;
    private Thread thread;
    private volatile boolean running;

    public MulticastDnsResponder(InetAddress address, NetworkInterface networkInterface,
                                 String hostName, List<Service> services) {
        this.address = address;
        this.networkInterface = networkInterface;
        this.hostName = hostName;
        this.services = services;
    }

    /** Encodes key/value pairs as DNS-SD TXT record data (length-prefixed "key=value" strings). */
    public static byte[] encodeTxtRecord(Map<String, String> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            byte[] item = (entry.getKey() + "=" + entry.getValue()).getBytes(UTF_8);
            out.write(Math.min(item.length, 255));
            out.write(item, 0, Math.min(item.length, 255));
        }
        return out.toByteArray();
    }

    public synchronized void start() throws IOException {
        socket = new MulticastSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(PORT));
        socket.setTimeToLive(255);
        if (networkInterface != null) {
            socket.setNetworkInterface(networkInterface);
            socket.joinGroup(new InetSocketAddress(InetAddress.getByName(GROUP), PORT), networkInterface);
        } else {
            socket.joinGroup(InetAddress.getByName(GROUP));
        }
        socket.setSoTimeout(1000);
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                serve();
            }
        }, "MulticastDnsResponder");
        thread.start();
        ReceiverLog.i(TAG, "Responder started for " + hostName + " at " + address.getHostAddress());
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) {
            try {
                sendMulticast(buildAnnouncement(0));
            } catch (Exception error) {
                ReceiverLog.w(TAG, "Goodbye announcement failed", error);
            }
            socket.close();
            socket = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void serve() {
        // Initial announcements, spaced one and two seconds apart as RFC 6762 section 8.3 recommends.
        long[] announcementTimes = {0, 1000, 3000};
        int announcementIndex = 0;
        long startTime = System.currentTimeMillis();
        byte[] buffer = new byte[9000];
        while (running) {
            long elapsed = System.currentTimeMillis() - startTime;
            if (announcementIndex < announcementTimes.length && elapsed >= announcementTimes[announcementIndex]) {
                try {
                    sendMulticast(buildAnnouncement(-1));
                } catch (IOException error) {
                    ReceiverLog.w(TAG, "Announcement failed", error);
                }
                announcementIndex++;
            }
            MulticastSocket current = socket;
            if (current == null) {
                break;
            }
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                current.receive(packet);
            } catch (SocketTimeoutException timeout) {
                continue;
            } catch (IOException error) {
                if (running) {
                    ReceiverLog.w(TAG, "Receive failed", error);
                }
                continue;
            }
            try {
                handleQuery(packet);
            } catch (Exception error) {
                ReceiverLog.d(TAG, "Ignoring malformed packet: " + error);
            }
        }
    }

    private void sendMulticast(byte[] message) throws IOException {
        MulticastSocket current = socket;
        if (current == null) {
            return;
        }
        current.send(new DatagramPacket(message, message.length, InetAddress.getByName(GROUP), PORT));
    }

    // ------------------------------------------------------------- query handling

    private void handleQuery(DatagramPacket packet) throws IOException {
        byte[] data = packet.getData();
        int length = packet.getLength();
        if (length < 12) {
            return;
        }
        int flags = readShort(data, 2);
        if ((flags & 0x8000) != 0) {
            return; // a response, not a query
        }
        int questionCount = readShort(data, 4);
        int position = 12;

        Message answer = new Message();
        boolean unicastRequested = false;
        for (int i = 0; i < questionCount && position < length; i++) {
            StringBuilder name = new StringBuilder();
            position = readName(data, position, length, name);
            int type = readShort(data, position);
            int questionClass = readShort(data, position + 2);
            position += 4;
            if ((questionClass & 0x8000) != 0) {
                unicastRequested = true;
            }
            appendAnswers(name.toString().toLowerCase(Locale.US), type, answer);
        }
        if (answer.isEmpty()) {
            return;
        }

        boolean legacyUnicast = packet.getPort() != PORT;
        byte[] response = answer.encode(legacyUnicast ? readShort(data, 0) : 0, legacyUnicast);
        MulticastSocket current = socket;
        if (current != null && (legacyUnicast || unicastRequested)) {
            current.send(new DatagramPacket(response, response.length, packet.getAddress(), packet.getPort()));
        }
        if (!legacyUnicast) {
            sendMulticast(response);
        }
    }

    private void appendAnswers(String name, int type, Message message) {
        String host = hostName.toLowerCase(Locale.US);
        if (name.equals(host) && (type == TYPE_A || type == TYPE_ANY)) {
            message.addAddress(hostName, HOST_TTL, address, false);
        }
        if (name.equals(SERVICE_ENUMERATION) && (type == TYPE_PTR || type == TYPE_ANY)) {
            for (Service service : services) {
                message.addPointer(SERVICE_ENUMERATION, SERVICE_TTL, service.serviceType);
            }
        }
        for (Service service : services) {
            String serviceType = service.serviceType.toLowerCase(Locale.US);
            String fullName = service.fullName().toLowerCase(Locale.US);
            if (name.equals(serviceType) && (type == TYPE_PTR || type == TYPE_ANY)) {
                message.addPointer(service.serviceType, SERVICE_TTL, service.fullName());
                message.addService(service, hostName, true);
                message.addText(service, true);
                message.addAddress(hostName, HOST_TTL, address, true);
            } else if (name.equals(fullName)) {
                if (type == TYPE_SRV || type == TYPE_ANY) {
                    message.addService(service, hostName, false);
                    message.addAddress(hostName, HOST_TTL, address, true);
                }
                if (type == TYPE_TXT || type == TYPE_ANY) {
                    message.addText(service, false);
                }
            }
        }
    }

    /** Builds an unsolicited announcement; a ttl of 0 produces a goodbye packet. */
    private byte[] buildAnnouncement(int ttlOverride) {
        Message message = new Message();
        message.ttlOverride = ttlOverride;
        for (Service service : services) {
            message.addPointer(service.serviceType, SERVICE_TTL, service.fullName());
            message.addService(service, hostName, false);
            message.addText(service, false);
        }
        message.addAddress(hostName, HOST_TTL, address, false);
        return message.encode(0, false);
    }

    // ------------------------------------------------------------- message building

    private static final class Record {
        String name;
        int type;
        int ttl;
        boolean cacheFlush;
        boolean additional;
        byte[] rdata;
        String rdataName;
        int srvPort;
    }

    private static final class Message {
        final List<Record> records = new ArrayList<>();
        int ttlOverride = -1;

        boolean isEmpty() {
            return records.isEmpty();
        }

        /** Returns the existing record of this name and type, or a new one marked as additional or not. */
        Record add(String name, int type, int ttl, boolean cacheFlush, boolean additional) {
            Record existing = find(name, type);
            if (existing != null) {
                existing.additional = existing.additional && additional;
                return existing;
            }
            Record record = new Record();
            record.name = name;
            record.type = type;
            record.ttl = ttl;
            record.cacheFlush = cacheFlush;
            record.additional = additional;
            records.add(record);
            return record;
        }

        void addAddress(String hostName, int ttl, InetAddress address, boolean additional) {
            add(hostName, TYPE_A, ttl, true, additional).rdata = address.getAddress();
        }

        void addPointer(String name, int ttl, String target) {
            for (Record existing : records) {
                if (existing.type == TYPE_PTR && existing.name.equalsIgnoreCase(name)
                        && existing.rdataName.equalsIgnoreCase(target)) {
                    return;
                }
            }
            Record record = new Record();
            record.name = name;
            record.type = TYPE_PTR;
            record.ttl = ttl;
            record.rdataName = target;
            records.add(record);
        }

        void addService(Service service, String hostName, boolean additional) {
            Record record = add(service.fullName(), TYPE_SRV, HOST_TTL, true, additional);
            record.rdataName = hostName;
            record.srvPort = service.port;
        }

        void addText(Service service, boolean additional) {
            add(service.fullName(), TYPE_TXT, SERVICE_TTL, true, additional).rdata = service.txtRecord;
        }

        Record find(String name, int type) {
            for (Record record : records) {
                if (record.type == type && record.name.equalsIgnoreCase(name)) {
                    return record;
                }
            }
            return null;
        }

        byte[] encode(int transactionId, boolean legacyUnicast) {
            List<Record> answers = new ArrayList<>();
            List<Record> additionals = new ArrayList<>();
            for (Record record : records) {
                (record.additional ? additionals : answers).add(record);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeShort(out, transactionId);
            writeShort(out, 0x8400); // response, authoritative answer
            writeShort(out, 0);
            writeShort(out, answers.size());
            writeShort(out, 0);
            writeShort(out, additionals.size());
            for (Record record : answers) writeRecord(out, record, legacyUnicast);
            for (Record record : additionals) writeRecord(out, record, legacyUnicast);
            return out.toByteArray();
        }

        private void writeRecord(ByteArrayOutputStream out, Record record, boolean legacyUnicast) {
            writeName(out, record.name);
            writeShort(out, record.type);
            int recordClass = CLASS_IN;
            if (record.cacheFlush && !legacyUnicast) {
                recordClass |= CACHE_FLUSH;
            }
            writeShort(out, recordClass);
            int ttl = ttlOverride >= 0 ? ttlOverride : record.ttl;
            if (legacyUnicast) {
                ttl = Math.min(ttl, 10);
            }
            writeInt(out, ttl);

            ByteArrayOutputStream rdata = new ByteArrayOutputStream();
            switch (record.type) {
                case TYPE_A:
                case TYPE_TXT:
                    byte[] bytes = record.rdata.length == 0 ? new byte[]{0} : record.rdata;
                    rdata.write(bytes, 0, bytes.length);
                    break;
                case TYPE_PTR:
                    writeName(rdata, record.rdataName);
                    break;
                case TYPE_SRV:
                    writeShort(rdata, 0);
                    writeShort(rdata, 0);
                    writeShort(rdata, record.srvPort);
                    writeName(rdata, record.rdataName);
                    break;
                default:
                    break;
            }
            writeShort(out, rdata.size());
            byte[] encoded = rdata.toByteArray();
            out.write(encoded, 0, encoded.length);
        }
    }

    // ------------------------------------------------------------- wire helpers

    /**
     * Splits a service name into DNS labels. The instance label may itself contain dots, so the
     * service type suffix ("_x._tcp.local") is split on dots and the rest is kept as one label.
     */
    static List<String> labels(String name) {
        List<String> result = new ArrayList<>();
        int serviceIndex = name.indexOf("._");
        if (serviceIndex > 0 && !name.startsWith("_")) {
            result.add(name.substring(0, serviceIndex));
            name = name.substring(serviceIndex + 1);
        }
        for (String label : name.split("\\.")) {
            if (!label.isEmpty()) {
                result.add(label);
            }
        }
        return result;
    }

    private static void writeName(ByteArrayOutputStream out, String name) {
        for (String label : labels(name)) {
            byte[] bytes = label.getBytes(UTF_8);
            int length = Math.min(bytes.length, 63);
            out.write(length);
            out.write(bytes, 0, length);
        }
        out.write(0);
    }

    private static int readName(byte[] data, int position, int limit, StringBuilder name) throws IOException {
        int end = -1;
        int jumps = 0;
        while (true) {
            if (position >= limit) {
                throw new IOException("Name runs past packet");
            }
            int length = data[position] & 0xFF;
            if (length == 0) {
                position++;
                break;
            }
            if ((length & 0xC0) == 0xC0) {
                if (++jumps > 16) {
                    throw new IOException("Name compression loop");
                }
                int pointer = ((length & 0x3F) << 8) | (data[position + 1] & 0xFF);
                if (end < 0) {
                    end = position + 2;
                }
                position = pointer;
                continue;
            }
            if (name.length() > 0) {
                name.append('.');
            }
            name.append(new String(data, position + 1, length, UTF_8));
            position += 1 + length;
        }
        return end >= 0 ? end : position;
    }

    private static int readShort(byte[] data, int position) {
        return ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        writeShort(out, (value >> 16) & 0xFFFF);
        writeShort(out, value & 0xFFFF);
    }

    public static boolean isUsableAddress(InetAddress address) {
        return address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress();
    }
}
