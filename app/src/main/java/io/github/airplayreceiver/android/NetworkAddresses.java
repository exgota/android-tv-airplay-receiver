package io.github.airplayreceiver.android;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

import io.github.airplayreceiver.protocol.MulticastDnsResponder;

/** Finds the IPv4 address and interface that the receiver should advertise. */
final class NetworkAddresses {

    final InetAddress address;
    final NetworkInterface networkInterface;

    private NetworkAddresses(InetAddress address, NetworkInterface networkInterface) {
        this.address = address;
        this.networkInterface = networkInterface;
    }

    /** Prefers Ethernet, then Wi-Fi, then any other interface that is up. Returns null when offline. */
    static NetworkAddresses find() {
        NetworkAddresses fallback = null;
        try {
            for (NetworkInterface candidate : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!candidate.isUp() || candidate.isLoopback() || candidate.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(candidate.getInetAddresses())) {
                    if (!MulticastDnsResponder.isUsableAddress(address)) {
                        continue;
                    }
                    String name = candidate.getName();
                    NetworkAddresses found = new NetworkAddresses(address, candidate);
                    if (name.startsWith("eth")) {
                        return found;
                    }
                    if (name.startsWith("wlan") && (fallback == null || !fallback.networkInterface.getName().startsWith("wlan"))) {
                        fallback = found;
                    } else if (fallback == null) {
                        fallback = found;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
