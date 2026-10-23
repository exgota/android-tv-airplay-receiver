package io.github.airplayreceiver.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reader and writer for Apple binary property lists ("bplist00").
 *
 * Values map to Java types as follows: dictionary to {@code Map<String, Object>},
 * array to {@code List<Object>}, integer to {@code Long}, real to {@code Double},
 * boolean to {@code Boolean}, data to {@code byte[]}, string to {@code String}.
 */
public final class BinaryPropertyList {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Charset UTF_16BE = Charset.forName("UTF-16BE");

    private BinaryPropertyList() {
    }

    // ---------------------------------------------------------------- reading

    public static Object parse(byte[] data) {
        return parse(data, 0, data.length);
    }

    public static Object parse(byte[] data, int offset, int length) {
        if (length < 40 || !new String(data, offset, 8, ASCII).equals("bplist00")) {
            throw new IllegalArgumentException("Not a binary property list");
        }
        Reader reader = new Reader(data, offset, length);
        return reader.readObject(reader.topObject, 0);
    }

    /** Returns the parsed dictionary, or an empty map when the body is empty or not a dictionary. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseDictionary(byte[] data) {
        if (data == null || data.length == 0) {
            return new LinkedHashMap<>();
        }
        Object value = parse(data);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<>();
    }

    private static final class Reader {
        private final byte[] data;
        private final int base;
        private final int offsetSize;
        private final int referenceSize;
        private final int objectCount;
        private final int topObject;
        private final int offsetTable;

        Reader(byte[] data, int base, int length) {
            this.data = data;
            this.base = base;
            int trailer = base + length - 32;
            offsetSize = data[trailer + 6] & 0xFF;
            referenceSize = data[trailer + 7] & 0xFF;
            objectCount = (int) readUnsigned(trailer + 8, 8);
            topObject = (int) readUnsigned(trailer + 16, 8);
            offsetTable = (int) readUnsigned(trailer + 24, 8);
        }

        private long readUnsigned(int position, int size) {
            long value = 0;
            for (int i = 0; i < size; i++) {
                value = (value << 8) | (data[position + i] & 0xFF);
            }
            return value;
        }

        private int objectOffset(int index) {
            if (index < 0 || index >= objectCount) {
                throw new IllegalArgumentException("Object reference out of range: " + index);
            }
            return base + (int) readUnsigned(base + offsetTable + index * offsetSize, offsetSize);
        }

        /** Returns {length, headerSize} for container and string markers. */
        private int[] readLength(int position) {
            int lowNibble = data[position] & 0x0F;
            if (lowNibble != 0x0F) {
                return new int[]{lowNibble, 1};
            }
            int intMarker = data[position + 1] & 0xFF;
            int byteCount = 1 << (intMarker & 0x0F);
            return new int[]{(int) readUnsigned(position + 2, byteCount), 2 + byteCount};
        }

