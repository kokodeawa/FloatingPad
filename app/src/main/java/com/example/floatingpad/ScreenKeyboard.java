package com.example.floatingpad;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Teclado en pantalla propio. Envía teclas por el teclado virtual (uinput), así se puede escribir aunque el
 * sistema oculte su teclado al detectar un teclado "físico". Los modificadores (Shift, Ctrl, Alt, Win) se
 * quedan pulsados hasta la siguiente tecla normal; tócalos otra vez para soltarlos.
 */
public class ScreenKeyboard {

    public interface Host {
        void sendKey(int linuxCode, boolean down);
        void close();
        void resize(int dir);
        void dragStart();
        void dragMove(float dx, float dy);
    }

    private static final Set<Integer> MODS = new HashSet<Integer>();
    static {
        MODS.add(42); MODS.add(54); MODS.add(29); MODS.add(97); MODS.add(56); MODS.add(100); MODS.add(125);
    }

    private static Object[] k(String label, int code, float weight) {
        return new Object[]{label, code, weight};
    }

    private static Object[][][] rows() {
        return new Object[][][]{
                {k("Esc", 1, 1.3f), k("F1", 59, 1f), k("F2", 60, 1f), k("F3", 61, 1f), k("F4", 62, 1f), k("F5", 63, 1f),
                        k("F6", 64, 1f), k("F7", 65, 1f), k("F8", 66, 1f), k("F9", 67, 1f), k("F10", 68, 1f),
                        k("F11", 87, 1f), k("F12", 88, 1f)},
                {k("`", 41, 1f), k("1", 2, 1f), k("2", 3, 1f), k("3", 4, 1f), k("4", 5, 1f), k("5", 6, 1f),
                        k("6", 7, 1f), k("7", 8, 1f), k("8", 9, 1f), k("9", 10, 1f), k("0", 11, 1f), k("-", 12, 1f),
                        k("=", 13, 1f), k("Borrar", 14, 1.9f)},
                {k("Tab", 15, 1.5f), k("Q", 16, 1f), k("W", 17, 1f), k("E", 18, 1f), k("R", 19, 1f), k("T", 20, 1f),
                        k("Y", 21, 1f), k("U", 22, 1f), k("I", 23, 1f), k("O", 24, 1f), k("P", 25, 1f), k("[", 26, 1f),
                        k("]", 27, 1f), k("\\", 43, 1.3f)},
                {k("Mayús", 58, 1.8f), k("A", 30, 1f), k("S", 31, 1f), k("D", 32, 1f), k("F", 33, 1f), k("G", 34, 1f),
                        k("H", 35, 1f), k("J", 36, 1f), k("K", 37, 1f), k("L", 38, 1f), k(";", 39, 1f), k("'", 40, 1f),
                        k("Enter", 28, 2.1f)},
                {k("Shift", 42, 2.3f), k("Z", 44, 1f), k("X", 45, 1f), k("C", 46, 1f), k("V", 47, 1f), k("B", 48, 1f),
                        k("N", 49, 1f), k("M", 50, 1f), k(",", 51, 1f), k(".", 52, 1f), k("/", 53, 1f),
                        k("↑", 103, 1.1f)},
                {k("Ctrl", 29, 1.4f), k("Win", 125, 1.2f), k("Alt", 56, 1.2f), k("Espacio", 57, 5.5f),
                        k("Alt", 100, 1.2f), k("←", 105, 1f), k("↓", 108, 1f), k("→", 106, 1f), k("Ctrl", 97, 1.4f)}
        };
    }

    private final Context ctx;
    private final Host host;
    private final float density;
    private final LinearLayout root;
    private final Map<Integer, TextView> views = new HashMap<Integer, TextView>();
    private final Set<Integer> latched = new HashSet<Integer>();

    public ScreenKeyboard(Context c, Host h) {
        ctx = c;
        host = h;
        density = c.getResources().getDisplayMetrics().density;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(4), dp(3), dp(4), dp(3));
        root.setBackground(box(0xEE141414, 10));
        root.addView(buildHeader(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));
        for (Object[][] row : rows()) {
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (Object[] kd : row) r.addView(buildKey((String) kd[0], (Integer) kd[1]), keyParams((Float) kd[2]));
            root.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
    }

    public View view() { return root; }

    /** Suelta los modificadores que hayan quedado pulsados. */
    public void releaseAll() {
        for (Integer code : new ArrayList<Integer>(latched)) host.sendKey(code, false);
        latched.clear();
        for (TextView v : views.values()) setActive(v, false);
    }

    private int dp(float v) { return (int) (v * density + 0.5f); }

    private GradientDrawable box(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams keyParams(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        p.setMargins(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f));
        return p;
    }

    private TextView headerBtn(String text, View.OnClickListener l) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setOnClickListener(l);
        return t;
    }

    private View buildHeader() {
        LinearLayout h = new LinearLayout(ctx);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView drag = new TextView(ctx);
        drag.setText("≡  Teclado");
        drag.setTextColor(0xFFCFE3FF);
        drag.setTextSize(13);
        drag.setGravity(Gravity.CENTER_VERTICAL);
        drag.setPadding(dp(8), 0, 0, 0);
        drag.setOnTouchListener(new View.OnTouchListener() {
            private float sx, sy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        host.dragStart();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        host.dragMove(e.getRawX() - sx, e.getRawY() - sy);
                        return true;
                }
                return false;
            }
        });
        h.addView(drag, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        h.addView(headerBtn("−", new View.OnClickListener() {
            @Override public void onClick(View v) { host.resize(-1); }
        }), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        h.addView(headerBtn("+", new View.OnClickListener() {
            @Override public void onClick(View v) { host.resize(1); }
        }), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        h.addView(headerBtn("✕", new View.OnClickListener() {
            @Override public void onClick(View v) { host.close(); }
        }), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        return h;
    }

    private void setActive(TextView v, boolean on) {
        v.setBackground(box(on ? 0xFF4A86FF : 0xFF3A3A3A, 6));
    }

    private View buildKey(String label, final int code) {
        final TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(11);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(0, 0, 0, 0);
        setActive(t, false);
        if (!views.containsKey(code)) views.put(code, t);
        final boolean mod = MODS.contains(code);
        t.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return true;   // clics del ratón virtual
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (mod) {
                            if (latched.remove(code)) { host.sendKey(code, false); setActive(t, false); }
                            else { latched.add(code); host.sendKey(code, true); setActive(t, true); }
                        } else {
                            host.sendKey(code, true);
                            setActive(t, true);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!mod) {
                            host.sendKey(code, false);
                            setActive(t, false);
                            releaseAll();           // los modificadores valen solo para la tecla siguiente
                        }
                        return true;
                }
                return true;
            }
        });
        return t;
    }
}
