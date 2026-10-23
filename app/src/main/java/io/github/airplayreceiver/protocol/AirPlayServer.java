package io.github.airplayreceiver.protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The AirPlay control server. One TCP port serves both RTSP (pairing, FairPlay, stream SETUP) and
 * HTTP (photos). Only one client streams at a time; a new client takes over from the previous one.
 */
public final class AirPlayServer {

    private static final String TAG = "AirPlayServer";
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private final ReceiverConfiguration configuration;
    private final ReceiverListener listener;
    private final byte[] airPlayTxtRecord;
    private final byte[] raopTxtRecord;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private final List<Connection> connections = new ArrayList<>();
    private Connection streamingConnection;
    /** At most one audio stream may feed the speaker, whichever connection set it up. */
    private AudioStreamReceiver activeAudioReceiver;
    /** Separate from the server lock, which is taken before connection locks; this one is taken after. */
    private final Object audioLock = new Object();

    public AirPlayServer(ReceiverConfiguration configuration, ReceiverListener listener) {
        this.configuration = configuration;
        this.listener = listener;
        airPlayTxtRecord = MulticastDnsResponder.encodeTxtRecord(configuration.airPlayTxt());
        raopTxtRecord = MulticastDnsResponder.encodeTxtRecord(configuration.raopTxt());
    }

    public byte[] airPlayTxtRecord() {
        return airPlayTxtRecord;
    }

    public byte[] raopTxtRecord() {
        return raopTxtRecord;
    }