        Object readObject(int index, int depth) {
            if (depth > 64) {
                throw new IllegalArgumentException("Property list nesting too deep");
            }
            int position = objectOffset(index);
            int marker = data[position] & 0xFF;
            int type = marker >> 4;
            switch (type) {
                case 0x0:
                    if (marker == 0x08) return Boolean.FALSE;
                    if (marker == 0x09) return Boolean.TRUE;
                    return null;
                case 0x1: {
                    int byteCount = 1 << (marker & 0x0F);
                    long value = 0;
                    for (int i = 0; i < byteCount; i++) {
                        value = (value << 8) | (data[position + 1 + i] & 0xFF);
                    }
                    if (byteCount == 1) return value & 0xFFL;
                    if (byteCount == 2) return value & 0xFFFFL;
                    if (byteCount == 4) return value & 0xFFFFFFFFL;
                    return value;
                }
                case 0x2: {
                    int byteCount = 1 << (marker & 0x0F);
                    long bits = readUnsigned(position + 1, byteCount);
                    if (byteCount == 4) return (double) Float.intBitsToFloat((int) bits);
                    return Double.longBitsToDouble(bits);
                }
                case 0x3:
                    return Double.longBitsToDouble(readUnsigned(position + 1, 8));
                case 0x4: {
                    int[] length = readLength(position);
                    byte[] value = new byte[length[0]];
                    System.arraycopy(data, position + length[1], value, 0, length[0]);
                    return value;
                }
                case 0x5: {
                    int[] length = readLength(position);
                    return new String(data, position + length[1], length[0], ASCII);
                }
                case 0x6: {
                    int[] length = readLength(position);
                    return new String(data, position + length[1], length[0] * 2, UTF_16BE);
                }
                case 0x8: {
                    int byteCount = (marker & 0x0F) + 1;
                    return readUnsigned(position + 1, byteCount);
                }
                case 0xA: {
                    int[] length = readLength(position);
                    List<Object> list = new ArrayList<>(length[0]);
                    int references = position + length[1];
                    for (int i = 0; i < length[0]; i++) {
                        int reference = (int) readUnsigned(references + i * referenceSize, referenceSize);
                        list.add(readObject(reference, depth + 1));
                    }
                    return list;
                }
                case 0xD: {
                    int[] length = readLength(position);
                    Map<String, Object> map = new LinkedHashMap<>();
                    int keys = position + length[1];
                    int values = keys + length[0] * referenceSize;
                    for (int i = 0; i < length[0]; i++) {
                        int keyReference = (int) readUnsigned(keys + i * referenceSize, referenceSize);
                        int valueReference = (int) readUnsigned(values + i * referenceSize, referenceSize);
                        map.put(String.valueOf(readObject(keyReference, depth + 1)),
                                readObject(valueReference, depth + 1));
                    }
                    return map;
                }
                default:
                    throw new IllegalArgumentException("Unsupported property list marker 0x" + Integer.toHexString(marker));
            }
        }
    }

    // ---------------------------------------------------------------- writing

    public static byte[] write(Object root) {
        Writer writer = new Writer(countObjects(root));
        writer.add(root);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write('b'); body.write('p'); body.write('l'); body.write('i');
        body.write('s'); body.write('t'); body.write('0'); body.write('0');

        int objectCount = writer.objects.size();
        long[] offsets = new long[objectCount];
        for (int i = 0; i < objectCount; i++) {
            offsets[i] = body.size();
            byte[] encoded = writer.objects.get(i);
            body.write(encoded, 0, encoded.length);
        }

        long offsetTableOffset = body.size();
        int offsetSize = offsetTableOffset < 256 ? 1 : (offsetTableOffset < 65536 ? 2 : 4);
        for (long offset : offsets) {
            writeUnsigned(body, offset, offsetSize);
        }

        for (int i = 0; i < 6; i++) body.write(0);
        body.write(offsetSize);
        body.write(writer.referenceSize);
        writeUnsigned(body, objectCount, 8);
        writeUnsigned(body, 0, 8);
        writeUnsigned(body, offsetTableOffset, 8);
        return body.toByteArray();
    }

    private static int countObjects(Object value) {
        if (value instanceof Map) {
            int count = 1;
            for (Object child : ((Map<?, ?>) value).values()) {
                count += 1 + countObjects(child);
            }
            return count;
        }
        if (value instanceof List) {
            int count = 1;
            for (Object child : (List<?>) value) {
                count += countObjects(child);
            }
            return count;
        }
        return 1;
    }

    private static final class Writer {
        final List<byte[]> objects = new ArrayList<>();
        final int referenceSize;

        Writer(int objectCount) {
            referenceSize = objectCount < 256 ? 1 : (objectCount < 65536 ? 2 : 4);
        }

        int add(Object value) {
            int index = objects.size();
            objects.add(null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (value instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) value;
                int[] keys = new int[map.size()];
                int[] values = new int[map.size()];
                int i = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    keys[i] = add(String.valueOf(entry.getKey()));
                    values[i] = add(entry.getValue());
                    i++;
                }
                writeMarker(out, 0xD0, map.size());
                for (int key : keys) writeUnsigned(out, key, referenceSize);
                for (int reference : values) writeUnsigned(out, reference, referenceSize);
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                int[] references = new int[list.size()];
                for (int i = 0; i < references.length; i++) {
                    references[i] = add(list.get(i));
                }
                writeMarker(out, 0xA0, list.size());
                for (int reference : references) writeUnsigned(out, reference, referenceSize);
            } else {
                writeScalar(out, value);
            }
            objects.set(index, out.toByteArray());
            return index;
        }
    }

    private static void writeScalar(ByteArrayOutputStream out, Object value) {
        if (value == null) {
            out.write(0x00);
        } else if (value instanceof Boolean) {
            out.write(((Boolean) value) ? 0x09 : 0x08);
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            writeInteger(out, ((Number) value).longValue());
        } else if (value instanceof Double || value instanceof Float) {
            out.write(0x23);
            writeUnsigned(out, Double.doubleToLongBits(((Number) value).doubleValue()), 8);
        } else if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            writeMarker(out, 0x40, bytes.length);
            out.write(bytes, 0, bytes.length);
        } else if (value instanceof String) {
            String string = (String) value;
            if (isAscii(string)) {
                writeMarker(out, 0x50, string.length());
                byte[] bytes = string.getBytes(ASCII);
                out.write(bytes, 0, bytes.length);
            } else {
                writeMarker(out, 0x60, string.length());
                byte[] bytes = string.getBytes(UTF_16BE);
                out.write(bytes, 0, bytes.length);
            }
        } else {
            throw new IllegalArgumentException("Unsupported property list value: " + value.getClass());
        }
    }

    private static boolean isAscii(String string) {
        for (int i = 0; i < string.length(); i++) {
            if (string.charAt(i) > 0x7F) return false;
        }
        return true;
    }

    private static void writeInteger(ByteArrayOutputStream out, long value) {
        if (value >= 0 && value < 0x100L) {
            out.write(0x10);
            writeUnsigned(out, value, 1);
        } else if (value >= 0 && value < 0x10000L) {
            out.write(0x11);
            writeUnsigned(out, value, 2);
        } else if (value >= 0 && value < 0x100000000L) {
            out.write(0x12);
            writeUnsigned(out, value, 4);
        } else {
            out.write(0x13);
            writeUnsigned(out, value, 8);
        }
    }

    private static void writeMarker(ByteArrayOutputStream out, int type, int length) {
        if (length < 15) {
            out.write(type | length);
        } else {
            out.write(type | 0x0F);
            writeInteger(out, length);
        }
    }

    private static void writeUnsigned(ByteArrayOutputStream out, long value, int size) {
        for (int i = size - 1; i >= 0; i--) {
            out.write((int) (value >>> (8 * i)) & 0xFF);
        }
    }
}
