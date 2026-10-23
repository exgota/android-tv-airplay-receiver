package io.github.airplayreceiver.protocol;

/** Callbacks from the protocol layer to the media and user interface layer. Called on network threads. */
public interface ReceiverListener {

    /** A client completed its first SETUP. */
    void onClientConnected(String clientName, String clientModel);

    /** The client that owned the receiver disconnected or tore everything down. */
    void onClientDisconnected();

    void onMirroringStarted();

    void onMirroringStopped();

    /** New H.264 parameter sets, sent at start and whenever the picture size changes. */
    void onVideoParameters(byte[] sequenceParameterSet, byte[] pictureParameterSet, int width, int height);

    /** One access unit in Annex B format (start codes). The array is owned by the callee. */
    void onVideoFrame(byte[] accessUnit, long timestampNanoseconds, boolean keyFrame);

    /**
     * An audio stream started.
     *
     * @param compressionType AirPlay "ct": 1 PCM, 2 ALAC, 4 AAC-LC, 8 AAC-ELD
     * @param samplesPerFrame AirPlay "spf"
     * @param screenAudio     true when the audio accompanies screen mirroring
     */
    void onAudioStarted(int compressionType, int samplesPerFrame, boolean screenAudio);

    /** One decrypted audio frame, in sequence order. The array is owned by the callee. */
    void onAudioFrame(byte[] frame, long rtpTimestamp);

    void onAudioFlush();

    void onAudioStopped();

    /** Volume in AirPlay decibels: 0 is full scale, -30 the quietest audible step, -144 mute. */
    void onVolume(float decibels);

    /** A still image (JPEG) sent with the AirPlay photo protocol. */
    void onPhoto(byte[] image);

    void onPhotoStopped();
}
