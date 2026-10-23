package io.github.airplayreceiver.protocol;

import java.io.ByteArrayOutputStream;

/**
 * Rewrites an H.264 sequence parameter set so that its VUI declares "bitstream_restriction" with
 * max_num_reorder_frames = 0. Without that declaration a decoder must assume frames may arrive out
 * of display order and holds several decoded pictures back, which adds latency. AirPlay mirroring
 * never reorders frames, so the declaration is true. Moonlight applies the same fix.
 */
public final class SequenceParameterSetRewriter {

    private SequenceParameterSetRewriter() {
    }

    /** Returns the rewritten SPS NAL unit (with its one-byte header), or the input if parsing fails. */
    public static byte[] rewrite(byte[] nal) {
        try {
            return rewriteOrThrow(nal);
        } catch (RuntimeException error) {
            ReceiverLog.w("SpsRewriter", "Keeping original SPS: " + error);
            return nal;
        }
    }

    static byte[] rewriteOrThrow(byte[] nal) {
        byte[] rbsp = removeEmulationPrevention(nal, 1);
        BitReader in = new BitReader(rbsp);

        int profile = in.bits(8);
        in.bits(16); // constraint flags, level
        in.ue();     // seq_parameter_set_id
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                || profile == 83 || profile == 86 || profile == 118 || profile == 128 || profile == 138
                || profile == 139 || profile == 134 || profile == 135) {
            int chromaFormat = in.ue();
            if (chromaFormat == 3) in.bits(1);
            in.ue();
            in.ue();
            in.bits(1);
            if (in.bits(1) == 1) {
                for (int i = 0; i < (chromaFormat == 3 ? 12 : 8); i++) {
                    if (in.bits(1) == 1) {
                        skipScalingList(in, i < 6 ? 16 : 64);
                    }
                }
            }
        }
        in.ue(); // log2_max_frame_num_minus4
        int pictureOrderCountType = in.ue();
        if (pictureOrderCountType == 0) {
            in.ue();
        } else if (pictureOrderCountType == 1) {
            in.bits(1);
            in.se();
            in.se();
            int cycle = in.ue();
            for (int i = 0; i < cycle; i++) in.se();
        }
        int referenceFrames = in.ue();
        in.bits(1);
        in.ue();
        in.ue();
        if (in.bits(1) == 0) in.bits(1); // frame_mbs_only_flag, mb_adaptive_frame_field_flag
        in.bits(1);
        if (in.bits(1) == 1) { // frame_cropping_flag
            in.ue();
            in.ue();
            in.ue();
            in.ue();
        }

        BitWriter out = new BitWriter();
        int vuiFlagPosition = in.position;
        boolean vuiPresent = in.bits(1) == 1;
        if (!vuiPresent) {
            out.copy(rbsp, 0, vuiFlagPosition);
            out.bits(1, 1); // vui_parameters_present_flag
            out.bits(0, 8); // aspect, overscan, video signal, chroma loc, timing, nal hrd, vcl hrd, pic struct
        } else {
            if (in.bits(1) == 1) { // aspect_ratio_info_present_flag
                if (in.bits(8) == 255) in.bits(32);
            }
            if (in.bits(1) == 1) in.bits(1);
            if (in.bits(1) == 1) {
                in.bits(4);
                if (in.bits(1) == 1) in.bits(24);
            }
            if (in.bits(1) == 1) {
                in.ue();
                in.ue();
            }
            if (in.bits(1) == 1) {
                in.bits(32);
                in.bits(32);
                in.bits(1);
            }
            boolean nalHrd = in.bits(1) == 1;
            if (nalHrd) skipHrd(in);
            boolean vclHrd = in.bits(1) == 1;
            if (vclHrd) skipHrd(in);
            if (nalHrd || vclHrd) in.bits(1);
            in.bits(1); // pic_struct_present_flag
            out.copy(rbsp, 0, in.position);
        }
        out.bits(1, 1); // bitstream_restriction_flag
        out.bits(1, 1); // motion_vectors_over_pic_boundaries_flag
        out.ue(2);      // max_bytes_per_pic_denom
        out.ue(1);      // max_bits_per_mb_denom
        out.ue(16);     // log2_max_mv_length_horizontal
        out.ue(16);     // log2_max_mv_length_vertical
        out.ue(0);      // max_num_reorder_frames
        out.ue(Math.max(1, referenceFrames)); // max_dec_frame_buffering
        out.bits(1, 1); // rbsp_stop_one_bit
        out.alignWithZeros();

        byte[] rewritten = out.toByteArray();
        byte[] withHeader = new byte[rewritten.length + 1];
        withHeader[0] = nal[0];
        System.arraycopy(rewritten, 0, withHeader, 1, rewritten.length);
        return addEmulationPrevention(withHeader, 1);
    }

    private static void skipScalingList(BitReader in, int size) {
        int last = 8;
        int next = 8;
        for (int j = 0; j < size; j++) {
            if (next != 0) {
                next = (last + in.se() + 256) % 256;
            }
            last = next == 0 ? last : next;
        }
    }

    private static void skipHrd(BitReader in) {
        int count = in.ue();
        in.bits(8);
        for (int i = 0; i <= count; i++) {
            in.ue();
            in.ue();
            in.bits(1);
        }
        in.bits(20);
    }

    static byte[] removeEmulationPrevention(byte[] nal, int offset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(nal.length);
        int zeros = 0;
        for (int i = offset; i < nal.length; i++) {
            int value = nal[i] & 0xFF;
            if (zeros >= 2 && value == 3) {
                zeros = 0;
                continue;
            }
            out.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    static byte[] addEmulationPrevention(byte[] data, int offset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 8);
        out.write(data, 0, offset);
        int zeros = 0;
        for (int i = offset; i < data.length; i++) {
            int value = data[i] & 0xFF;
            if (zeros >= 2 && value <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    private static final class BitReader {
        final byte[] data;
        int position;

        BitReader(byte[] data) {
            this.data = data;
        }

        int bits(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                if (position >= data.length * 8) {
                    throw new IllegalStateException("SPS ended early");
                }
                value = (value << 1) | ((data[position >> 3] >> (7 - (position & 7))) & 1);
                position++;
            }
            return value;
        }

        int ue() {
            int zeros = 0;
            while (bits(1) == 0) {
                if (++zeros > 31) throw new IllegalStateException("Bad Exp-Golomb code");
            }
            return zeros == 0 ? 0 : ((1 << zeros) - 1 + bits(zeros));
        }

        int se() {
            int value = ue();
            return (value & 1) == 1 ? (value + 1) / 2 : -(value / 2);
        }
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int count;

        void bits(int value, int length) {
            for (int i = length - 1; i >= 0; i--) {
                current = (current << 1) | ((value >> i) & 1);
                if (++count == 8) {
                    out.write(current);
                    current = 0;
                    count = 0;
                }
            }
        }

        void copy(byte[] source, int fromBit, int toBit) {
            for (int position = fromBit; position < toBit; position++) {
                bits((source[position >> 3] >> (7 - (position & 7))) & 1, 1);
            }
        }

        void ue(int value) {
            int coded = value + 1;
            int length = 32 - Integer.numberOfLeadingZeros(coded);
            bits(0, length - 1);
            bits(coded, length);
        }

        void alignWithZeros() {
            while (count != 0) {
                bits(0, 1);
            }
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
