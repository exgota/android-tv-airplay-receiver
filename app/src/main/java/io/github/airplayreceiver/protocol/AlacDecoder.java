package io.github.airplayreceiver.protocol;

/**
 * Apple Lossless (ALAC) decoder for 16-bit mono or stereo streams, following the structure of
 * Apple's open source reference decoder (Apache License 2.0): adaptive Golomb entropy decoding,
 * an adaptive FIR predictor, and stereo dematrixing. Android 5.1 has no ALAC codec.
 */
public final class AlacDecoder {

    private static final int ID_SCE = 0;
    private static final int ID_CPE = 1;
    private static final int ID_CCE = 2;
    private static final int ID_LFE = 3;
    private static final int ID_DSE = 4;
    private static final int ID_PCE = 5;
    private static final int ID_FIL = 6;
    private static final int ID_END = 7;

    private static final int QBSHIFT = 9;
    private static final int QB = 1 << QBSHIFT;
    private static final int MMULSHIFT = 2;
    private static final int MDENSHIFT = QBSHIFT - MMULSHIFT - 1;
    private static final int MOFF = 1 << (MDENSHIFT - 2);
    private static final int BITOFF = 24;
    private static final int MAX_PREFIX = 9;
    private static final int MAX_RUN_BITS = 16;
    private static final int MAXIMUM_MEAN = 0xFFFF;

    private final int frameLength;
    private final int bitDepth;
    private final int riceHistoryMultiplier;
    private final int riceInitialHistory;
    private final int riceLimit;
    private final int channels;

    private final int[] predictor;
    private final int[] mixBufferU;
    private final int[] mixBufferV;
    private final short[] coefficientsU = new short[32];
    private final short[] coefficientsV = new short[32];

    private byte[] data;
    private int bitPosition;
    private int bitLimit;

    /** AirPlay's fixed stream format: 352 frames, 16 bit, pb 40, mb 10, kb 14, stereo. */
    public static AlacDecoder forAirPlay(int samplesPerFrame) {
        return new AlacDecoder(samplesPerFrame > 0 ? samplesPerFrame : 352, 16, 40, 10, 14, 2);
    }

    public AlacDecoder(int frameLength, int bitDepth, int riceHistoryMultiplier, int riceInitialHistory,
                       int riceLimit, int channels) {
        if (bitDepth != 16) {
            throw new IllegalArgumentException("Only 16-bit ALAC is supported");
        }
        this.frameLength = frameLength;
        this.bitDepth = bitDepth;
        this.riceHistoryMultiplier = riceHistoryMultiplier;
        this.riceInitialHistory = riceInitialHistory;
        this.riceLimit = riceLimit;
        this.channels = channels;
        predictor = new int[frameLength];
        mixBufferU = new int[frameLength];
        mixBufferV = new int[frameLength];
    }

    public int maximumOutputSamples() {
        return frameLength * channels;
    }

    /**
     * Decodes one packet into interleaved 16-bit samples.
     *
     * @return the number of frames (samples per channel) written
     */
    public int decode(byte[] input, int offset, int length, short[] output) {
        data = new byte[length + 8];
        System.arraycopy(input, offset, data, 0, length);
        bitPosition = 0;
        bitLimit = length * 8;

        int channelIndex = 0;
        int frames = frameLength;
        while (bitPosition + 3 <= bitLimit) {
            int tag = readBits(3);
            switch (tag) {
                case ID_SCE:
                case ID_LFE:
                    frames = decodeSingleChannel(output, channelIndex);
                    channelIndex += 1;
                    break;
                case ID_CPE:
                    if (channelIndex + 2 > channels) {
                        throw new IllegalStateException("Channel pair exceeds configured channels");
                    }
                    frames = decodeChannelPair(output, channelIndex);
                    channelIndex += 2;
                    break;
                case ID_DSE: {
                    readBits(4);
                    boolean byteAlign = readBits(1) != 0;
                    int count = readBits(8);
                    if (count == 255) count += readBits(8);
                    if (byteAlign) alignToByte();
                    bitPosition += count * 8;
                    break;
                }
                case ID_FIL: {
                    int count = readBits(4);
                    if (count == 15) count += readBits(8) - 1;
                    bitPosition += count * 8;
                    break;
                }
                case ID_END:
                    alignToByte();
                    return frames;
                case ID_CCE:
                case ID_PCE:
                default:
                    throw new IllegalStateException("Unsupported ALAC element " + tag);
            }
            if (channelIndex >= channels) {
                return frames;
            }
        }
        return frames;
    }

