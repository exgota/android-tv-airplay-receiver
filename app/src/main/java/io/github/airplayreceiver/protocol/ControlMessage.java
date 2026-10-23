package io.github.airplayreceiver.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** An RTSP/1.0 or HTTP/1.1 request as sent on the AirPlay control connection, and its response. */
public final class ControlMessage {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final int MAXIMUM_BODY = 32 * 1024 * 1024;

    public final String method;
    public final String path;
    public final String protocol;
    public final Map<String, String> headers;
    public final byte[] body;

    private ControlMessage(String method, String path, String protocol, Map<String, String> headers, byte[] body) {
        this.method = method;
        this.path = path;
        this.protocol = protocol;
        this.headers = headers;
        this.body = body;
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.US));
    }

    /** Reads one request, or returns {@code null} at end of stream. */
    public static ControlMessage read(InputStream in) throws IOException {
        String requestLine = readLine(in);
        while (requestLine != null && requestLine.isEmpty()) {
            requestLine = readLine(in);
        }
        if (requestLine == null) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 3) {
            throw new IOException("Malformed request line: " + requestLine);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
            }
        }
        int contentLength = 0;
        String lengthHeader = headers.get("content-length");
        if (lengthHeader != null) {
            contentLength = Integer.parseInt(lengthHeader.trim());
        }
        if (contentLength < 0 || contentLength > MAXIMUM_BODY) {
            throw new IOException("Unreasonable content length " + contentLength);
        }
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int count = in.read(body, read, contentLength - read);
            if (count < 0) {
                throw new IOException("Connection closed inside request body");
            }
            read += count;
        }
        return new ControlMessage(parts[0], parts[1], parts[parts.length - 1], headers, body);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        while (true) {
            int value = in.read();
            if (value < 0) {
                return line.size() == 0 ? null : line.toString("US-ASCII");
            }
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                line.write(value);
            }
            if (line.size() > 16384) {
                throw new IOException("Header line too long");
            }
        }
        return line.toString("UTF-8");
    }

    /** A response under construction. */
    public static final class Response {
        public int status = 200;
        public String reason = "OK";
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];
        public boolean closeAfterSending;

        public Response status(int status, String reason) {
            this.status = status;
            this.reason = reason;
            return this;
        }

        public Response header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Response body(String contentType, byte[] content) {
            headers.put("Content-Type", contentType);
            body = content;
            return this;
        }

        public void write(OutputStream out, String protocol) throws IOException {
            StringBuilder head = new StringBuilder();
            head.append(protocol).append(' ').append(status).append(' ').append(reason).append("\r\n");
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
            head.append("Content-Length: ").append(body.length).append("\r\n\r\n");
            out.write(head.toString().getBytes(ASCII));
            out.write(body);
            out.flush();
        }
    }
}
