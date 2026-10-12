package com.example.floatingpad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guía el emparejamiento por depuración inalámbrica como Shizuku: la notificación SIEMPRE tiene
 * el campo para escribir el código. El puerto del cuadro «Vincular con código» se detecta solo
 * (mDNS); si no se detecta, se puede escribir también: «123456 41234» (código y puerto).
 */
public class PairingService extends Service {

    public static final String ACTION_START = "com.example.floatingpad.PAIR_START";
    public static final String ACTION_CODE = "com.example.floatingpad.PAIR_CODE";
    public static final String ACTION_STOP = "com.example.floatingpad.PAIR_STOP";

    private static final String CHANNEL = "pairing_channel";
    private static final int NOTIF_ID = 2;
    private static final String PAIRING_TYPE = "_adb-tls-pairing._tcp";
    private static final long WAIT_PORT_MS = 20000;

    private final Handler handler = new Handler();
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock lock;
    private volatile String host;
    private volatile int port = -1;
    private volatile boolean resolving, pairing;
    private volatile String pendingCode;
    private volatile int pendingPort = -1;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CODE.equals(action)) {
            handleCode(intent);
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("Escribe el código de 6 dígitos",
                "Abre «Vincular dispositivo con código» y toca «Escribir código» aquí", true));
        startDiscovery();
        return START_NOT_STICKY;
    }

    // ---------- Detección del puerto (mDNS) ----------

    private void startDiscovery() {
        if (discovery != null) return;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            lock = wm.createMulticastLock("pad_pairing");
            lock.setReferenceCounted(false);
            lock.acquire();
        } catch (Throwable ignored) { }
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) {
                AdbConnector.get(PairingService.this).status("No se pudo buscar el puerto automáticamente (código " + e
                        + "). Escribe el código y el puerto: «123456 41234».");
            }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceFound(NsdServiceInfo info) {
                if (info.getServiceType() != null && info.getServiceType().contains("_adb-tls-pairing")) resolve(info);
            }
            @Override public void onServiceLost(NsdServiceInfo info) { port = -1; }
        };
        try {
            nsd.discoverServices(PAIRING_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Throwable e) {
            AdbConnector.get(this).status("No se pudo iniciar la búsqueda automática: " + e.getMessage());
        }
    }

    private void resolve(NsdServiceInfo info) {
        if (resolving) return;
        resolving = true;
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int e) { resolving = false; }
                @Override public void onServiceResolved(NsdServiceInfo r) {
                    resolving = false;
                    if (r.getHost() == null) return;
                    host = r.getHost().getHostAddress();
                    port = r.getPort();
                    AdbConnector.get(PairingService.this).status("Cuadro de código detectado (puerto " + port + ").");
                    String code = pendingCode;
                    if (code != null && !pairing) startPairing(code, pendingPort > 0 ? pendingPort : port);
                }
            });
        } catch (Throwable e) {
            resolving = false;
        }
    }

    // ---------- Código escrito en la notificación ----------

    private void handleCode(Intent intent) {
        Bundle r = RemoteInput.getResultsFromIntent(intent);
        CharSequence cs = r == null ? null : r.getCharSequence("code");
        String text = cs == null ? "" : cs.toString();

        // «123456» o «123456 41234» (código + puerto opcional)
        List<String> nums = new ArrayList<String>();
        Matcher m = Pattern.compile("\\d+").matcher(text);
        while (m.find()) nums.add(m.group());
        String code = null;
        int manualPort = -1;
        for (String n : nums) {
            if (code == null && n.length() == 6) code = n;
            else if (manualPort < 0 && n.length() >= 4 && n.length() <= 5) {
                try { manualPort = Integer.parseInt(n); } catch (NumberFormatException ignored) { }
            }
        }
        if (code == null) {
            update("El código debe tener 6 dígitos", "Tócalo de nuevo e inténtalo otra vez", true);
            return;
        }
        if (pairing) return;

        int usePort = manualPort > 0 ? manualPort : port;
        if (usePort > 0) {
            startPairing(code, usePort);
            return;
        }
        // Aún no se detectó el puerto: se espera a que aparezca
        pendingCode = code;
        pendingPort = -1;
        update("Esperando el cuadro de código…", "Mantén abierto «Vincular dispositivo con código»", false);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (pendingCode != null && !pairing) {
                    pendingCode = null;
                    update("No encuentro el puerto automáticamente",
                            "Escribe código y puerto como en la pantalla: 123456 41234", true);
                }
            }
        }, WAIT_PORT_MS);
    }

    private String wifiIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip != 0) return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
        } catch (Throwable ignored) { }
        return "127.0.0.1";
    }

    private void startPairing(final String code, final int usePort) {
        pendingCode = null;
        pairing = true;
        update("Emparejando…", "No cierres el cuadro de código", false);
        final String h = (host != null && usePort == port) ? host : wifiIp();
        new Thread(new Runnable() {
            @Override public void run() {
                AdbConnector adb = AdbConnector.get(PairingService.this);
                try {
                    adb.pair(h, usePort, code);
                } catch (Throwable e) {
                    pairing = false;
                    adb.status("Fallo al emparejar con " + h + ":" + usePort + " → " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    update("Código incorrecto o caducado", "Revisa el número (y el puerto) y escríbelo de nuevo", true);
                    return;
                }
                String extra = "";
                try {
                    adb.ensureConnected();
                    extra = " y conectado";
                } catch (Throwable e) {
                    adb.status("Emparejado, pero aún no conecta: " + e.getMessage());
                }
                adb.status("Listo: emparejado" + extra + ". Vuelve a la app y pulsa «Conectar / crear mando».");
                update("✔ Emparejado" + extra, "Vuelve a Mando Flotante y pulsa «Conectar / crear mando»", false);
                handler.postDelayed(new Runnable() { @Override public void run() { stopSelf(); } }, 6000);
            }
        }).start();
    }

    // ---------- Notificación ----------

    private void update(String title, String text, boolean withInput) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(title, text, withInput));
    }

    private Notification buildNotification(String title, String text, boolean withInput) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Emparejamiento", NotificationManager.IMPORTANCE_HIGH));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true);
        if (withInput) {
            Intent i = new Intent(this, PairingService.class).setAction(ACTION_CODE);
            PendingIntent pi = PendingIntent.getService(this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT);
            RemoteInput ri = new RemoteInput.Builder("code").setLabel("Código de 6 dígitos").build();
            Notification.Action a = new Notification.Action.Builder(android.R.drawable.ic_menu_edit, "Escribir código", pi)
                    .addRemoteInput(ri).build();
            b.addAction(a);
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        try { if (nsd != null && discovery != null) nsd.stopServiceDiscovery(discovery); } catch (Throwable ignored) { }
        discovery = null;
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