    private int decodeSingleChannel(short[] output, int channelIndex) {
        readBits(4);   // element instance tag
        readBits(12);  // unused header bits
        int header = readBits(4);
        boolean partialFrame = (header >> 3) != 0;
        int bytesShifted = (header >> 1) & 3;
        boolean escape = (header & 1) != 0;
        int channelBits = bitDepth - bytesShifted * 8;
        int samples = frameLength;
        if (partialFrame) {
            samples = (readBits(16) << 16) | readBits(16);
            checkSamples(samples);
        }
        if (!escape) {
            readBits(8);  // mix bits, unused for mono
            readBits(8);  // mix residue
            int modeByte = readBits(8);
            int mode = modeByte >> 4;
            int denominatorShift = modeByte & 0x0F;
            int factorByte = readBits(8);
            int predictorFactor = factorByte >> 5;
            int coefficientCount = factorByte & 0x1F;
            for (int i = 0; i < coefficientCount; i++) {
                coefficientsU[i] = (short) readBits(16);
            }
            if (bytesShifted != 0) {
                bitPosition += bytesShifted * 8 * samples;
            }
            decodeResiduals(predictor, samples, channelBits, riceHistoryMultiplier * predictorFactor / 4);
            reconstruct(mode, predictor, mixBufferU, samples, coefficientsU, coefficientCount, channelBits, denominatorShift);
        } else {
            int shift = 32 - bitDepth;
            for (int i = 0; i < samples; i++) {
                mixBufferU[i] = (readBits(bitDepth) << shift) >> shift;
            }
        }
        for (int i = 0; i < samples; i++) {
            output[i * channels + channelIndex] = (short) mixBufferU[i];
        }
        return samples;
    }

    private int decodeChannelPair(short[] output, int channelIndex) {
        readBits(4);
        readBits(12);
        int header = readBits(4);
        boolean partialFrame = (header >> 3) != 0;
        int bytesShifted = (header >> 1) & 3;
        boolean escape = (header & 1) != 0;
        int channelBits = bitDepth - bytesShifted * 8 + 1;
        int samples = frameLength;
        if (partialFrame) {
            samples = (readBits(16) << 16) | readBits(16);
            checkSamples(samples);
        }
        int mixBits = 0;
        int mixResidue = 0;
        if (!escape) {
            mixBits = readBits(8);
            mixResidue = (byte) readBits(8);

            int modeByte = readBits(8);
            int modeU = modeByte >> 4;
            int denominatorShiftU = modeByte & 0x0F;
            int factorByte = readBits(8);
            int predictorFactorU = factorByte >> 5;
            int coefficientCountU = factorByte & 0x1F;
            for (int i = 0; i < coefficientCountU; i++) {
                coefficientsU[i] = (short) readBits(16);
            }

            modeByte = readBits(8);
            int modeV = modeByte >> 4;
            int denominatorShiftV = modeByte & 0x0F;
            factorByte = readBits(8);
            int predictorFactorV = factorByte >> 5;
            int coefficientCountV = factorByte & 0x1F;
            for (int i = 0; i < coefficientCountV; i++) {
                coefficientsV[i] = (short) readBits(16);
            }

            if (bytesShifted != 0) {
                bitPosition += bytesShifted * 8 * 2 * samples;
            }
            decodeResiduals(predictor, samples, channelBits, riceHistoryMultiplier * predictorFactorU / 4);
            reconstruct(modeU, predictor, mixBufferU, samples, coefficientsU, coefficientCountU, channelBits, denominatorShiftU);
            decodeResiduals(predictor, samples, channelBits, riceHistoryMultiplier * predictorFactorV / 4);
            reconstruct(modeV, predictor, mixBufferV, samples, coefficientsV, coefficientCountV, channelBits, denominatorShiftV);
        } else {
            int shift = 32 - bitDepth;
            for (int i = 0; i < samples; i++) {
                mixBufferU[i] = (readBits(bitDepth) << shift) >> shift;
                mixBufferV[i] = (readBits(bitDepth) << shift) >> shift;
            }
        }

        if (mixResidue != 0) {
            for (int i = 0; i < samples; i++) {
                int left = mixBufferU[i] + mixBufferV[i] - ((mixResidue * mixBufferV[i]) >> mixBits);
                int right = left - mixBufferV[i];
                output[i * channels + channelIndex] = (short) left;
                output[i * channels + channelIndex + 1] = (short) right;
            }
        } else {
            for (int i = 0; i < samples; i++) {
                output[i * channels + channelIndex] = (short) mixBufferU[i];
                output[i * channels + channelIndex + 1] = (short) mixBufferV[i];
            }
        }
        return samples;
    }

