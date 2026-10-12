package com.example.floatingpad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private TextView status, log;
    private Button mainBtn, moreBtn;
    private LinearLayout advanced;
    private VirtualGamepadManager gamepad;
    private AdbConnector adb;
    private static final int SHIZUKU_REQUEST = 701;

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> runOnUiThread(() -> {
        if (requestCode != SHIZUKU_REQUEST) return;
        addLog(grantResult == PackageManager.PERMISSION_GRANTED ? "[OK] Permiso de Shizuku concedido." : "[ERROR] Permiso de Shizuku denegado.");
        refreshStatus();
    });
    private final Shizuku.OnBinderReceivedListener binderListener = () -> runOnUiThread(this::refreshStatus);
    private final Shizuku.OnBinderDeadListener deadListener = () -> runOnUiThread(this::refreshStatus);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        gamepad = VirtualGamepadManager.get(this);
        adb = AdbConnector.get(this);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addBinderDeadListener(deadListener);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Mando Flotante");
        title.setTextSize(24);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(8), 0, dp(12));
        root.addView(status);

        mainBtn = new Button(this);
        mainBtn.setTextSize(18);
        mainBtn.setOnClickListener(v -> nextStep());
        root.addView(mainBtn, new LinearLayout.LayoutParams(-1, dp(64)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button stop = new Button(this);
        stop.setText("Detener");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, FloatingControllerService.class));
            gamepad.stop();
            addLog("Mando detenido.");
            refreshStatus();
        });
        moreBtn = new Button(this);
        moreBtn.setText("Más opciones ▾");
        moreBtn.setOnClickListener(v -> {
            boolean show = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(show ? View.VISIBLE : View.GONE);
            moreBtn.setText(show ? "Menos opciones ▴" : "Más opciones ▾");
        });
        row.addView(stop, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(moreBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(row);

        advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        addButton(advanced, "Probar mando (pulsa A)", v -> {
            gamepad.sendKey(96, true);
            v.postDelayed(() -> gamepad.sendKey(96, false), 150);
            addLog("Botón A enviado.");
        });
        addButton(advanced, "Emparejar de nuevo", v -> startPairingGuide());
        addButton(advanced, "Reiniciar conexión", v -> {
            gamepad.reset();
            addLog("Conexión reiniciada.");
            connectGamepad();
        });
        addButton(advanced, "Olvidar emparejamiento", v -> {
            adb.forgetPairing();
            gamepad.reset();
            addLog("Emparejamiento olvidado.");
            refreshStatus();
        });
        addButton(advanced, "Diagnóstico del mando", v -> {
            addLog("Consultando al sistema…");
            gamepad.diagnose((ok, msg) -> runOnUiThread(() -> addLog(msg)));
        });
        addButton(advanced, "Registros detallados", v -> openDiagnosticTerminal());
        addButton(advanced, "Opciones de desarrollador", v -> openDevOptions());
        addButton(advanced, "Ajustes de notificaciones", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            } catch (Exception e) { toast("No se pudo abrir"); }
        });
        addButton(advanced, "Usar Shizuku (alternativa)", v -> {
            if (!Shizuku.pingBinder()) { toast("Shizuku no está activo"); return; }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) toast("Permiso de Shizuku ya concedido");
            else Shizuku.requestPermission(SHIZUKU_REQUEST);
        });
        root.addView(advanced);

        log = new TextView(this);
        log.setTextIsSelectable(true);
        log.setTextSize(13);
        log.setPadding(0, dp(16), 0, dp(16));
        root.addView(log);
        setContentView(scroll);
    }

    // ---------- Un solo botón que hace el siguiente paso ----------

    private boolean shizukuReady() {
        try { return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable e) { return false; }
    }

    private void nextStep() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            addLog("Activa «Mostrar sobre otras apps» para Mando Flotante y vuelve.");
            return;
        }
        if (!gamepad.hasBinder()) {
            if (shizukuReady() || adb.isPaired()) connectGamepad();
            else startPairingGuide();
            return;
        }
        startOverlay();
    }

    private String nextLabel() {
        if (!Settings.canDrawOverlays(this)) return "1. Permitir mostrar sobre otras apps";
        if (!gamepad.hasBinder()) return (shizukuReady() || adb.isPaired()) ? "2. Conectar mando" : "2. Emparejar (solo la primera vez)";
        return "3. Iniciar mando flotante";
    }

    private void connectGamepad() {
        addLog("Conectando…");
        gamepad.connect((ok, msg) -> runOnUiThread(() -> {
            addLog((ok ? "[OK] " : "[ERROR] ") + msg);
            refreshStatus();
        }));
    }

    private void startPairingGuide() {
        if (Build.VERSION.SDK_INT < 30) {
            addLog("[ERROR] La depuración inalámbrica necesita Android 11 o superior. Usa Shizuku en «Más opciones».");
            return;
        }
        Intent s = new Intent(this, PairingService.class).setAction(PairingService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
        addLog("Emparejar (una sola vez):\n"
                + " 1) Activa «Depuración inalámbrica».\n"
                + " 2) Toca «Vincular dispositivo con código de vinculación».\n"
                + " 3) Con ese cuadro abierto, baja las notificaciones, toca «Escribir código» y teclea los 6 dígitos.\n"
                + "(Si no detecta el puerto: escribe «123456 41234», código y puerto.)");
        openDevOptions();
    }

    private void openDevOptions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            addLog("Activa Opciones de desarrollador: Ajustes › Acerca del teléfono › toca 7 veces «Número de compilación».");
        }
    }

    private void startOverlay() {
        Intent i = new Intent(this, FloatingControllerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        addLog("Mando flotante iniciado.");
    }

    private void openDiagnosticTerminal() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12); panel.setPadding(pad, pad, pad, pad);
        TextView hint = new TextView(this);
        hint.setText("Terminal de diagnóstico. Android puede limitar el acceso a registros de otras apps; se mostrarán los registros disponibles y los eventos de Mando Flotante.");
        panel.addView(hint);
        EditText output = new EditText(this);
        output.setTextSize(11);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        output.setMinLines(12); output.setMaxLines(24); output.setSingleLine(false);
        output.setText("Mando Flotante: terminal de diagnóstico\nPulsa Capturar Logcat para leer registros disponibles.\n");
        panel.addView(output, new LinearLayout.LayoutParams(-1, dp(300)));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Terminal de diagnóstico")
            .setView(panel)
            .setPositiveButton("Capturar Logcat", null)
            .setNeutralButton("Copiar", null)
            .setNegativeButton("Cerrar", null)
            .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                output.setText("Capturando logcat…\n");
                new Thread(() -> {
                    StringBuilder result = new StringBuilder();
                    try {
                        Process proc = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "600").redirectErrorStream(true).start();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                        String line; int count = 0;
                        while ((line = reader.readLine()) != null && count < 600) {
                            if (line.contains("floatingpad") || line.contains("FloatingPad") || line.contains("Shizuku") || line.contains("uinput") || line.contains("AndroidRuntime") || line.contains("FATAL EXCEPTION") || line.contains("Permission Denial") || line.contains("UserService")) {
                                result.append(line).append('\n'); count++;
                            }
                        }
                        proc.destroy();
                        if (result.length() == 0) result.append("Android no devolvió líneas que coincidan con los filtros.\nPuede que el sistema limite el acceso a Logcat.\n");
                    } catch (Throwable e) { result.append("ERROR al ejecutar logcat: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n"); }
                    result.append("\n--- ESTADO ACTUAL ---\nShizuku activo: ").append(Shizuku.pingBinder());
                    try { result.append("\nPermiso Shizuku: ").append(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED); } catch (Throwable e) { result.append("\nPermiso Shizuku: error ").append(e.getMessage()); }
                    result.append("\nSuperposición: ").append(Settings.canDrawOverlays(MainActivity.this));
                    result.append("\nServicio Binder: ").append(gamepad.hasBinder() ? "conectado" : "desconectado").append("\nÚltimo evento interno: ").append(gamepad.getLastDiagnostic()).append('\n');
                    final String text = result.toString();
                    runOnUiThread(() -> output.setText(text));
                }).start();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Mando Flotante logs", output.getText().toString()));
                toast("Registros copiados");
            });
        });
        dialog.show();
    }


    private void refreshStatus() {
        if (status == null) return;
        boolean overlay = Settings.canDrawOverlays(this);
        boolean linked = shizukuReady() || adb.isPaired();
        status.setText((overlay ? "✔" : "✖") + " Superposición\n"
                + (linked ? "✔" : "✖") + " Emparejado" + (shizukuReady() ? " (Shizuku)" : adb.isPaired() ? " (ADB propio)" : "") + "\n"
                + (gamepad.hasBinder() ? "✔" : "✖") + " Mando virtual");
        mainBtn.setText(nextLabel());
    }

    private void addButton(LinearLayout root, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        root.addView(b);
    }

    private void addLog(String s) { if (log != null) log.append(s + "\n"); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onResume() {
        super.onResume();
        adb.setListener(msg -> {
            addLog(msg);
            refreshStatus();
            if (msg.startsWith("Listo: emparejado") && !gamepad.hasBinder()) connectGamepad(); // sigue solo
        });
        refreshStatus();
    }

    @Override protected void onPause() { adb.setListener(null); super.onPause(); }

    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        Shizuku.removeBinderReceivedListener(binderListener);
        Shizuku.removeBinderDeadListener(deadListener);
        super.onDestroy();
    }
}
