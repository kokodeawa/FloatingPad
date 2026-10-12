package com.example.floatingpad;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * Cliente ADB propio (depuración inalámbrica de Android 11+), basado en libadb-android.
 * Empareja una vez con el código de 6 dígitos y después se conecta solo vía mDNS.
 * Casi todas las llamadas a la librería van por reflexión para no depender de firmas exactas.
 */
public class AdbConnector {

    public interface StatusListener { void onStatus(String msg); }

    private static final String PREFS = "adb_connector";
    private static AdbConnector instance;

    private final Context ctx;
    private final SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile StatusListener listener;
    private Mgr mgr;

    private AdbConnector(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized AdbConnector get(Context c) {
        if (instance == null) instance = new AdbConnector(c);
        return instance;
    }

    public boolean isPaired() { return sp.getBoolean("paired", false); }

    public void setListener(StatusListener l) { listener = l; }

    public void status(final String m) {
        final StatusListener l = listener;
        if (l != null) ui.post(new Runnable() { @Override public void run() { l.onStatus(m); } });
    }

    // ---------- Claves y certificado (se guardan para no tener que emparejar otra vez) ----------

    private static class Mgr extends AbsAdbConnectionManager {
        private PrivateKey key;
        private Certificate cert;

        Mgr(SharedPreferences sp) throws Exception {
            trySetApi(Build.VERSION.SDK_INT);
            String k = sp.getString("key", null), c = sp.getString("cert", null);
            if (k != null && c != null) {
                try {
                    key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(k, Base64.NO_WRAP)));
                    cert = CertificateFactory.getInstance("X.509")
                            .generateCertificate(new ByteArrayInputStream(Base64.decode(c, Base64.NO_WRAP)));
                } catch (Exception e) {
                    key = null;
                    cert = null;
                }
            }
            if (key == null || cert == null) {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
                KeyPair pair = kpg.generateKeyPair();
                PublicKey publicKey = pair.getPublic();
                key = pair.getPrivate();
                cert = selfSignedCert(publicKey, key);
                sp.edit().putString("key", Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP))
                        .putString("cert", Base64.encodeToString(cert.getEncoded(), Base64.NO_WRAP)).apply();
            }
        }

        /** setApi existe solo en algunas versiones de la librería; se llama por reflexión. */
        private void trySetApi(int api) {
            for (Class<?> c = getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod("setApi", int.class);
                    m.setAccessible(true);
                    m.invoke(this, api);
                    return;
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable t) {
                    return;
                }
            }
        }

        // ---- Certificado X.509 autofirmado construido a mano (DER), sin librerías extra ----

        private static byte[] len(int n) {
            if (n < 128) return new byte[]{(byte) n};
            if (n < 256) return new byte[]{(byte) 0x81, (byte) n};
            return new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
        }

        private static byte[] der(int tag, byte[]... parts) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (byte[] p : parts) body.write(p);
            byte[] b = body.toByteArray();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(tag);
            o.write(len(b.length));
            o.write(b);
            return o.toByteArray();
        }

        private static byte[] utcTime(long ms) throws Exception {
            SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return der(0x17, f.format(new Date(ms)).getBytes("US-ASCII"));
        }

        private static Certificate selfSignedCert(PublicKey pub, PrivateKey priv) throws Exception {
            byte[] sigAlg = der(0x30, new byte[]{0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B},
                    new byte[]{0x05, 0x00}); // sha256WithRSAEncryption
            byte[] serial = new byte[8];
            new SecureRandom().nextBytes(serial);
            serial[0] = (byte) ((serial[0] & 0x7F) | 0x01);
            byte[] name = der(0x30, der(0x31, der(0x30, new byte[]{0x06, 0x03, 0x55, 0x04, 0x03},
                    der(0x0C, "FloatingPad".getBytes("UTF-8")))));
            long now = System.currentTimeMillis();
            byte[] validity = der(0x30, utcTime(now - 86400000L), utcTime(now + 3650L * 86400000L));
            byte[] version = new byte[]{(byte) 0xA0, 0x03, 0x02, 0x01, 0x02}; // v3
            byte[] tbs = der(0x30, version, der(0x02, serial), sigAlg, name, validity, name, pub.getEncoded());
            Signature sg = Signature.getInstance("SHA256withRSA");
            sg.initSign(priv);
            sg.update(tbs);
            byte[] sig = sg.sign();
            byte[] bits = der(0x03, new byte[]{0x00}, sig);
            byte[] certDer = der(0x30, tbs, sigAlg, bits);
            return CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certDer));
        }

        @Override protected PrivateKey getPrivateKey() { return key; }
        @Override protected Certificate getCertificate() { return cert; }
        @Override protected String getDeviceName() { return "FloatingPad"; }
    }

    private synchronized Mgr manager() throws Exception {
        if (mgr == null) mgr = new Mgr(sp);
        return mgr;
    }

    // ---------- Reflexión ----------

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        try {
            Method m = target.getClass().getMethod(name, types);
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw (c instanceof Exception) ? (Exception) c : new Exception(c);
        }
    }

    private boolean connectedNow() {
        try {
            Object r = call(manager(), "isConnected", new Class<?>[0]);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable e) {
            return false;
        }
    }

    private void quietDisconnect() {
        try { call(manager(), "disconnect", new Class<?>[0]); } catch (Throwable ignored) { }
    }

    // ---------- Operaciones ----------

    /** Empareja con el código de 6 dígitos del cuadro «Vincular con código». */
    public synchronized void pair(String host, int port, String code) throws Exception {
        call(manager(), "pair", new Class<?>[]{String.class, int.class, String.class}, host, port, code);
        sp.edit().putBoolean("paired", true).apply();
        status("Emparejado correctamente con el teléfono.");
    }

    /** Busca por mDNS el servicio _adb-tls-connect del propio teléfono. Devuelve {host, puerto}. */
    private String[] discoverConnect(long timeoutMs) throws Exception {
        final NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] res = new String[2];
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            lock = wm.createMulticastLock("pad_connect");
            lock.setReferenceCounted(false);
            lock.acquire();
        } catch (Throwable ignored) { }
        NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { latch.countDown(); }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo i) { }
            @Override public void onServiceFound(NsdServiceInfo info) {
                if (info.getServiceType() == null || !info.getServiceType().contains("_adb-tls-connect")) return;
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo i, int e) { }
                        @Override public void onServiceResolved(NsdServiceInfo r) {
                            if (res[0] == null && r.getHost() != null) {
                                String h = r.getHost().getHostAddress();
                                if (h == null || h.indexOf(':') >= 0) h = wifiIp(); // evita IPv6 link-local
                                res[0] = h;
                                res[1] = String.valueOf(r.getPort());
                                latch.countDown();
                            }
                        }
                    });
                } catch (Throwable ignored) { }
            }
        };
        try {
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, dl);
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            try { nsd.stopServiceDiscovery(dl); } catch (Throwable ignored) { }
            try { if (lock != null && lock.isHeld()) lock.release(); } catch (Throwable ignored) { }
        }
        if (res[0] == null) {
            throw new Exception("No encuentro la depuración inalámbrica. Actívala en Opciones de desarrollador y conecta el móvil a Wi-Fi.");
        }
        return res;
    }

    private String wifiIp() {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip != 0) return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
        } catch (Throwable ignored) { }
        return "127.0.0.1";
    }

    /** Se conecta a la depuración inalámbrica (IP y puerto descubiertos por la propia app). */
    public synchronized void ensureConnected() throws Exception {
        if (connectedNow()) return;
        String[] hp = discoverConnect(12000);
        status("Depuración inalámbrica encontrada en " + hp[0] + ":" + hp[1]);
        Object ok = call(manager(), "connect", new Class<?>[]{String.class, int.class}, hp[0], Integer.parseInt(hp[1]));
        if (ok instanceof Boolean && !((Boolean) ok)) {
            throw new Exception("El teléfono rechazó la conexión ADB. Vuelve a emparejar.");
        }
    }

    public synchronized void forgetPairing() {
        quietDisconnect();
        mgr = null;
        sp.edit().clear().apply();
    }

    /** Ejecuta un comando como shell por ADB (sin PTY): "exec:" + comando. */
    public synchronized ShellSession openExec(String command) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensureConnected();
                final Object stream = call(manager(), "openStream", new Class<?>[]{String.class}, "exec:" + command);
                InputStream in = (InputStream) call(stream, "openInputStream", new Class<?>[0]);
                OutputStream out = (OutputStream) call(stream, "openOutputStream", new Class<?>[0]);
                return new ShellSession.AdbSession(in, out, new Runnable() {
                    @Override public void run() {
                        try { call(stream, "close", new Class<?>[0]); } catch (Throwable ignored) { }
                    }
                });
            } catch (Exception e) {
                last = e;
                quietDisconnect(); // la librería a veces deja conexiones rotas; se reintenta una vez
            }
        }
        throw last != null ? last : new IllegalStateException("No se pudo abrir el shell ADB");
    }

    /** Como openExec, pero SIN reiniciar la conexión si falla (sesiones auxiliares como teclado/ratón). */
    public synchronized ShellSession openExecKeep(String command) throws Exception {
        ensureConnected();
        final Object stream = call(manager(), "openStream", new Class<?>[]{String.class}, "exec:" + command);
        InputStream in = (InputStream) call(stream, "openInputStream", new Class<?>[0]);
        OutputStream out = (OutputStream) call(stream, "openOutputStream", new Class<?>[0]);
        return new ShellSession.AdbSession(in, out, new Runnable() {
            @Override public void run() {
                try { call(stream, "close", new Class<?>[0]); } catch (Throwable ignored) { }
            }
        });
    }
}
