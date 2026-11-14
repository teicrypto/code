package com.teicrypto.syspulse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Tạo chứng chỉ X.509 tự ký (SHA256withRSA) bằng cách tự mã hóa DER, chỉ dùng Java chuẩn.
 * Thay cho sun.security.x509 (thư viện đó không biên dịch được trên GitHub Actions).
 */
final class CertUtil {
    private CertUtil() {
    }

    static Certificate selfSigned(KeyPair kp, String cn, long validityMs) throws Exception {
        // AlgorithmIdentifier: sha256WithRSAEncryption, tham số NULL
        byte[] sigAlg = hex("300D06092A864886F70D01010B0500");

        // Name: CN=<cn>
        byte[] name = tlv(0x30, tlv(0x31, tlv(0x30, cat(hex("0603550403"), tlv(0x0C, cn.getBytes(StandardCharsets.UTF_8))))));

        long now = System.currentTimeMillis();
        byte[] validity = tlv(0x30, cat(time(now - 86400000L), time(now + validityMs)));

        byte[] version = hex("A003020102"); // [0] EXPLICIT INTEGER 2 (v3)
        byte[] serial = tlv(0x02, new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] spki = kp.getPublic().getEncoded(); // SubjectPublicKeyInfo (DER)

        byte[] tbs = tlv(0x30, cat(version, serial, sigAlg, name, validity, name, spki));

        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();

        byte[] cert = tlv(0x30, cat(tbs, sigAlg, tlv(0x03, cat(new byte[]{0}, sig))));
        return CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
    }

    // UTCTime "yyMMddHHmmssZ" (hợp lệ cho năm 1950-2049)
    private static byte[] time(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(new java.util.Date(millis)).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = content.length;
        if (n < 128) {
            o.write(n);
        } else if (n < 256) {
            o.write(0x81);
            o.write(n);
        } else if (n < 65536) {
            o.write(0x82);
            o.write(n >> 8);
            o.write(n & 0xFF);
        } else {
            o.write(0x83);
            o.write(n >> 16);
            o.write((n >> 8) & 0xFF);
            o.write(n & 0xFF);
        }
        o.write(content, 0, content.length);
        return o.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }
}
