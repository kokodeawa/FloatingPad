package com.example.floatingpad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.floatingpad.PadModel.Ctl;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Overlay del mando. En juego NO ocupa la pantalla: cada grupo de controles es una ventana pequeña y el resto
 * de la pantalla sigue siendo de la app de debajo. Siempre hay un "tirador" 🎮 pequeño, dentro del área útil
 * (nunca bajo la barra de estado), que abre un menú: Ocultar/Mostrar, Ajustes, Editar y Salir. Además la
 * notificación del servicio tiene Mostrar/Ocultar y Cerrar.
 */
public class FloatingControllerService extends Service implements GamepadView.Listener {

    public static final String ACTION_TOGGLE = "com.example.floatingpad.OVERLAY_TOGGLE";
    public static final String ACTION_STOP = "com.example.floatingpad.OVERLAY_STOP";

    private static final String CHANNEL = "overlay_channel";
    private static final float[] GRID_STEPS = {0f, 0.02f, 0.03f, 0.05f};
    private static final String[] BUILTIN_LAND = {"Asimétrico", "Simétrico", "Teclado"};
    private static final String[] BUILTIN_PORT = {"Asimétrico vertical", "Simétrico vertical", "Teclado vertical"};
    private static final int MENU_TIMEOUT_MS = 6000;

    private static class PadWin {
        GamepadView view;
        WindowManager.LayoutParams lp;
        String sig;
    }

    private interface PickCb { void onPick(KeyDefs.Def d); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private SharedPreferences ui;
    private Vibrator vib;
    private VirtualGamepadManager gamepad;
    private final PadModel model = new PadModel();
    private float density = 1f;

    // marco útil (área donde se colocan las ventanas, sin barras del sistema)
    private int frameW, frameH, measureToken;
    private boolean ready, padsHidden, editing, destroyed;
    private static final int TOUCH_NORMAL = 0, TOUCH_THROUGH = 1, TOUCH_ONLY_BUTTONS = 2;
    private static final int MAX_WINDOWS = 8;       // máximo de ventanas del mando (rendimiento)
    private int touchMode;                          // normal / tocar pantalla / solo botones
    private TextView tvPass, modeHelp;
    private final TextView[] modeBtns = new TextView[3];
    private View blocker;                           // ventana transparente que bloquea el fondo (modo solo botones)
    private WindowManager.LayoutParams blockerLp;
    private int lastOrientation = -1, lastWdp, lastHdp;

    // teclado en pantalla propio
    private ScreenKeyboard kb;
    private WindowManager.LayoutParams kbLp;
    private boolean kbAttached;
    private float kbFrac;
    private int kbStartX, kbStartY;

    // ventanas de juego
    private final List<PadWin> wins = new ArrayList<PadWin>();
    private float alpha = 0.75f;

    // tirador
    private LinearLayout handleRow;
    private TextView handleMain, tvToggle, tvSettings, tvEdit, tvExit;
    private WindowManager.LayoutParams handleLp;
    private boolean handleAttached, menuOpen;

    // ajustes
    private ScrollView settingsPanel;
    private WindowManager.LayoutParams settingsLp;
    private boolean settingsAttached, syncing;
    private TextView tvSize, tvProfile;
    private SeekBar seekSize;

    // edición
    private FrameLayout editRoot, pickerHolder;
    private WindowManager.LayoutParams editLp;
    private GamepadView editView;
    private HorizontalScrollView editBar;
    private TextView tvSel, btnGrid, btnReset, btnDel, btnSensM, btnSensP;
    private int gridIdx = 2;
    private boolean resetArmed, delArmed;

    // perfiles
    private final List<String> pnames = new ArrayList<String>();
    private int pidx = 0;

    private final Runnable collapseMenu = new Runnable() {
        @Override public void run() { setMenuOpen(false); }
    };

