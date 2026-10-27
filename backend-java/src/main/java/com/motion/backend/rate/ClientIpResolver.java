package com.motion.backend.rate;

import com.motion.backend.config.MotionProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** application client IP selection: trust X-Forwarded-For only from trusted direct peers. */
@Component
public class ClientIpResolver {

    private final List<String> trustedProxies;

    public ClientIpResolver(MotionProperties properties) {
        this.trustedProxies = List.copyOf(properties.trustedProxies());
    }

    public String resolve(HttpServletRequest request) {
        String peer = hostOnly(request.getRemoteAddr());
        if (trustedProxies.isEmpty() || !isTrusted(peer, trustedProxies)) {
            return peer;
        }

        List<String> chain = new ArrayList<>();
        var headers = request.getHeaders("X-Forwarded-For");
        while (headers.hasMoreElements()) {
            String header = headers.nextElement();
            if (header == null) {
                continue;
            }
            for (String part : header.split(",", -1)) {
                String value = part.trim();
                if (!value.isEmpty()) {
                    chain.add(value);
                }
            }
        }
        chain.add(peer);

        for (int index = chain.size() - 1; index >= 0; index--) {
            String candidate = hostOnly(chain.get(index));
            if (candidate.isEmpty()) {
                continue;
            }
            if (!isTrusted(candidate, trustedProxies)) {
                return candidate;
            }
        }
        return peer;
    }

    static String hostOnly(String address) {
        if (address == null) {
            return "";
        }
        String value = address.trim();
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close > 0 && value.length() > close + 1 && value.charAt(close + 1) == ':') {
                return value.substring(1, close);
            }
            return value;
        }
        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            return value.substring(0, lastColon);
        }
        return value;
    }

    static boolean isTrusted(String address, List<String> trusted) {
        byte[] ip = parseIp(address);
        if (ip == null) {
            return false;
        }
        for (String entry : trusted) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String value = entry.trim();
            int slash = value.indexOf('/');
            if (slash >= 0) {
                String networkText = value.substring(0, slash);
                String prefixText = value.substring(slash + 1);
                byte[] network = parseIp(networkText);
                if (network == null || network.length != ip.length) {
                    continue;
                }
                try {
                    int prefix = Integer.parseInt(prefixText);
                    if (prefix >= 0 && prefix <= ip.length * 8 && prefixMatches(ip, network, prefix)) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    // Go ignores malformed CIDRs too.
                }
                continue;
            }
            byte[] candidate = parseIp(value);
            if (candidate != null && java.util.Arrays.equals(candidate, ip)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] parseIp(String value) {
        if (value == null || !value.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            return address.getAddress();
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

    private static boolean prefixMatches(byte[] address, byte[] network, int prefixBits) {
        int fullBytes = prefixBits / 8;
        for (int index = 0; index < fullBytes; index++) {
            if (address[index] != network[index]) {
                return false;
            }
        }
        int remainder = prefixBits % 8;
        if (remainder == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainder);
        return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }
}