    public synchronized void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(configuration.controlPort));
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "AirPlayAccept");
        acceptThread.start();
        ReceiverLog.i(TAG, "Listening on port " + configuration.controlPort);
    }

    public void stop() {
        List<Connection> open;
        synchronized (this) {
            running = false;
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (IOException ignored) {
            }
            open = new ArrayList<>(connections);
        }
        for (Connection connection : open) {
            connection.close();
        }
    }

    /** Ends the current client's streams, for example when the user presses Back on the remote. */
    public void disconnectStreamingClient() {
        Connection current;
        synchronized (this) {
            current = streamingConnection;
        }
        if (current != null) {
            current.close();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                final Connection connection = new Connection(socket);
                synchronized (this) {
                    connections.add(connection);
                }
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        connection.run();
                    }
                }, "AirPlayConnection-" + socket.getInetAddress().getHostAddress());
                thread.start();
            } catch (IOException error) {
                if (running) {
                    ReceiverLog.w(TAG, "Accept failed", error);
                }
            }
        }
    }

    private synchronized void claimStreaming(Connection connection) {
        Connection previous = streamingConnection;
        streamingConnection = connection;
        if (previous != null && previous != connection) {
            ReceiverLog.i(TAG, "New client takes over from " + previous.remoteAddress);
            previous.stopStreams();
        }
    }

    private void activateAudioReceiver(AudioStreamReceiver receiver) {
        synchronized (audioLock) {
            if (activeAudioReceiver != null && activeAudioReceiver != receiver && activeAudioReceiver.isRunning()) {
                ReceiverLog.w(TAG, "Stopping a second audio stream that was still running");
                activeAudioReceiver.stop();
            }
            activeAudioReceiver = receiver;
        }
    }

    private synchronized boolean releaseStreaming(Connection connection) {
        connections.remove(connection);
        if (streamingConnection == connection) {
            streamingConnection = null;
            return true;
        }
        return false;
    }

    // --------------------------------------------------------------------- connection

    private final class Connection {
        final Socket socket;
        final String remoteAddress;
        final LegacyPairing pairing;
        final io.github.airplayreceiver.protocol.fairplay.FairPlay fairPlay =
                new io.github.airplayreceiver.protocol.fairplay.FairPlay();

        byte[] sessionKey;
        byte[] audioInitializationVector;
        TimingClient timingClient;
        MirrorStreamReceiver mirrorReceiver;
        AudioStreamReceiver audioReceiver;
        boolean announcedClient;
        volatile boolean closed;

        Connection(Socket socket) {
            this.socket = socket;
            this.remoteAddress = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
            this.pairing = new LegacyPairing(configuration.identitySeed);
        }

        void run() {
            ReceiverLog.i(TAG, "Connection from " + remoteAddress);
            try {
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                while (!closed) {
                    ControlMessage request = ControlMessage.read(in);
                    if (request == null) {
                        break;
                    }
                    ControlMessage.Response response = new ControlMessage.Response();
                    try {
                        handle(request, response);
                    } catch (Exception error) {
                        ReceiverLog.e(TAG, "Failed to handle " + request.method + " " + request.path, error);
                        response.status(500, "Internal Server Error");
                    }
                    boolean rtsp = request.protocol.startsWith("RTSP");
                    if (rtsp) {
                        response.headers.put("Server", "AirTunes/" + ReceiverConfiguration.SOURCE_VERSION);
                        String sequence = request.header("CSeq");
                        if (sequence != null) {
                            response.headers.put("CSeq", sequence);
                        }
                    }
                    response.write(out, request.protocol);
                    if (response.closeAfterSending) {
                        break;
                    }
                }
            } catch (IOException error) {
                if (!closed) {
                    ReceiverLog.d(TAG, "Connection " + remoteAddress + " ended: " + error);
                }
            } finally {
                close();
            }
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            boolean wasStreaming = stopStreams();
            if (releaseStreaming(this) || wasStreaming) {
                listener.onClientDisconnected();
            }
            ReceiverLog.i(TAG, "Connection from " + remoteAddress + " closed");
        }

        /** Stops every stream this connection started. Returns true when any was running. */
        synchronized boolean stopStreams() {
            boolean any = false;
            if (mirrorReceiver != null) {
                mirrorReceiver.stop();
                mirrorReceiver = null;
                listener.onMirroringStopped();
                any = true;
            }
            if (audioReceiver != null) {
                audioReceiver.stop();
                audioReceiver = null;
                listener.onAudioStopped();
                any = true;
            }
            if (timingClient != null) {
                timingClient.stop();
                timingClient = null;
            }
            return any;
        }

        private void handle(ControlMessage request, ControlMessage.Response response) throws Exception {
            String method = request.method;
            String path = request.path;
            if (!path.equals("/feedback") && !method.equals("GET_PARAMETER")) {
                ReceiverLog.i(TAG, remoteAddress + " " + method + " " + path + " (" + request.body.length + " bytes)"
                        + describeStreams(request));
            }
            if (request.protocol.startsWith("RTSP") && request.header("CSeq") != null && !method.equals("RECORD")) {
                response.header("Audio-Jack-Status", "connected; type=digital");
            }

            if (method.equals("GET") && path.startsWith("/info")) {
                response.body("application/x-apple-binary-plist", BinaryPropertyList.write(info(request)));
            } else if (method.equals("POST") && path.equals("/pair-setup")) {
                if (request.body.length != 32) {
                    ReceiverLog.w(TAG, "pair-setup with " + request.body.length + " bytes (PIN pairing is not supported)");
                }
                response.body("application/octet-stream", pairing.pairSetup());
            } else if (method.equals("POST") && path.equals("/pair-verify")) {
                byte[] reply = pairing.pairVerify(request.body);
                if (request.body.length > 0 && request.body[0] == 0 && !pairing.isVerified()) {
                    response.status(470, "Connection Authorization Required");
                    response.closeAfterSending = true;
                }
                response.body("application/octet-stream", reply);
            } else if (method.equals("POST") && path.equals("/fp-setup")) {
                byte[] reply = fairPlay.setup(request.body);
                if (reply == null) {
                    ReceiverLog.w(TAG, "Unsupported FairPlay request of " + request.body.length + " bytes, type "
                            + (request.body.length > 4 ? request.body[4] : -1));
                    response.status(501, "Not Implemented");
                } else {
                    response.body("application/octet-stream", reply);
                }
            } else if (method.equals("OPTIONS")) {
                response.header("Public", "SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER");
            } else if (method.equals("SETUP")) {
                setup(request, response);
            } else if (method.equals("RECORD")) {
                response.header("Audio-Latency", "11025");
                response.header("Audio-Jack-Status", "connected; type=analog");
            } else if (method.equals("GET_PARAMETER")) {
                String text = new String(request.body, ASCII);
                if (text.contains("volume")) {
                    response.body("text/parameters", "volume: 0.000000\r\n".getBytes(ASCII));
                }
            } else if (method.equals("SET_PARAMETER")) {
                setParameter(request);
            } else if (method.equals("FLUSH")) {
                synchronized (this) {
                    if (audioReceiver != null) {
                        audioReceiver.flush();
                    }
                }
                listener.onAudioFlush();
            } else if (method.equals("TEARDOWN")) {
                teardown(request);
            } else if (method.equals("POST") && (path.equals("/feedback") || path.equals("/audioMode"))) {
                // Heartbeat and audio mode hints need only an acknowledgement.
            } else if (method.equals("POST") && path.equals("/reverse")) {
                response.status(101, "Switching Protocols");
                response.header("Upgrade", "PTTH/1.0");
                response.header("Connection", "Upgrade");
            } else if (method.equals("GET") && path.equals("/server-info")) {
                response.body("text/x-apple-plist+xml", serverInfoXml().getBytes(Charset.forName("UTF-8")));
            } else if (method.equals("GET") && path.equals("/slideshow-features")) {
                response.body("text/x-apple-plist+xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<plist version=\"1.0\"><dict><key>themes</key><array/></dict></plist>\n").getBytes(ASCII));
            } else if (method.equals("PUT") && path.startsWith("/photo")) {
                String action = request.header("X-Apple-AssetAction");
                if (request.body.length > 0 && (action == null || !action.equals("cacheOnly"))) {
                    listener.onPhoto(request.body);
                } else if ("displayCached".equals(action)) {
                    response.status(412, "Precondition Failed");
                }
            } else if (method.equals("POST") && path.equals("/stop")) {
                listener.onPhotoStopped();
            } else {
                ReceiverLog.i(TAG, "Unhandled request " + method + " " + path + " " + request.protocol);
                response.status(request.protocol.startsWith("RTSP") ? 200 : 404,
                        request.protocol.startsWith("RTSP") ? "OK" : "Not Found");
            }
        }

        private Map<String, Object> info(ControlMessage request) {
            Map<String, Object> info = new LinkedHashMap<>();
            String qualifier = null;
            String contentType = request.header("Content-Type");
            if (contentType != null && contentType.contains("apple-binary-plist") && request.body.length > 0) {
                Object list = BinaryPropertyList.parseDictionary(request.body).get("qualifier");
                if (list instanceof List && !((List<?>) list).isEmpty()) {
                    qualifier = String.valueOf(((List<?>) list).get(0));
                }
            }
            if ("txtAirPlay".equals(qualifier)) {
                info.put("txtAirPlay", airPlayTxtRecord);
            } else if ("txtRAOP".equals(qualifier)) {
                info.put("txtRAOP", raopTxtRecord);
            }
            if (contentType != null) {
                return info;
            }

            String deviceIdentifier = configuration.deviceIdentifierText(true);
            info.put("deviceID", deviceIdentifier);
            info.put("macAddress", deviceIdentifier);
            info.put("pk", LegacyPairing.publicKeyForSeed(configuration.identitySeed));
            info.put("features", ReceiverConfiguration.FEATURES);
            info.put("name", configuration.name);
            info.put("pi", ReceiverConfiguration.PAIRING_IDENTIFIER);
            info.put("vv", 2L);
            info.put("statusFlags", 68L);
            info.put("keepAliveLowPower", Boolean.TRUE);
            info.put("sourceVersion", ReceiverConfiguration.SOURCE_VERSION);
            info.put("keepAliveSendStatsAsBody", Boolean.TRUE);
            info.put("model", ReceiverConfiguration.MODEL);
            if (request.header("CSeq") == null) {
                return info;
            }
            info.put("initialVolume", -15.0);

            List<Object> latencies = new ArrayList<>();
            for (long type : new long[]{100, 101}) {
                Map<String, Object> latency = new LinkedHashMap<>();
                latency.put("type", type);
                latency.put("inputLatencyMicros", 0L);
                latency.put("audioType", "default");
                latency.put("outputLatencyMicros", Boolean.FALSE);
                latencies.add(latency);
            }
            info.put("audioLatencies", latencies);

            List<Object> formats = new ArrayList<>();
            for (long type : new long[]{100, 101}) {
                Map<String, Object> format = new LinkedHashMap<>();
                format.put("audioOutputFormats", 0x3fffffcL);
                format.put("type", type);
                format.put("audioInputFormats", 0x3fffffcL);
                formats.add(format);
            }
            info.put("audioFormats", formats);

            Map<String, Object> display = new LinkedHashMap<>();
            display.put("uuid", ReceiverConfiguration.DISPLAY_IDENTIFIER);
            display.put("widthPhysical", 0L);
            display.put("heightPhysical", 0L);
            display.put("width", (long) configuration.displayWidth);
            display.put("height", (long) configuration.displayHeight);
            display.put("widthPixels", (long) configuration.displayWidth);
            display.put("heightPixels", (long) configuration.displayHeight);
            display.put("rotation", Boolean.FALSE);
            display.put("refreshRate", 1.0 / 60.0);
            display.put("maxFPS", (long) configuration.maximumFramesPerSecond);
            display.put("overscanned", Boolean.FALSE);
            display.put("features", 14L);
            List<Object> displays = new ArrayList<>();
            displays.add(display);
            info.put("displays", displays);
            return info;
        }

        private String serverInfoXml() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                    + "<plist version=\"1.0\"><dict>"
                    + "<key>deviceid</key><string>" + configuration.deviceIdentifierText(true) + "</string>"
                    + "<key>features</key><integer>" + ReceiverConfiguration.FEATURES + "</integer>"
                    + "<key>macAddress</key><string>" + configuration.deviceIdentifierText(true) + "</string>"
                    + "<key>model</key><string>" + ReceiverConfiguration.MODEL + "</string>"
                    + "<key>protovers</key><string>1.0</string>"
                    + "<key>srcvers</key><string>" + ReceiverConfiguration.SOURCE_VERSION + "</string>"
                    + "</dict></plist>\n";
        }

        private void setup(ControlMessage request, ControlMessage.Response response) throws Exception {
            Map<String, Object> body = BinaryPropertyList.parseDictionary(request.body);
            Map<String, Object> reply = new LinkedHashMap<>();

            if (body.get("ekey") instanceof byte[] && body.get("eiv") instanceof byte[]) {
                if (!fairPlay.isReady()) {
                    throw new IllegalStateException("SETUP before FairPlay handshake");
                }
                claimStreaming(this);
                stopStreams();
                byte[] fairPlayKey = fairPlay.decryptAesKey((byte[]) body.get("ekey"));
                sessionKey = MediaKeys.sessionKey(fairPlayKey, pairing.getSharedSecret());
                audioInitializationVector = (byte[]) body.get("eiv");

                String clientName = stringValue(body.get("name"), "AirPlay client");
                String clientModel = stringValue(body.get("model"), "");
                ReceiverLog.i(TAG, "Session from " + clientName + " (" + clientModel + "), timing protocol "
                        + body.get("timingProtocol") + ", pairing " + (pairing.getSharedSecret() != null));

                int clientTimingPort = (int) longValue(body.get("timingPort"));
                synchronized (this) {
                    timingClient = new TimingClient(socket.getInetAddress(), clientTimingPort);
                    timingClient.start();
                    reply.put("timingPort", (long) timingClient.localPort());
                }
                reply.put("eventPort", 0L);
                if (!announcedClient) {
                    announcedClient = true;
                    listener.onClientConnected(clientName, clientModel);
                }
            }

            Object streams = body.get("streams");
            if (streams instanceof List) {
                if (sessionKey == null) {
                    throw new IllegalStateException("Stream SETUP before session SETUP");
                }
                List<Object> replyStreams = new ArrayList<>();
                for (Object item : (List<?>) streams) {
                    if (!(item instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> stream = (Map<String, Object>) item;
                    long type = longValue(stream.get("type"));
                    Map<String, Object> replyStream = new LinkedHashMap<>();
                    if (type == 110) {
                        long connectionIdentifier = longValue(stream.get("streamConnectionID"));
                        MediaKeys.MirrorDecryptor decryptor = new MediaKeys.MirrorDecryptor(sessionKey, connectionIdentifier);
                        synchronized (this) {
                            if (mirrorReceiver != null) mirrorReceiver.stop();
                            mirrorReceiver = new MirrorStreamReceiver(decryptor, listener);
                            mirrorReceiver.start();
                            replyStream.put("dataPort", (long) mirrorReceiver.localPort());
                        }
                        replyStream.put("type", 110L);
                        listener.onMirroringStarted();
                        ReceiverLog.i(TAG, "Mirroring stream on port " + replyStream.get("dataPort"));
                    } else if (type == 96) {
                        int compressionType = (int) longValue(stream.get("ct"));
                        int samplesPerFrame = (int) longValue(stream.get("spf"));
                        boolean usingScreen = Boolean.TRUE.equals(stream.get("usingScreen"));
                        int clientControlPort = (int) longValue(stream.get("controlPort"));
                        ReceiverLog.i(TAG, "Audio stream ct=" + compressionType + " spf=" + samplesPerFrame
                                + " audioFormat=0x" + Long.toHexString(longValue(stream.get("audioFormat")))
                                + " usingScreen=" + usingScreen + " isMedia=" + stream.get("isMedia"));
                        // Mirroring audio wants low latency; music tolerates a deeper reorder window.
                        int reorderWindow = (compressionType == 8) ? 6 : 24;
                        MediaKeys.AudioDecryptor decryptor = new MediaKeys.AudioDecryptor(sessionKey, audioInitializationVector);
                        listener.onAudioStarted(compressionType, samplesPerFrame, usingScreen || compressionType == 8);
                        synchronized (this) {
                            if (audioReceiver != null) audioReceiver.stop();
                            audioReceiver = new AudioStreamReceiver(decryptor, listener, socket.getInetAddress(),
                                    clientControlPort, reorderWindow);
                            activateAudioReceiver(audioReceiver);
                            audioReceiver.start();
                            replyStream.put("dataPort", (long) audioReceiver.dataPort());
                            replyStream.put("controlPort", (long) audioReceiver.controlPort());
                        }
                        replyStream.put("type", 96L);
                    } else {
                        ReceiverLog.w(TAG, "SETUP for unknown stream type " + type);
                        continue;
                    }
                    replyStreams.add(replyStream);
                }
                reply.put("streams", replyStreams);
            }
            response.body("application/x-apple-binary-plist", BinaryPropertyList.write(reply));
        }

        private void setParameter(ControlMessage request) {
            String contentType = request.header("Content-Type");
            if (contentType == null) {
                return;
            }
            if (contentType.startsWith("text/parameters")) {
                String text = new String(request.body, ASCII).trim();
                if (text.startsWith("volume:")) {
                    try {
                        listener.onVolume(Float.parseFloat(text.substring(7).trim()));
                    } catch (NumberFormatException error) {
                        ReceiverLog.w(TAG, "Bad volume parameter: " + text);
                    }
                }
            }
        }

        private void teardown(ControlMessage request) {
            Map<String, Object> body = BinaryPropertyList.parseDictionary(request.body);
            Object streams = body.get("streams");
            boolean teardownAudio = false;
            boolean teardownVideo = false;
            if (streams instanceof List) {
                for (Object item : (List<?>) streams) {
                    if (item instanceof Map) {
                        long type = longValue(((Map<?, ?>) item).get("type"));
                        teardownAudio |= type == 96;
                        teardownVideo |= type == 110;
                    }
                }
            } else {
                teardownAudio = true;
                teardownVideo = true;
            }
            synchronized (this) {
                if (teardownAudio && audioReceiver != null) {
                    audioReceiver.stop();
                    audioReceiver = null;
                    listener.onAudioStopped();
                }
                if (teardownVideo && mirrorReceiver != null) {
                    mirrorReceiver.stop();
                    mirrorReceiver = null;
                    listener.onMirroringStopped();
                }
                if (audioReceiver == null && mirrorReceiver == null && timingClient != null) {
                    timingClient.stop();
                    timingClient = null;
                }
            }
        }
    }

    /** Names the stream types in a SETUP or TEARDOWN body, for the log. */
    private static String describeStreams(ControlMessage request) {
        if (!(request.method.equals("SETUP") || request.method.equals("TEARDOWN")) || request.body.length == 0) {
            return "";
        }
        try {
            Map<String, Object> body = BinaryPropertyList.parseDictionary(request.body);
            StringBuilder text = new StringBuilder(" keys=").append(body.keySet());
            Object streams = body.get("streams");
            if (streams instanceof List) {
                text.append(" streams=");
                for (Object item : (List<?>) streams) {
                    if (item instanceof Map) text.append(((Map<?, ?>) item).get("type")).append(' ');
                }
            }
            return text.toString();
        } catch (RuntimeException error) {
            return " (unparsed body)";
        }
    }

    private static long longValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static String stringValue(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }
}
