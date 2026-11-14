package com.teicrypto.syspulse;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * Quản lý kết nối ADB (theo ví dụ trong README của libadb-android).
 * Khác ví dụ gốc:
 *  - Khóa + chứng chỉ được LƯU vào bộ nhớ riêng của app, để ghép nối (pair) chỉ phải làm một lần.
 *  - Chứng chỉ tự ký được tạo bằng CertUtil (Java thuần) thay cho sun.security.x509.
 */
public class SysAdbManager extends AbsAdbConnectionManager {
    private static SysAdbManager INSTANCE;

    public static synchronized SysAdbManager getInstance(Context context) throws Exception {
        if (INSTANCE == null) {
            INSTANCE = new SysAdbManager(context.getApplicationContext());
        }
        return INSTANCE;
    }

    /** Tự tìm cổng TLS bằng mDNS của chính thư viện rồi kết nối (giống cách Shizuku làm). */
    public boolean tlsAuto(Context context, long timeoutMs) throws Exception {
        return connectTls(context, timeoutMs);
    }

    private PrivateKey mPrivateKey;
    private Certificate mCertificate;

    private SysAdbManager(Context context) throws Exception {
        // Phiên bản Android mà adbd đang chạy (ở đây là chính máy này)
        setApi(Build.VERSION.SDK_INT);

        File keyFile = new File(context.getFilesDir(), "adb_key.pk8");
        File certFile = new File(context.getFilesDir(), "adb_cert.der");

        // Thử nạp khóa đã lưu
        try {
            if (keyFile.exists() && certFile.exists()) {
                byte[] keyBytes = Files.readAllBytes(keyFile.toPath());
                byte[] certBytes = Files.readAllBytes(certFile.toPath());
                mPrivateKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
                mCertificate = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(certBytes));
            }
        } catch (Exception e) {
            mPrivateKey = null;
            mCertificate = null;
        }

        if (mPrivateKey == null || mCertificate == null) {
            // Sinh cặp khóa mới + chứng chỉ tự ký (~10 năm)
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048, new SecureRandom());
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            mPrivateKey = keyPair.getPrivate();
            mCertificate = CertUtil.selfSigned(keyPair, "SysPulse", 3650L * 86400000L);

            // Lưu lại để lần sau dùng đúng khóa này
            try (FileOutputStream k = new FileOutputStream(keyFile);
                 FileOutputStream c = new FileOutputStream(certFile)) {
                k.write(mPrivateKey.getEncoded());
                c.write(mCertificate.getEncoded());
            }
        }
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return mPrivateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return mCertificate;
    }

    @Override
    protected String getDeviceName() {
        return "SysPulse";
    }
}