    private void checkSamples(int samples) {
        if (samples < 0 || samples > frameLength) {
            throw new IllegalStateException("Partial frame of " + samples + " samples exceeds " + frameLength);
        }
    }

    private void reconstruct(int mode, int[] residuals, int[] output, int samples, short[] coefficients,
                             int coefficientCount, int channelBits, int denominatorShift) {
        if (mode == 0) {
            predict(residuals, output, samples, coefficients, coefficientCount, channelBits, denominatorShift);
        } else {
            predict(residuals, residuals, samples, null, 31, channelBits, 0);
            predict(residuals, output, samples, coefficients, coefficientCount, channelBits, denominatorShift);
        }
    }

    // ------------------------------------------------------------------ entropy decoding

    private void decodeResiduals(int[] output, int samples, int maximumBits, int historyMultiplier) {
        int mean = riceInitialHistory;
        int limitMask = (1 << riceLimit) - 1;
        int zeroMode = 0;
        int count = 0;
        while (count < samples) {
            if (bitPosition >= bitLimit) {
                throw new IllegalStateException("ALAC residuals run past the packet");
            }
            int m = mean >>> QBSHIFT;
            int k = 31 - Integer.numberOfLeadingZeros(m + 3);
            k = Math.min(k, riceLimit);
            int divisor = (1 << k) - 1;

            int value = readRiceValue(divisor, k, maximumBits);
            int decoded = value + zeroMode;
            int sign = -(decoded & 1) | 1;
            output[count++] = ((decoded + 1) >>> 1) * sign;

            mean = historyMultiplier * (value + zeroMode) + mean - ((historyMultiplier * mean) >> QBSHIFT);
            if (value > MAXIMUM_MEAN) {
                mean = MAXIMUM_MEAN;
            }
            zeroMode = 0;

            if ((mean << MMULSHIFT) < QB && count < samples) {
                zeroMode = 1;
                int runK = Integer.numberOfLeadingZeros(mean) - BITOFF + ((mean + MOFF) >> MDENSHIFT);
                int runDivisor = ((1 << runK) - 1) & limitMask;
                int run = readRunLength(runDivisor, runK);
                if (count + run > samples) {
                    throw new IllegalStateException("ALAC zero run exceeds frame");
                }
                for (int j = 0; j < run; j++) {
                    output[count++] = 0;
                }
                if (run >= 65535) {
                    zeroMode = 0;
                }
                mean = 0;
            }
        }
    }

    /** dyn_get_32bit: unary prefix with escape, then a k-bit remainder. */
    private int readRiceValue(int m, int k, int maximumBits) {
        int prefix = countLeadingOnes();
        if (prefix >= MAX_PREFIX) {
            bitPosition += MAX_PREFIX;
            return readBits(maximumBits);
        }
        bitPosition += prefix + 1;
        if (k == 1) {
            return prefix;
        }
        int value = peekBits(k);
        int result = prefix * m;
        bitPosition += k - 1;
        if (value >= 2) {
            result += value - 1;
            bitPosition += 1;
        }
        return result;
    }