    // ---------- Ciclo de vida ----------

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopSelf();
        } else if (ACTION_TOGGLE.equals(a) && ready) {
            if (editing) exitEdit(); else togglePads();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, buildNotification());
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        density = getResources().getDisplayMetrics().density;
        ui = getSharedPreferences("pad_ui", Context.MODE_PRIVATE);
        touchMode = Math.max(0, Math.min(2, ui.getInt("touch_mode", TOUCH_NORMAL)));
        Configuration cfg = getResources().getConfiguration();
        lastOrientation = cfg.orientation;
        lastWdp = cfg.screenWidthDp;
        lastHdp = cfg.screenHeightDp;
        vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        gamepad = VirtualGamepadManager.get(this);
        gamepad.kbm().setListener(new KbmDevices.Listener() {
            @Override public void onState(final boolean ok, final String message) {
                main.post(new Runnable() {
                    @Override public void run() { if (!destroyed) toast("Teclado/ratón virtual: " + message); }
                });
            }
        });
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        measureFrame(new Runnable() { @Override public void run() { onFrameReady(); } });
    }

    private PendingIntent svcIntent(int req, String action) {
        Intent i = new Intent(this, FloatingControllerService.class).setAction(action);
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Mando flotante", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
        return b.setContentTitle("Mando flotante activo")
                .setContentText("Toca 🎮 en pantalla, o usa estos botones")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_view, "Mostrar / ocultar",
                        svcIntent(1, ACTION_TOGGLE)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Cerrar",
                        svcIntent(2, ACTION_STOP)).build())
                .build();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        releasePads();
        removePads();
        closeSettings();
        removeKeyboard();
        removeHandle();
        removeEditRoot();
        if (gamepad != null) {
            gamepad.sendAxis(true, 0, 0);
            gamepad.sendAxis(false, 0, 0);
            gamepad.releaseAllKeys();
            gamepad.kbm().setListener(null);
            gamepad.releaseKbm();
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Android también avisa cuando aparece o desaparece un teclado/ratón virtual. Eso NO debe reconstruir el
        // mando (cancelaría los gestos en curso): solo se reconstruye si cambia la orientación o el tamaño.
        boolean changed = newConfig.orientation != lastOrientation || newConfig.screenWidthDp != lastWdp
                || newConfig.screenHeightDp != lastHdp;
        lastOrientation = newConfig.orientation;
        lastWdp = newConfig.screenWidthDp;
        lastHdp = newConfig.screenHeightDp;
        if (!changed || !ready || destroyed) return;
        ready = false;
        if (editing) exitEditNoRebuild();
        releasePads();
        removePads();
        closeSettings();
        removeKeyboard();
        removeHandle();
        measureFrame(new Runnable() { @Override public void run() { onFrameReady(); } });
    }

    // ---------- Utilidades de interfaz ----------

    private int dp(float v) { return (int) (v * density + 0.5f); }

    private GradientDrawable box(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView mkBtn(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        b.setBackground(box(0xFF2E2E2E, 8));
        b.setOnClickListener(l);
        return b;
    }

    private TextView mkLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13);
        return t;
    }

    private LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        return p;
    }

    private LinearLayout.LayoutParams wrapMargin() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(3), dp(2), dp(3), dp(2));
        return p;
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /** Ventana que NO roba el foco y deja pasar los toques de fuera; con toque dividido para multitáctil entre ventanas. */
    private WindowManager.LayoutParams winParams(int w, int h, int x, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = x;
        p.y = y;
        return p;
    }

    // ---------- Marco útil de pantalla ----------

    /**
     * Mide el área donde Android coloca las ventanas normales de superposición (debajo de la barra de estado y
     * de la barra de navegación). Todo el overlay se coloca dentro de ella, así nada queda intocable.
     */
    private void measureFrame(final Runnable then) {
        final int token = ++measureToken;
        final View probe = new View(this);
        final boolean[] done = {false};
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        final Runnable finish = new Runnable() {
            @Override public void run() {
                if (done[0]) return;
                done[0] = true;
                try { wm.removeView(probe); } catch (Exception ignored) { }
                if (token == measureToken && !destroyed) then.run();
            }
        };
        probe.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or2, int ob) {
                if (done[0] || r - l <= 0 || b - t <= 0) return;
                frameW = r - l;
                frameH = b - t;
                main.post(finish);
            }
        });
        try {
            wm.addView(probe, p);
        } catch (Exception e) {
            fallbackFrame();
            main.post(finish);
            return;
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!done[0]) { fallbackFrame(); finish.run(); }
            }
        }, 1500);
    }

    private void fallbackFrame() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int sb = 0;
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) sb = getResources().getDimensionPixelSize(id);
        frameW = dm.widthPixels;
        frameH = Math.max(100, dm.heightPixels - sb);
    }

    private boolean portrait() { return frameH > frameW; }

    private void onFrameReady() {
        if (destroyed) return;
        density = getResources().getDisplayMetrics().density;
        model.setFrame(frameW, frameH, density);
        ready = true;
        loadProfileForOrientation();
        createHandle();
        if (!padsHidden && wins.isEmpty()) showPads();
    }

    // ---------- Ventanas de juego ----------

    private void showPads() {
        removePads();
        if (!ready || editing) return;
        addBlocker();
        for (PadModel.Cluster c : model.clusters(MAX_WINDOWS)) {
            PadWin w = new PadWin();
            w.sig = c.signature();
            w.view = new GamepadView(this);
            w.view.bind(model, c.members, c.l, c.t);
            w.view.setListener(this);
            w.view.setAlpha(effectiveAlpha());
            w.lp = winParams(c.r - c.l, c.b - c.t, c.l, c.t);
            if (touchMode == TOUCH_THROUGH) w.lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                wm.addView(w.view, w.lp);
                wins.add(w);
            } catch (Exception ignored) { }
        }
        restack();
    }

    /** Si la agrupación no cambió solo se reposicionan las ventanas; si cambió se reconstruyen. */
    private void refreshPads() {
        if (!ready || editing || padsHidden) return;
        List<PadModel.Cluster> cl = model.clusters(MAX_WINDOWS);
        boolean same = cl.size() == wins.size();
        for (int i = 0; same && i < cl.size(); i++) if (!cl.get(i).signature().equals(wins.get(i).sig)) same = false;
        if (!same) { releasePads(); showPads(); return; }
        for (int i = 0; i < cl.size(); i++) {
            PadModel.Cluster c = cl.get(i);
            PadWin w = wins.get(i);
            w.lp.x = c.l;
            w.lp.y = c.t;
            w.lp.width = c.r - c.l;
            w.lp.height = c.b - c.t;
            w.view.bind(model, c.members, c.l, c.t);
            try { wm.updateViewLayout(w.view, w.lp); } catch (Exception ignored) { }
            w.view.invalidate();
        }
    }

    private void releasePads() {
        for (PadWin w : wins) w.view.releaseAll();
        if (gamepad != null) gamepad.releaseAllKeys();
    }

    private void removePads() {
        for (PadWin w : wins) {
            try { wm.removeView(w.view); } catch (Exception ignored) { }
        }
        wins.clear();
        removeBlocker();
    }

    private void applyAlpha() {
        for (PadWin w : wins) w.view.setAlpha(effectiveAlpha());
    }

    private void togglePads() {
        padsHidden = !padsHidden;
        if (padsHidden) { releasePads(); removePads(); } else showPads();
        updateToggleLabel();
        if (!destroyed) toast(padsHidden ? "Mando oculto (toca 🎮 para mostrarlo)" : "Mando visible");
    }

    // ---------- Tirador (siempre accesible) ----------

    private String posKey(String k) { return k + (portrait() ? "_p" : "_l"); }

    private void createHandle() {
        removeHandle();
        handleRow = new LinearLayout(this);
        handleRow.setOrientation(LinearLayout.VERTICAL);
        handleRow.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = box(0xDD111111, 18);
        bg.setStroke(dp(1), 0x66FFFFFF);
        handleRow.setBackground(bg);
        handleRow.setAlpha(0.9f);

        handleMain = new TextView(this);
        handleMain.setText("🎮");
        handleMain.setTextSize(18);
        handleMain.setGravity(Gravity.CENTER);
        handleMain.setPadding(dp(14), dp(7), dp(14), dp(7));
        handleRow.addView(handleMain);

        tvToggle = menuItem("Ocultar", 0xFFFFFFFF, new View.OnClickListener() {
            @Override public void onClick(View v) { togglePads(); armCollapse(); }
        });
        tvPass = menuItem(modeLabel(), 0xFFFFFFFF, new View.OnClickListener() {
            @Override public void onClick(View v) { setTouchMode((touchMode + 1) % 3); armCollapse(); }
        });
        tvSettings = menuItem("Ajustes", 0xFFFFFFFF, new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); setMenuOpen(false); }
        });
        tvEdit = menuItem("Editar", 0xFFFFFFFF, new View.OnClickListener() {
            @Override public void onClick(View v) { setMenuOpen(false); enterEdit(); }
        });
        tvExit = menuItem("✕ Salir", 0xFFFF8080, new View.OnClickListener() {
            @Override public void onClick(View v) { stopSelf(); }
        });
        updateToggleLabel();
        updateHandleIcon();

        handleMain.setOnTouchListener(new View.OnTouchListener() {
            private int sx, sy;
            private float tx, ty;
            private boolean dragging;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = handleLp.x;
                        sy = handleLp.y;
                        tx = e.getRawX();
                        ty = e.getRawY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - tx, dy = e.getRawY() - ty;
                        int slop = ViewConfiguration.get(FloatingControllerService.this).getScaledTouchSlop();
                        if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) dragging = true;
                        if (dragging) {
                            handleLp.x = sx + (int) dx;
                            handleLp.y = sy + (int) dy;
                            clampHandle();
                            try { wm.updateViewLayout(handleRow, handleLp); } catch (Exception ignored) { }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragging) saveHandlePos(); else setMenuOpen(!menuOpen);
                        return true;
                }
                return false;
            }
        });

        handleLp = winParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, 0, 0);
        float fx = ui.getFloat(posKey("hx"), -1f), fy = ui.getFloat(posKey("hy"), -1f);
        handleLp.x = fx < 0 ? frameW / 2 - dp(24) : (int) (fx * frameW);
        handleLp.y = fy < 0 ? 0 : (int) (fy * frameH);
        try {
            wm.addView(handleRow, handleLp);
            handleAttached = true;
        } catch (Exception ignored) { }
        handleRow.post(new Runnable() {
            @Override public void run() {
                clampHandle();
                if (handleAttached) try { wm.updateViewLayout(handleRow, handleLp); } catch (Exception ignored) { }
            }
        });
    }

    private TextView menuItem(String text, int color, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(9), dp(12), dp(9));
        t.setOnClickListener(l);
        t.setVisibility(View.GONE);
        t.setMinWidth(dp(150));
        handleRow.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private void updateToggleLabel() {
        if (tvToggle != null) tvToggle.setText(padsHidden ? "Mostrar" : "Ocultar");
    }

    private void setMenuOpen(boolean open) {
        if (handleRow == null) return;
        menuOpen = open;
        int v = open ? View.VISIBLE : View.GONE;
        tvToggle.setVisibility(v);
        tvPass.setVisibility(v);
        tvSettings.setVisibility(v);
        tvEdit.setVisibility(v);
        tvExit.setVisibility(v);
        main.removeCallbacks(collapseMenu);
        if (open) main.postDelayed(collapseMenu, MENU_TIMEOUT_MS);
        handleRow.post(new Runnable() {
            @Override public void run() {
                clampHandle();
                if (handleAttached) try { wm.updateViewLayout(handleRow, handleLp); } catch (Exception ignored) { }
            }
        });
    }

    private void armCollapse() {
        main.removeCallbacks(collapseMenu);
        main.postDelayed(collapseMenu, MENU_TIMEOUT_MS);
    }

    private void clampHandle() {
        if (handleRow == null) return;
        int w = handleRow.getWidth() > 0 ? handleRow.getWidth() : dp(52);
        int h = handleRow.getHeight() > 0 ? handleRow.getHeight() : dp(40);
        handleLp.x = Math.max(0, Math.min(handleLp.x, frameW - w));
        handleLp.y = Math.max(0, Math.min(handleLp.y, frameH - h));
    }

    private void saveHandlePos() {
        ui.edit().putFloat(posKey("hx"), handleLp.x / (float) Math.max(1, frameW))
                .putFloat(posKey("hy"), handleLp.y / (float) Math.max(1, frameH)).apply();
    }

    private void bringHandleToFront() {
        if (!handleAttached || handleRow == null) return;
        try {
            wm.removeView(handleRow);
            wm.addView(handleRow, handleLp);
        } catch (Exception ignored) { }
    }

    private void removeHandle() {
        main.removeCallbacks(collapseMenu);
        if (handleAttached && handleRow != null) {
            try { wm.removeView(handleRow); } catch (Exception ignored) { }
        }
        handleAttached = false;
        menuOpen = false;
        handleRow = null;
    }

    // ---------- Ajustes (ventana pequeña) ----------

    private void openSettings() {
        if (settingsAttached) { closeSettings(); return; }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(6), dp(10), dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = mkLabel("Ajustes del mando");
        title.setTextSize(15);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = mkBtn("✕", new View.OnClickListener() {
            @Override public void onClick(View v) { closeSettings(); }
        });
        head.addView(close, wrapMargin());
        col.addView(head);

        col.addView(mkLabel("Transparencia"));
        SeekBar sa = new SeekBar(this);
        sa.setMax(85);
        sa.setProgress(Math.round(alpha * 100) - 15);
        sa.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                alpha = 0.15f + p / 100f;
                applyAlpha();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        col.addView(sa);

        tvSize = mkLabel("Tamaño");
        col.addView(tvSize);
        seekSize = new SeekBar(this);
        seekSize.setMax(Math.round((PadModel.MAX_SCALE - PadModel.MIN_SCALE) * 100));
        seekSize.setProgress(Math.round((model.getScale() - PadModel.MIN_SCALE) * 100));
        seekSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user || syncing) return;
                float want = PadModel.MIN_SCALE + p / 100f;
                float eff = model.setScale(want);
                if (eff < want - 0.004f) syncSizeSeek(eff);
                updateSizeLabel();
                refreshPads();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        col.addView(seekSize);
        updateSizeLabel();

        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wide.setMargins(dp(3), dp(8), dp(3), dp(3));
        TextView pl = mkLabel("Pantalla");
        pl.setPadding(dp(3), dp(8), 0, 0);
        col.addView(pl);
        LinearLayout mrow = new LinearLayout(this);
        mrow.setOrientation(LinearLayout.HORIZONTAL);
        String[] mnames = {"Normal", "Tocar pantalla", "Solo botones"};
        for (int i = 0; i < 3; i++) {
            final int mi = i;
            modeBtns[i] = mkBtn(mnames[i], new View.OnClickListener() {
                @Override public void onClick(View v) { setTouchMode(mi); }
            });
            modeBtns[i].setTextSize(12);
            mrow.addView(modeBtns[i], weight(1f));
        }
        col.addView(mrow);
        modeHelp = mkLabel("");
        modeHelp.setTextSize(11);
        modeHelp.setTextColor(0xFFB8C4D0);
        modeHelp.setPadding(dp(3), dp(2), dp(3), dp(4));
        col.addView(modeHelp);
        refreshModeUi();
        col.addView(mkBtn("Ajustes del teclado del sistema", new View.OnClickListener() {
            @Override public void onClick(View v) { openSystemKeyboardSettings(); }
        }), wide);

        LinearLayout prow = new LinearLayout(this);
        prow.setOrientation(LinearLayout.HORIZONTAL);
        prow.setGravity(Gravity.CENTER_VERTICAL);
        prow.addView(mkBtn("◀", new View.OnClickListener() {
            @Override public void onClick(View v) { cycleProfile(-1); }
        }), wrapMargin());
        tvProfile = mkLabel("");
        tvProfile.setGravity(Gravity.CENTER);
        tvProfile.setTextSize(14);
        prow.addView(tvProfile, weight(1f));
        prow.addView(mkBtn("▶", new View.OnClickListener() {
            @Override public void onClick(View v) { cycleProfile(1); }
        }), wrapMargin());
        col.addView(prow);
        updateProfileLabel();

        LinearLayout brow = new LinearLayout(this);
        brow.setOrientation(LinearLayout.HORIZONTAL);
        brow.addView(mkBtn("Guardar", new View.OnClickListener() {
            @Override public void onClick(View v) { saveProfile(false); }
        }), weight(1f));
        brow.addView(mkBtn("Nuevo", new View.OnClickListener() {
            @Override public void onClick(View v) { saveProfile(true); }
        }), weight(1f));
        brow.addView(mkBtn("Borrar", new View.OnClickListener() {
            @Override public void onClick(View v) { deleteProfile(); }
        }), weight(1f));
        col.addView(brow);

        TextView editBtn = mkBtn("✎  Editar controles", new View.OnClickListener() {
            @Override public void onClick(View v) { closeSettings(); enterEdit(); }
        });
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ep.setMargins(dp(3), dp(6), dp(3), dp(3));
        col.addView(editBtn, ep);

        settingsPanel = new ScrollView(this);
        settingsPanel.setBackground(box(0xEE101010, 14));
        settingsPanel.addView(col);

        int w = Math.min(dp(340), frameW - dp(16));
        int h = Math.min(dp(380), frameH - dp(16));
        int hy = handleLp != null ? handleLp.y + dp(44) : 0;
        settingsLp = winParams(w, h, (frameW - w) / 2, Math.max(0, Math.min(hy, frameH - h)));
        try {
            wm.addView(settingsPanel, settingsLp);
            settingsAttached = true;
        } catch (Exception ignored) { }
    }

    private void closeSettings() {
        if (settingsAttached && settingsPanel != null) {
            try { wm.removeView(settingsPanel); } catch (Exception ignored) { }
        }
        settingsAttached = false;
        settingsPanel = null;
        seekSize = null;
        for (int i = 0; i < 3; i++) modeBtns[i] = null;
        modeHelp = null;
        tvSize = null;
        tvProfile = null;
    }

    private void updateSizeLabel() {
        if (tvSize == null) return;
        tvSize.setText("Tamaño: " + Math.round(model.getScale() * 100) + "%   (máx. sin colisión: "
                + Math.round(model.getMaxScale() * 100) + "%)");
    }

    private void syncSizeSeek(float scale) {
        if (seekSize == null) return;
        syncing = true;
        seekSize.setProgress(Math.round((scale - PadModel.MIN_SCALE) * 100f));
        syncing = false;
    }

    // ---------- Perfiles ----------

    private void reloadNames() {
        pnames.clear();
        String[] b = portrait() ? BUILTIN_PORT : BUILTIN_LAND;
        for (String s : b) pnames.add(s);
        for (String n : ProfileStore.names(this)) {
            JSONObject o = ProfileStore.get(this, n);
            if (o == null) continue;
            boolean p = "port".equals(o.optString("orient", "land"));
            if (p == portrait()) pnames.add(n);
        }
    }

    private void loadProfileForOrientation() {
        reloadNames();
        String active = ProfileStore.getActive(this, portrait());
        int i = pnames.indexOf(active);
        if (i < 0 && "Predeterminado".equals(active)) i = 0;
        pidx = i < 0 ? 0 : i;
        applyProfile();
    }

    private void applyProfile() {
        releasePads();
        String n = pnames.get(pidx);
        if (pidx < 3) {
            model.buildPreset(pidx, portrait());
        } else {
            JSONObject o = ProfileStore.get(this, n);
            boolean ok = false;
            if (o != null) {
                alpha = (float) Math.max(0.15, Math.min(1.0, o.optDouble("alpha", alpha)));
                ok = model.applyJson(o.optJSONObject("pad"));
            }
            if (!ok) model.buildPreset(PadModel.FAMILY_ASYM, portrait());
        }
        ProfileStore.setActive(this, portrait(), n);
        updateProfileLabel();
        updateKbmState();
        if (ready && !editing && !padsHidden) showPads();
    }

    private void updateProfileLabel() {
        if (tvProfile != null && pidx < pnames.size()) tvProfile.setText("Perfil: " + pnames.get(pidx));
    }

    private void cycleProfile(int d) {
        if (pnames.isEmpty()) return;
        pidx = (pidx + d + pnames.size()) % pnames.size();
        applyProfile();
        syncSizeSeek(model.getScale());
        updateSizeLabel();
    }

    private void saveProfile(boolean asNew) {
        String name = (asNew || pidx < 3) ? ProfileStore.nextName(this) : pnames.get(pidx);
        JSONObject o = new JSONObject();
        try {
            o.put("alpha", alpha);
            o.put("orient", portrait() ? "port" : "land");
            o.put("pad", model.toJson());
        } catch (JSONException ignored) { }
        ProfileStore.put(this, name, o);
        reloadNames();
        pidx = pnames.indexOf(name);
        if (pidx < 0) pidx = 0;
        ProfileStore.setActive(this, portrait(), name);
        updateProfileLabel();
        toast("Perfil guardado: " + name);
    }

    private void deleteProfile() {
        if (pidx < 3) { toast("Los perfiles incluidos no se pueden borrar"); return; }
        String name = pnames.get(pidx);
        ProfileStore.delete(this, name);
        reloadNames();
        pidx = 0;
        applyProfile();
        toast("Perfil borrado: " + name);
    }

    // ---------- Edición (ventana completa y temporal) ----------

    private void enterEdit() {
        if (editing || !ready) return;
        editing = true;
        closeSettings();
        setMenuOpen(false);
        releasePads();
        removePads();
        removeHandle();
        buildEditRoot();
    }

    private void exitEdit() {
        exitEditNoRebuild();
        createHandle();
        if (!padsHidden) showPads();
        updateKbmState();
    }

    private void exitEditNoRebuild() {
        if (!editing) return;
        editing = false;
        removeEditRoot();
    }

    private void removeEditRoot() {
        if (editRoot != null) {
            if (editView != null) editView.setEditMode(false);
            try { wm.removeView(editRoot); } catch (Exception ignored) { }
        }
        editRoot = null;
        editView = null;
        editBar = null;
        pickerHolder = null;
    }

    private void buildEditRoot() {
        editRoot = new FrameLayout(this);
        editView = new GamepadView(this);
        editView.bind(model, null, 0, 0);
        editView.setListener(this);
        editView.setGrid(GRID_STEPS[gridIdx]);
        editView.setEditMode(true);
        editView.setEditListener(new GamepadView.EditListener() {
            @Override public void onSelection(Ctl c) {
                if (tvSel != null) tvSel.setText(c == null ? "Toca un control" : "Elegido: " + describe(c));
                boolean tp = c != null && c.type == PadModel.TRACKPAD;
                if (btnSensM != null) btnSensM.setVisibility(tp ? View.VISIBLE : View.GONE);
                if (btnSensP != null) btnSensP.setVisibility(tp ? View.VISIBLE : View.GONE);
            }
            @Override public void onScaleInfo(float scale, float max) { }
            @Override public void onMessage(String msg) { toast(msg); }
        });
        editRoot.addView(editView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        buildEditBar();

        pickerHolder = new FrameLayout(this);
        pickerHolder.setClickable(true);   // que el panel se trague los toques y no muevan controles por debajo
        pickerHolder.setVisibility(View.GONE);
        editRoot.addView(pickerHolder, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        editLp = winParams(frameW, frameH, 0, 0);
        try {
            wm.addView(editRoot, editLp);
        } catch (Exception e) {
            editing = false;
            editRoot = null;
            editView = null;
            toast("No se pudo abrir el editor");
            createHandle();
            if (!padsHidden) showPads();
        }
    }

    private String describe(Ctl c) {
        switch (c.type) {
            case PadModel.STICK: return c.label;
            case PadModel.DPAD: return "Cruceta";
            case PadModel.TRACKPAD: return "Trackpad";
            case PadModel.MSTICK: return "Joystick de teclas";
            case PadModel.KBD: return "Botón de teclado";
            case PadModel.KEY: return "Tecla " + c.label;
            default: return c.glyph != PadModel.GLYPH_NONE ? c.id : c.label;
        }
    }

    private void buildEditBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(4), dp(6), dp(4));

        tvSel = mkLabel("Toca un control");
        tvSel.setPadding(dp(8), 0, dp(8), 0);
        row.addView(tvSel, wrapMargin());

        btnGrid = mkBtn("", new View.OnClickListener() {
            @Override public void onClick(View v) {
                gridIdx = (gridIdx + 1) % GRID_STEPS.length;
                applyGrid();
            }
        });
        row.addView(btnGrid, wrapMargin());
        row.addView(mkBtn("Tamaño −", new View.OnClickListener() {
            @Override public void onClick(View v) { editView.adjustSelected(-0.05f); }
        }), wrapMargin());
        row.addView(mkBtn("Tamaño +", new View.OnClickListener() {
            @Override public void onClick(View v) { editView.adjustSelected(0.05f); }
        }), wrapMargin());
        btnSensM = mkBtn("Sens −", new View.OnClickListener() {
            @Override public void onClick(View v) { editView.adjustSens(-0.2f); }
        });
        btnSensP = mkBtn("Sens +", new View.OnClickListener() {
            @Override public void onClick(View v) { editView.adjustSens(0.2f); }
        });
        btnSensM.setVisibility(View.GONE);
        btnSensP.setVisibility(View.GONE);
        row.addView(btnSensM, wrapMargin());
        row.addView(btnSensP, wrapMargin());
        row.addView(mkBtn("＋ Añadir", new View.OnClickListener() {
            @Override public void onClick(View v) { openAddMenu(); }
        }), wrapMargin());
        row.addView(mkBtn("Asignar", new View.OnClickListener() {
            @Override public void onClick(View v) { assignSelected(); }
        }), wrapMargin());
        btnDel = mkBtn("Eliminar", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (editView.getSelected() == null) { toast("Toca primero un control"); return; }
                if (!delArmed) { delArmed = true; btnDel.setText("¿Seguro?"); return; }
                delArmed = false;
                btnDel.setText("Eliminar");
                editView.removeSelected();
            }
        });
        row.addView(btnDel, wrapMargin());
        btnReset = mkBtn("Restablecer", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!resetArmed) { resetArmed = true; btnReset.setText("¿Seguro?"); return; }
                resetArmed = false;
                btnReset.setText("Restablecer");
                model.buildPreset(model.getFamily(), portrait());
                editView.setSelected(null);
                editView.invalidate();
                toast("Distribución restablecida");
            }
        });
        row.addView(btnReset, wrapMargin());
        row.addView(mkBtn("Guardar", new View.OnClickListener() {
            @Override public void onClick(View v) { saveProfile(false); }
        }), wrapMargin());
        row.addView(mkBtn("✓ Listo", new View.OnClickListener() {
            @Override public void onClick(View v) { exitEdit(); }
        }), wrapMargin());

        editBar = new HorizontalScrollView(this);
        editBar.setBackground(box(0xE6101010, 14));
        editBar.addView(row);

        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        p.bottomMargin = dp(6);
        p.leftMargin = dp(6);
        p.rightMargin = dp(6);
        editRoot.addView(editBar, p);
        applyGrid();
    }

    private void applyGrid() {
        float g = GRID_STEPS[gridIdx];
        if (editView != null) editView.setGrid(g);
        if (btnGrid != null) btnGrid.setText(g <= 0f ? "Rejilla: no" : "Rejilla: " + Math.round(g * 100) + "%");
    }

    // ---------- Edición: añadir / asignar ----------

    private void showPanel(View content, FrameLayout.LayoutParams lp) {
        pickerHolder.removeAllViews();
        pickerHolder.addView(content, lp);
        pickerHolder.setVisibility(View.VISIBLE);
        editBar.setVisibility(View.GONE);
    }

    private void hidePanel() {
        if (pickerHolder == null) return;
        pickerHolder.removeAllViews();
        pickerHolder.setVisibility(View.GONE);
        if (editBar != null) editBar.setVisibility(View.VISIBLE);
    }

    private void openAddMenu() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView t = mkLabel("Añadir al mando");
        t.setTextSize(15);
        col.addView(t);
        String[] names = {"Tecla de teclado o ratón", "Botón de mando", "Joystick WASD", "Joystick flechas",
                "Joystick IJKL", "Joystick personalizado", "Trackpad (ratón)", "Botón de teclado en pantalla"};
        for (int i = 0; i < names.length; i++) {
            final int which = i;
            col.addView(mkBtn(names[i], new View.OnClickListener() {
                @Override public void onClick(View v) { hidePanel(); addByChoice(which); }
            }), weightV());
        }
        col.addView(mkBtn("Cancelar", new View.OnClickListener() {
            @Override public void onClick(View v) { hidePanel(); }
        }), weightV());
        ScrollView sv = new ScrollView(this);
        sv.setBackground(box(0xF0101010, 14));
        sv.addView(col);
        int w = Math.min(frameW - dp(16), dp(360));
        int h = Math.min(frameH - dp(16), dp(400));
        showPanel(sv, new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }

    private LinearLayout.LayoutParams weightV() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(3), dp(4), dp(3), dp(4));
        return p;
    }

    private void addByChoice(int which) {
        switch (which) {
            case 0:
                showKeyPicker("Elige la tecla", KeyDefs.keyboardGroups(), new PickCb() {
                    @Override public void onPick(KeyDefs.Def d) { addKey(d); }
                });
                break;
            case 1:
                showKeyPicker("Elige el botón de mando", KeyDefs.padGroups(), new PickCb() {
                    @Override public void onPick(KeyDefs.Def d) { addPadButton(d); }
                });
                break;
            case 2:
                addMStick(new int[]{KeyDefs.LK_W, KeyDefs.LK_S, KeyDefs.LK_A, KeyDefs.LK_D});
                break;
            case 3:
                addMStick(new int[]{KeyDefs.LK_UP, KeyDefs.LK_DOWN, KeyDefs.LK_LEFT, KeyDefs.LK_RIGHT});
                break;
            case 4:
                addMStick(new int[]{KeyDefs.LK_I, KeyDefs.LK_K, KeyDefs.LK_J, KeyDefs.LK_L});
                break;
            case 5:
                pickDirections(new int[4], 0, new int[0], null);
                break;
            case 6:
                addTrackpad();
                break;
            default:
                addKbdButton();
        }
    }

    private void placeNew(Ctl c) {
        if (model.addControl(c)) {
            editView.setSelected(c);
            editView.invalidate();
        } else {
            toast("No hay espacio libre: reduce el tamaño global o mueve controles");
        }
    }

    private void addKey(KeyDefs.Def d) {
        Ctl c = new Ctl();
        c.id = model.nextId("K");
        c.type = PadModel.KEY;
        c.label = d.label;
        c.key = d.code;
        c.base = 0.055f;
        c.wr = d.label.length() >= 5 ? 2.2f : (d.label.length() >= 3 ? 1.5f : 1f);
        placeNew(c);
    }

    private void addPadButton(KeyDefs.Def d) {
        Ctl c = new Ctl();
        c.id = model.nextId("B");
        c.type = PadModel.BUTTON;
        c.label = d.label;
        c.key = d.code;
        c.round = true;
        c.base = 0.055f;
        placeNew(c);
    }

    private void addMStick(int[] keys) {
        Ctl c = new Ctl();
        c.id = model.nextId("J");
        c.type = PadModel.MSTICK;
        c.label = "Joystick";
        c.keys = keys;
        c.base = 0.13f;
        placeNew(c);
    }

    private void addKbdButton() {
        Ctl c = new Ctl();
        c.id = model.nextId("T");
        c.type = PadModel.KBD;
        c.label = "Teclado";
        c.base = 0.05f;
        c.wr = 2.0f;
        placeNew(c);
    }

    private void addTrackpad() {
        Ctl c = new Ctl();
        c.id = model.nextId("T");
        c.type = PadModel.TRACKPAD;
        c.label = "TRACKPAD";
        c.base = 0.17f;
        c.wr = 1.8f;
        placeNew(c);
    }

    /** Pide, una a una, las 4 teclas de un joystick personalizado. target==null crea uno nuevo; si no, reasigna. */
    private void pickDirections(final int[] keys, final int step, final int[] unused, final Ctl target) {
        final String[] names = {"arriba ↑", "abajo ↓", "izquierda ←", "derecha →"};
        if (step >= 4) {
            if (target != null) { target.keys = keys; editView.invalidate(); }
            else addMStick(keys);
            return;
        }
        showKeyPicker("Tecla para " + names[step], KeyDefs.keyboardGroups(), new PickCb() {
            @Override public void onPick(KeyDefs.Def d) {
                keys[step] = d.code;
                pickDirections(keys, step + 1, unused, target);
            }
        });
    }

    private void assignSelected() {
        final Ctl c = editView.getSelected();
        if (c == null) { toast("Toca primero un control para elegirlo"); return; }
        switch (c.type) {
            case PadModel.KEY:
                showKeyPicker("Nueva tecla", KeyDefs.keyboardGroups(), new PickCb() {
                    @Override public void onPick(KeyDefs.Def d) {
                        c.key = d.code;
                        c.label = d.label;
                        editView.invalidate();
                        editView.setSelected(c);
                    }
                });
                break;
            case PadModel.BUTTON:
                showKeyPicker("Nuevo botón de mando", KeyDefs.padGroups(), new PickCb() {
                    @Override public void onPick(KeyDefs.Def d) {
                        c.key = d.code;
                        c.label = d.label;
                        c.glyph = PadModel.GLYPH_NONE;
                        editView.invalidate();
                        editView.setSelected(c);
                    }
                });
                break;
            case PadModel.MSTICK:
                pickDirections(new int[4], 0, new int[0], c);
                break;
            default:
                toast("Este control no tiene teclas que asignar");
        }
    }

    private void showKeyPicker(String title, KeyDefs.Group[] groups, final PickCb cb) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = mkLabel(title);
        t.setTextSize(15);
        head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(mkBtn("Cancelar", new View.OnClickListener() {
            @Override public void onClick(View v) { hidePanel(); }
        }), wrapMargin());
        col.addView(head);

        final int perRow = frameW > dp(600) ? 8 : 5;
        for (KeyDefs.Group g : groups) {
            TextView gl = mkLabel(g.title);
            gl.setTextColor(0xFF9FD0FF);
            gl.setPadding(dp(4), dp(10), 0, dp(2));
            col.addView(gl);
            LinearLayout row = null;
            int n = g.defs.length;
            for (int i = 0; i < n; i++) {
                if (i % perRow == 0) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    col.addView(row);
                }
                final KeyDefs.Def d = g.defs[i];
                TextView b = mkBtn(d.label, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        hidePanel();
                        cb.onPick(d);
                    }
                });
                b.setPadding(dp(2), dp(8), dp(2), dp(8));
                row.addView(b, weight(1f));
            }
            int rest = (perRow - (n % perRow)) % perRow;
            for (int i = 0; i < rest; i++) {
                View sp = new View(this);
                row.addView(sp, weight(1f));
            }
        }
        ScrollView sv = new ScrollView(this);
        sv.setBackground(box(0xF0101010, 14));
        sv.addView(col);
        int w = Math.min(frameW - dp(16), dp(560));
        int h = Math.min(frameH - dp(16), dp(420));
        showPanel(sv, new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }

    // ---------- Eventos del mando ----------

    @Override
    public void onKey(int code, boolean down) {
        if (vib != null && down) vib.vibrate(8);
        if (gamepad != null) gamepad.sendKey(code, down);
    }

    @Override
    public void onStick(boolean left, float x, float y) {
        if (gamepad != null) gamepad.sendAxis(left, x, y);
    }

    @Override
    public void onRaw(int linuxCode, boolean down) {
        if (vib != null && down) vib.vibrate(8);
        if (gamepad != null) gamepad.sendRaw(linuxCode, down);
    }

    @Override
    public void onMouseMove(float dx, float dy) {
        if (gamepad != null) gamepad.sendMouseMove(dx, dy);
    }

    @Override
    public void onWheel(int ticks) {
        if (gamepad != null) gamepad.sendWheel(ticks);
    }

    @Override
    public void onToggleKeyboard() {
        if (kbAttached) removeKeyboard(); else showKeyboard();
    }

    // ---------- Modo de toque: normal / tocar pantalla / solo botones ----------

    private float effectiveAlpha() {
        return touchMode == TOUCH_THROUGH ? Math.min(alpha, 0.35f) : alpha;
    }

    private String modeLabel() {
        switch (touchMode) {
            case TOUCH_THROUGH: return "Pantalla: tocar pantalla";
            case TOUCH_ONLY_BUTTONS: return "Pantalla: solo botones";
            default: return "Pantalla: normal";
        }
    }

    private String modeHelpText() {
        switch (touchMode) {
            case TOUCH_THROUGH: return "Los toques atraviesan el mando. Los botones se ven pero no responden.";
            case TOUCH_ONLY_BUTTONS: return "Solo responden los botones. El fondo queda bloqueado para no tocar la pantalla sin querer.";
            default: return "Los botones responden y el resto de la pantalla se puede tocar.";
        }
    }

    private void refreshModeUi() {
        for (int i = 0; i < 3; i++) {
            if (modeBtns[i] != null) modeBtns[i].setBackground(box(i == touchMode ? 0xFF4A86FF : 0xFF2E2E2E, 8));
        }
        if (modeHelp != null) modeHelp.setText(modeHelpText());
        if (tvPass != null) tvPass.setText(modeLabel());
        updateHandleIcon();
    }

    private void updateHandleIcon() {
        if (handleMain == null || handleRow == null) return;
        handleMain.setText(touchMode == TOUCH_THROUGH ? "👆" : (touchMode == TOUCH_ONLY_BUTTONS ? "🔒" : "🎮"));
        GradientDrawable g = (GradientDrawable) handleRow.getBackground();
        int stroke = touchMode == TOUCH_THROUGH ? 0xFFFFD54A : (touchMode == TOUCH_ONLY_BUTTONS ? 0xFF6AB7FF : 0x66FFFFFF);
        g.setStroke(dp(touchMode == TOUCH_NORMAL ? 1 : 2), stroke);
    }

    private void setTouchMode(int mode) {
        touchMode = mode;
        ui.edit().putInt("touch_mode", mode).apply();
        releasePads();
        if (ready && !editing && !padsHidden) showPads();   // reconstruye con las banderas y el bloqueador correctos
        refreshModeUi();
        toast(modeLabel());
    }

    /** Modo «solo botones»: ventana transparente a pantalla completa, por debajo del mando, que traga los toques del fondo. */
    private void addBlocker() {
        if (touchMode != TOUCH_ONLY_BUTTONS || blocker != null || frameW <= 0) return;
        View v = new View(this);
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) { return true; }
        });
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(frameW, frameH, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(v, p);
            blocker = v;
            blockerLp = p;
        } catch (Exception ignored) { }
    }

    private void removeBlocker() {
        if (blocker != null) {
            try { wm.removeView(blocker); } catch (Exception ignored) { }
        }
        blocker = null;
        blockerLp = null;
    }

    /** Orden de apilado: bloqueador y mando debajo; encima el teclado, los ajustes y, arriba de todo, el tirador. */
    private void restack() {
        bringKeyboardToFront();
        bringSettingsToFront();
        bringHandleToFront();
    }

    private void bringSettingsToFront() {
        if (!settingsAttached || settingsPanel == null || settingsLp == null) return;
        try {
            wm.removeView(settingsPanel);
            wm.addView(settingsPanel, settingsLp);
        } catch (Exception ignored) { }
    }

    // ---------- Teclado en pantalla y teclado virtual ----------

    /**
     * El teclado/ratón virtuales solo existen mientras el perfil los necesita. Se crean al ENTRAR en el perfil (antes
     * del primer toque) y se destruyen con retraso al salir, para no crear y destruir dispositivos sin parar.
     */
    private void updateKbmState() {
        if (gamepad == null || model == null) return;
        if (model.needsKbm()) {
            gamepad.prepareKbm();
        } else {
            removeKeyboard();
            gamepad.releaseKbmLater(4000);
        }
    }

    private int kbWidth() {
        return Math.min(frameW, Math.max(dp(300), (int) (frameW * kbFrac)));
    }

    private int kbHeight() {
        return Math.min(frameH, dp(32 + 6 * 38 + 10));
    }

    private void clampKb() {
        if (kbLp == null) return;
        kbLp.x = Math.max(0, Math.min(kbLp.x, frameW - kbLp.width));
        kbLp.y = Math.max(0, Math.min(kbLp.y, frameH - kbLp.height));
    }

    private void updateKb() {
        if (kbAttached && kb != null) {
            try { wm.updateViewLayout(kb.view(), kbLp); } catch (Exception ignored) { }
        }
    }

    private void showKeyboard() {
        if (kbAttached || !ready) return;
        if (kbFrac <= 0f) kbFrac = portrait() ? 0.98f : 0.62f;
        kb = new ScreenKeyboard(this, new ScreenKeyboard.Host() {
            @Override public void sendKey(int linuxCode, boolean down) {
                if (gamepad != null) gamepad.sendRaw(linuxCode, down);
            }
            @Override public void close() { removeKeyboard(); }
            @Override public void resize(int dir) {
                kbFrac = Math.max(0.4f, Math.min(1f, kbFrac + dir * 0.08f));
                if (kbLp != null) { kbLp.width = kbWidth(); clampKb(); updateKb(); }
            }
            @Override public void dragStart() {
                if (kbLp != null) { kbStartX = kbLp.x; kbStartY = kbLp.y; }
            }
            @Override public void dragMove(float dx, float dy) {
                if (kbLp == null) return;
                kbLp.x = kbStartX + (int) dx;
                kbLp.y = kbStartY + (int) dy;
                clampKb();
                updateKb();
            }
        });
        int w = kbWidth(), h = kbHeight();
        kbLp = winParams(w, h, (frameW - w) / 2, frameH - h - dp(4));
        try {
            wm.addView(kb.view(), kbLp);
            kbAttached = true;
        } catch (Exception e) {
            kb = null;
            kbLp = null;
            toast("No se pudo abrir el teclado");
        }
        bringHandleToFront();
    }

    private void removeKeyboard() {
        if (kb != null) {
            kb.releaseAll();
            if (kbAttached) {
                try { wm.removeView(kb.view()); } catch (Exception ignored) { }
            }
        }
        kbAttached = false;
        kb = null;
        kbLp = null;
    }

    private void bringKeyboardToFront() {
        if (!kbAttached || kb == null || kbLp == null) return;
        try {
            wm.removeView(kb.view());
            wm.addView(kb.view(), kbLp);
        } catch (Exception ignored) { }
    }

    private void openSystemKeyboardSettings() {
        String[] acts = {"android.settings.HARD_KEYBOARD_SETTINGS", Settings.ACTION_INPUT_METHOD_SETTINGS};
        for (String a : acts) {
            try {
                Intent i = new Intent(a);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception ignored) { }
        }
        toast("No se pudieron abrir los ajustes del teclado");
    }
}
