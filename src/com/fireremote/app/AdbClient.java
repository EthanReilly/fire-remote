package com.fireremote.app;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Minimal ADB-over-TCP client: connects, authenticates with an RSA key, and keeps one
 * interactive shell open so remote button presses are sent as "input keyevent N" lines.
 * Pure Java (no Android APIs) so it can be tested on a desktop JVM.
 */
final class AdbClient {

    interface Listener {
        /** The TV is showing "Allow USB debugging?"; the user must accept it on the TV. */
        void onAuthPromptShown();

        void onDisconnected(String reason);
    }

    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int VERSION = 0x01000000;
    private static final int MAX_DATA = 4096;

    private final AdbKeys keys;
    private final Listener listener;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean connected;
    private volatile boolean closing;

    // Persistent shell stream (touched by writer thread + reader thread).
    private final Object shellLock = new Object();
    private int nextLocalId = 1;
    private volatile int shellLocal;
    private volatile int shellRemote;
    private volatile boolean shellOpen;
    private final Semaphore okay = new Semaphore(0);

    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicInteger pending =
            new java.util.concurrent.atomic.AtomicInteger();

    AdbClient(AdbKeys keys, Listener listener) {
        this.keys = keys;
        this.listener = listener;
    }

    boolean isConnected() {
        return connected;
    }

    /** Blocking. Connects and authenticates; may wait up to ~2 minutes for the TV prompt. */
    void connect(String host, int port) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5000);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.setSoTimeout(15000);
        socket = s;
        in = s.getInputStream();
        out = s.getOutputStream();
        try {
            handshake();
        } catch (Exception e) {
            closeQuietly();
            throw e;
        }
        s.setSoTimeout(0);
        connected = true;
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "adb-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void handshake() throws Exception {
        writeMessage(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes());
        boolean sentSignature = false;
        boolean sentKey = false;
        while (true) {
            Message m;
            try {
                m = readMessage();
            } catch (java.net.SocketTimeoutException te) {
                if (sentKey) throw new IOException("Timed out waiting for you to accept the prompt on the TV");
                throw new IOException("The Fire TV did not respond (is ADB debugging on?)");
            }
            if (m.command == A_CNXN) {
                return;
            } else if (m.command == A_AUTH && m.arg0 == AUTH_TOKEN) {
                if (!sentSignature) {
                    writeMessage(A_AUTH, AUTH_SIGNATURE, 0, keys.sign(m.data));
                    sentSignature = true;
                } else if (!sentKey) {
                    writeMessage(A_AUTH, AUTH_RSAPUBLICKEY, 0, keys.adbPublicKeyPayload("FireRemote@android"));
                    sentKey = true;
                    socket.setSoTimeout(120000);
                    listener.onAuthPromptShown();
                } else {
                    throw new IOException("Connection was rejected on the TV");
                }
            } else {
                // Ignore anything unexpected during the handshake.
            }
        }
    }

    /** Queues a shell command (e.g. "input keyevent 22"). Never blocks the caller. */
    void shell(final String command) {
        if (!connected) return;
        if (pending.get() >= 4) return; // user is mashing faster than the TV can keep up
        pending.incrementAndGet();
        writer.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runShell(command);
                } finally {
                    pending.decrementAndGet();
                }
            }
        });
    }

    private void runShell(String command) {
        if (!connected) return;
        try {
            sendShellLine(command);
        } catch (Exception first) {
            // The shell may have exited; reopen once and retry.
            try {
                shellOpen = false;
                sendShellLine(command);
            } catch (Exception e) {
                fail("Lost connection to the Fire TV");
            }
        }
    }

    private void sendShellLine(String command) throws Exception {
        if (!shellOpen) openShell();
        byte[] data = (command + "\n").getBytes("UTF-8");
        okay.drainPermits();
        writeMessage(A_WRTE, shellLocal, shellRemote, data);
        if (!okay.tryAcquire(5, TimeUnit.SECONDS) || !shellOpen) {
            throw new IOException("No reply from the Fire TV");
        }
    }

    private void openShell() throws Exception {
        synchronized (shellLock) {
            shellLocal = nextLocalId++;
        }
        okay.drainPermits();
        writeMessage(A_OPEN, shellLocal, 0, "shell:\0".getBytes());
        if (!okay.tryAcquire(5, TimeUnit.SECONDS) || !shellOpen) {
            throw new IOException("Could not open a shell on the Fire TV");
        }
    }

    private void readLoop() {
        try {
            while (connected) {
                Message m = readMessage();
                switch (m.command) {
                    case A_OKAY:
                        if (m.arg1 == shellLocal) {
                            if (!shellOpen) {
                                shellRemote = m.arg0;
                                shellOpen = true;
                            }
                            okay.release();
                        }
                        break;
                    case A_WRTE:
                        // Shell output (echo/prompt) – acknowledge and discard.
                        writeMessage(A_OKAY, m.arg1, m.arg0, null);
                        break;
                    case A_CLSE:
                        if (m.arg1 == shellLocal) {
                            boolean wasOpen = shellOpen;
                            shellOpen = false;
                            if (wasOpen) writeMessage(A_CLSE, m.arg1, m.arg0, null);
                            okay.release();
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            fail("Disconnected from the Fire TV");
        }
    }

    private void fail(String reason) {
        if (!connected) return;
        connected = false;
        shellOpen = false;
        okay.release();
        closeQuietly();
        if (!closing) listener.onDisconnected(reason);
    }

    void close() {
        closing = true;
        connected = false;
        closeQuietly();
        writer.shutdownNow();
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    // ---- wire format ----

    static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private synchronized void writeMessage(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        ByteBuffer bb = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(cmd ^ 0xffffffff);
        if (len > 0) bb.put(data);
        out.write(bb.array());
        out.flush();
    }

    private Message readMessage() throws IOException {
        byte[] header = new byte[24];
        readFully(header);
        ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = bb.getInt();
        m.arg0 = bb.getInt();
        m.arg1 = bb.getInt();
        int len = bb.getInt();
        bb.getInt(); // checksum (not verified)
        int magic = bb.getInt();
        if (magic != (m.command ^ 0xffffffff)) throw new IOException("Bad adb packet");
        if (len < 0 || len > 1024 * 1024) throw new IOException("Bad adb packet length");
        m.data = new byte[len];
        readFully(m.data);
        return m;
    }

    private void readFully(byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }
}