    /** dyn_get: the zero-run length code. */
    private int readRunLength(int m, int k) {
        int prefix = countLeadingOnes();
        if (prefix >= MAX_PREFIX) {
            bitPosition += MAX_PREFIX;
            return readBits(MAX_RUN_BITS);
        }
        bitPosition += prefix + 1;
        int value = peekBits(k);
        bitPosition += k;
        int result = prefix * m + value - 1;
        if (value < 2) {
            result -= value - 1;
            bitPosition -= 1;
        }
        return result;
    }

    private int countLeadingOnes() {
        int count = 0;
        int position = bitPosition;
        while (count < MAX_PREFIX && ((data[position >> 3] >> (7 - (position & 7))) & 1) == 1) {
            count++;
            position++;
        }
        return count;
    }

    // ------------------------------------------------------------------ prediction

    private static void predict(int[] residuals, int[] output, int samples, short[] coefficients,
                                int activeCount, int channelBits, int denominatorShift) {
        int channelShift = 32 - channelBits;
        int denominatorHalf = denominatorShift > 0 ? 1 << (denominatorShift - 1) : 0;
        if (samples == 0) {
            return;
        }
        output[0] = residuals[0];
        if (activeCount == 0) {
            if (samples > 1 && residuals != output) {
                System.arraycopy(residuals, 1, output, 1, samples - 1);
            }
            return;
        }
        if (activeCount == 31) {
            int previous = output[0];
            for (int j = 1; j < samples; j++) {
                int delta = residuals[j] + previous;
                previous = (delta << channelShift) >> channelShift;
                output[j] = previous;
            }
            return;
        }

        for (int j = 1; j <= activeCount && j < samples; j++) {
            int delta = residuals[j] + output[j - 1];
            output[j] = (delta << channelShift) >> channelShift;
        }

        int limit = activeCount + 1;
        for (int j = limit; j < samples; j++) {
            int sum = 0;
            int last = j - 1;
            int top = output[j - limit];
            for (int k = 0; k < activeCount; k++) {
                sum += coefficients[k] * (output[last - k] - top);
            }
            int delta = residuals[j];
            int originalDelta = delta;
            int sign = signum(delta);
            delta += top + ((sum + denominatorHalf) >> denominatorShift);
            output[j] = (delta << channelShift) >> channelShift;

            if (sign > 0) {
                for (int k = activeCount - 1; k >= 0; k--) {
                    int difference = top - output[last - k];
                    int differenceSign = signum(difference);
                    coefficients[k] = (short) (coefficients[k] - differenceSign);
                    originalDelta -= (activeCount - k) * ((differenceSign * difference) >> denominatorShift);
                    if (originalDelta <= 0) {
                        break;
                    }
                }
            } else if (sign < 0) {
                for (int k = activeCount - 1; k >= 0; k--) {
                    int difference = top - output[last - k];
                    int differenceSign = signum(difference);
                    coefficients[k] = (short) (coefficients[k] + differenceSign);
                    originalDelta -= (activeCount - k) * ((-differenceSign * difference) >> denominatorShift);
                    if (originalDelta >= 0) {
                        break;
                    }
                }
            }
        }
    }

    private static int signum(int value) {
        return (value > 0 ? 1 : 0) - (value < 0 ? 1 : 0);
    }

    // ------------------------------------------------------------------ bit reading

    private int peekBits(int count) {
        if (count == 0) {
            return 0;
        }
        int byteIndex = bitPosition >> 3;
        long window = ((data[byteIndex] & 0xFFL) << 32) | ((data[byteIndex + 1] & 0xFFL) << 24)
                | ((data[byteIndex + 2] & 0xFFL) << 16) | ((data[byteIndex + 3] & 0xFFL) << 8)
                | (data[byteIndex + 4] & 0xFFL);
        int shift = 40 - (bitPosition & 7) - count;
        return (int) ((window >>> shift) & ((1L << count) - 1));
    }

    private int readBits(int count) {
        if (bitPosition + count > bitLimit + 32) {
            throw new IllegalStateException("ALAC packet truncated");
        }
        int value = peekBits(count);
        bitPosition += count;
        return value;
    }

    private void alignToByte() {
        bitPosition = (bitPosition + 7) & ~7;
    }
}
