package io.github.airplayreceiver.protocol;

/** Minimal logging facade so the protocol package runs on Android and on a desktop JVM. */
public final class ReceiverLog {

    public interface Sink {
        void write(int level, String tag, String message, Throwable error);
    }

    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;

    private static volatile Sink sink = new Sink() {
        @Override
        public void write(int level, String tag, String message, Throwable error) {
            System.out.println(tag + ": " + message);
            if (error != null) {
                error.printStackTrace(System.out);
            }
        }
    };

    private ReceiverLog() {
    }

    public static void setSink(Sink newSink) {
        sink = newSink;
    }

    public static void d(String tag, String message) {
        sink.write(DEBUG, tag, message, null);
    }

    public static void i(String tag, String message) {
        sink.write(INFO, tag, message, null);
    }

    public static void w(String tag, String message) {
        sink.write(WARN, tag, message, null);
    }

    public static void w(String tag, String message, Throwable error) {
        sink.write(WARN, tag, message, error);
    }

    public static void e(String tag, String message, Throwable error) {
        sink.write(ERROR, tag, message, error);
    }

    public static String hex(byte[] data, int offset, int length) {
        StringBuilder builder = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            builder.append(String.format("%02x", data[offset + i] & 0xFF));
        }
        return builder.toString();
    }

    public static String hex(byte[] data) {
        return hex(data, 0, data.length);
    }
}
