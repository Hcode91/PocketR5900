package com.hcode91.pocketr5900;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.util.Calendar;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/** The key exported games are signed with, and installing them. */
public class Installer extends BroadcastReceiver {
    private static final String ALIAS = "pocketr5900-export";
    static final String ACTION_RESULT = "com.hcode91.pocketr5900.INSTALL_RESULT";

    /** A signing key kept in the phone's keystore, made the first time it is needed. */
    static KeyStore.PrivateKeyEntry key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            Calendar end = Calendar.getInstance();
            end.set(2100, Calendar.JANUARY, 1);
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=PocketR5900 export"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(new Date(0))
                    .setCertificateNotAfter(end.getTime())
                    .build());
            g.generateKeyPair();
        }
        return (KeyStore.PrivateKeyEntry) ks.getEntry(ALIAS, null);
    }

    /** Hands an APK to Android's installer; the user confirms on screen. */
    static void install(Context context, File apk) throws IOException {
        PackageInstaller pi = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(apk.length());
        int id = pi.createSession(params);
        try (PackageInstaller.Session session = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("game.apk", 0, apk.length())) {
                byte[] b = new byte[1 << 20];
                int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                }
                session.fsync(out);
            }
            Intent result = new Intent(context, Installer.class).setAction(ACTION_RESULT);
            PendingIntent pending = PendingIntent.getBroadcast(context, id, result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (RuntimeException e) {
                    Log.e("PocketR5900", "cannot show the installer", e);
                }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "Game installed. It is on your home screen / app drawer.", Toast.LENGTH_LONG).show();
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, "Install failed: " + (msg != null ? msg : status), Toast.LENGTH_LONG).show();
        }
    }
}
