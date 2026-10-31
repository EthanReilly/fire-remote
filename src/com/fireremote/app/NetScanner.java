package com.fireremote.app;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Finds Fire TVs on the phone's Wi-Fi subnet by probing their DIAL service (port 8009). */
final class NetScanner {

    static final class Found {
        final String ip;
        final boolean confirmed; // answered as a Fire TV remote service

        Found(String ip, boolean confirmed) {
            this.ip = ip;
            this.confirmed = confirmed;
        }
    }

    /** Returns this phone's IPv4 address on the local network, or null. Prefers Wi-Fi. */
    static Inet4Address localAddress() {
        Inet4Address fallback = null;
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String name = ni.getName();
                        if (name.startsWith("wlan") || name.startsWith("eth")) return (Inet4Address) a;
                        if (fallback == null) fallback = (Inet4Address) a;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /** Blocking scan of x.y.z.1-254 (a few seconds). Confirmed Fire TVs are listed first. */
    static List<Found> scan(Inet4Address self) throws InterruptedException {
        byte[] b = self.getAddress();
        final String prefix = (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".";
        final int me = b[3] & 0xff;
        final List<Found> found = Collections.synchronizedList(new ArrayList<Found>());
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (int i = 1; i <= 254; i++) {
            if (i == me) continue;
            final String host = prefix + i;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    int code = probeDial(host);
                    if (code == 200) found.add(new Found(host, true));
                    else if (code > 0) found.add(new Found(host, false));
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(25, TimeUnit.SECONDS);
        List<Found> out = new ArrayList<Found>(found);
        Collections.sort(out, new Comparator<Found>() {
            @Override
            public int compare(Found x, Found y) {
                if (x.confirmed != y.confirmed) return x.confirmed ? -1 : 1;
                return lastOctet(x.ip) - lastOctet(y.ip);
            }
        });
        return out;
    }

    /** HTTP status of GET /apps/FireTVRemote on port 8009; 0 if closed or not an HTTP server. */
    private static int probeDial(String host) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, 8009), 400);
            s.setSoTimeout(1500);
            OutputStream out = s.getOutputStream();
            out.write(("GET /apps/FireTVRemote HTTP/1.1\r\nHost: " + host + ":8009\r\nConnection: close\r\n\r\n")
                    .getBytes("UTF-8"));
            out.flush();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[64];
            int n = in.read(buf);
            if (n <= 0) return 0;
            String head = new String(buf, 0, n, "ISO-8859-1");
            if (!head.startsWith("HTTP/")) return 0;
            String[] parts = head.split(" ");
            return parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static int lastOctet(String ip) {
        return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1));
    }
}
