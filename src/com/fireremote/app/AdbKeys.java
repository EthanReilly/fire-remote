package com.fireremote.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * RSA key pair used to authenticate with the Fire TV's adbd, stored in the app's private files.
 * Pure Java (no Android APIs) so it can be tested on a desktop JVM.
 */
final class AdbKeys {

    // PKCS#1 v1.5 type-1 padding + SHA-1 DigestInfo header; the 20-byte adb token is appended.
    private static final byte[] SIGNATURE_PADDING;

    static {
        byte[] digestInfo = {
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        };
        int total = 256 - 20; // 2048-bit key, minus the token
        SIGNATURE_PADDING = new byte[total];
        SIGNATURE_PADDING[0] = 0x00;
        SIGNATURE_PADDING[1] = 0x01;
        int ffEnd = total - digestInfo.length - 1;
        for (int i = 2; i < ffEnd; i++) SIGNATURE_PADDING[i] = (byte) 0xff;
        SIGNATURE_PADDING[ffEnd] = 0x00;
        System.arraycopy(digestInfo, 0, SIGNATURE_PADDING, ffEnd + 1, digestInfo.length);
    }

    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;

    private AdbKeys(PrivateKey priv, RSAPublicKey pub) {
        this.privateKey = priv;
        this.publicKey = pub;
    }

    /** Loads the key pair from {@code dir}, creating and saving a new one on first use. */
    static AdbKeys loadOrCreate(File dir) throws Exception {
        File privFile = new File(dir, "adbkey");
        File pubFile = new File(dir, "adbkey.pub.x509");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (privFile.exists() && pubFile.exists()) {
            try {
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(privFile)));
                RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(readAll(pubFile)));
                return new AdbKeys(priv, pub);
            } catch (Exception corrupt) {
                // fall through and regenerate
            }
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        writeAll(privFile, kp.getPrivate().getEncoded());
        writeAll(pubFile, kp.getPublic().getEncoded());
        return new AdbKeys(kp.getPrivate(), (RSAPublicKey) kp.getPublic());
    }

    /** Signs the 20-byte token sent by adbd in an AUTH(TOKEN) message. */
    byte[] sign(byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, privateKey);
        c.update(SIGNATURE_PADDING);
        return c.doFinal(token);
    }

    /** Public key in adb's format: base64(mincrypt RSAPublicKey) + " name" + NUL. */
    byte[] adbPublicKeyPayload(String name) {
        String b64 = Base64Enc.encode(mincryptKey(publicKey));
        byte[] s = (b64 + " " + name).getBytes();
        byte[] out = new byte[s.length + 1];
        System.arraycopy(s, 0, out, 0, s.length);
        out[s.length] = 0;
        return out;
    }

    static byte[] mincryptKey(RSAPublicKey pub) {
        final int words = 64; // 2048 bits / 32
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger n = pub.getModulus();
        BigInteger rr = BigInteger.ZERO.setBit(2048).modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = n.remainder(r32).modInverse(r32).negate();

        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(words);
        bb.putInt(n0inv.intValue());
        BigInteger t = n;
        for (int i = 0; i < words; i++) {
            bb.putInt(t.remainder(r32).intValue());
            t = t.shiftRight(32);
        }
        t = rr;
        for (int i = 0; i < words; i++) {
            bb.putInt(t.remainder(r32).intValue());
            t = t.shiftRight(32);
        }
        bb.putInt(pub.getPublicExponent().intValue());
        return bb.array();
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    /** Minimal standard Base64 encoder (android.util.Base64 isn't available on a desktop JVM). */
    static final class Base64Enc {
        private static final char[] T =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

        static String encode(byte[] d) {
            StringBuilder sb = new StringBuilder((d.length + 2) / 3 * 4);
            int i = 0;
            while (i + 2 < d.length) {
                int v = ((d[i] & 0xff) << 16) | ((d[i + 1] & 0xff) << 8) | (d[i + 2] & 0xff);
                sb.append(T[v >>> 18]).append(T[(v >>> 12) & 63]).append(T[(v >>> 6) & 63]).append(T[v & 63]);
                i += 3;
            }
            int rem = d.length - i;
            if (rem == 1) {
                int v = (d[i] & 0xff) << 16;
                sb.append(T[v >>> 18]).append(T[(v >>> 12) & 63]).append("==");
            } else if (rem == 2) {
                int v = ((d[i] & 0xff) << 16) | ((d[i + 1] & 0xff) << 8);
                sb.append(T[v >>> 18]).append(T[(v >>> 12) & 63]).append(T[(v >>> 6) & 63]).append('=');
            }
            return sb.toString();
        }
    }
}
