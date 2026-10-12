package com.example.floatingpad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

import com.example.floatingpad.PadModel.Ctl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dibuja y atiende una parte del mando. En juego, cada instancia es una ventana pequeña que solo cubre un
 * grupo de controles (el resto de la pantalla queda libre). En edición, una sola instancia cubre todo el marco.
 */
public class GamepadView extends View {

    public interface Listener {
        void onKey(int keyCode, boolean down);                 // botones de mando (códigos Android)
        void onStick(boolean left, float x, float y);          // sticks analógicos, -1..1
        void onRaw(int linuxCode, boolean down);               // teclado y botones de ratón (códigos Linux)
        void onMouseMove(float dx, float dy);                  // trackpad (píxeles ya multiplicados por la sensibilidad)
        void onWheel(int ticks);                               // trackpad con dos dedos
        void onToggleKeyboard();                               // botón de teclado en pantalla
    }

    public interface EditListener {
        void onSelection(Ctl c);
        void onScaleInfo(float scale, float maxScale);
        void onMessage(String msg);
    }

    private static final int[] DPAD_KEYS = {PadModel.K_UP, PadModel.K_DOWN, PadModel.K_LEFT, PadModel.K_RIGHT};

    private static class Tp {
        int id1 = -1, id2 = -1;
        float x1, y1, x2, y2, travel, accWheel;
        long t0, lastTapUp, tm1, tm2;
        boolean two, dragging, rightDone;
    }

    private PadModel model;
    private List<Ctl> mine;          // null = todos (modo edición)
    private int offX, offY;
    private final SparseArray<Ctl> owners = new SparseArray<Ctl>();
    private final Map<Ctl, Tp> tps = new HashMap<Ctl, Tp>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint();
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();
    private Listener listener;
    private EditListener editListener;
    private float density = 1f;

    private boolean edit;
    private float grid = 0.03f;
    private Ctl selected;
    private int editPointer = -1;
    private float grabDx, grabDy;

    public GamepadView(Context c) { super(c); init(); }
    public GamepadView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f);
        line.setColor(0xCCFFFFFF);
        txt.setColor(0xFFFFFFFF);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setFakeBoldText(true);
        gridPaint.setColor(0x33FFFFFF);
        gridPaint.setStrokeWidth(1f);
        mark.setStyle(Paint.Style.STROKE);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setListener(Listener l) { listener = l; }
    public void setEditListener(EditListener l) { editListener = l; }

    /** Asocia la vista al modelo: subset = controles que atiende (null = todos); (ox,oy) = origen de la ventana. */
    public void bind(PadModel m, List<Ctl> subset, int ox, int oy) {
        model = m;
        mine = subset == null ? null : new ArrayList<Ctl>(subset);
        offX = ox;
        offY = oy;
        invalidate();
    }

    private List<Ctl> list() { return mine != null ? mine : model.controls(); }

    // ---------- Edición ----------

    public void setEditMode(boolean on) {
        releaseAll();
        edit = on;
        if (!on) selected = null;
        invalidate();
        notifySelection();
    }

    public boolean isEditMode() { return edit; }
    public void setGrid(float step) { grid = step; invalidate(); }
    public Ctl getSelected() { return selected; }
    public void setSelected(Ctl c) { selected = c; invalidate(); notifySelection(); }

    public void adjustSelected(float dk) {
        if (selected == null) { msg("Toca primero un control para elegirlo"); return; }
        if (!model.adjust(selected, dk)) msg("No cabe: chocaría con otro control o saldría de la pantalla");
        invalidate();
        notifyScale();
    }

    public void adjustSens(float d) {
        if (selected == null || selected.type != PadModel.TRACKPAD) return;
        model.adjustSens(selected, d);
        msg("Sensibilidad del trackpad: " + Math.round(selected.sens * 100) + "%");
    }

    public void removeSelected() {
        if (selected == null) { msg("Toca primero un control para elegirlo"); return; }
        model.remove(selected);
        selected = null;
        invalidate();
        notifySelection();
        notifyScale();
    }

    public void notifyScale() {
        if (editListener != null && model != null) editListener.onScaleInfo(model.getScale(), model.getMaxScale());
    }

    private void notifySelection() { if (editListener != null) editListener.onSelection(selected); }
    private void msg(String m) { if (editListener != null) editListener.onMessage(m); }

    private boolean editTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                editPointer = e.getPointerId(0);
                float x = e.getX(), y = e.getY();
                selected = hit(x, y, true);
                if (selected != null) { grabDx = selected.cx - x; grabDy = selected.cy - y; }
                notifySelection();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int i = e.findPointerIndex(editPointer);
                if (i >= 0 && selected != null) model.moveTo(selected, e.getX(i) + grabDx, e.getY(i) + grabDy, grid);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                editPointer = -1;
                notifyScale();
                break;
        }
        invalidate();
        return true;
    }

    // ---------- Dibujo ----------

    @Override
    protected void onDraw(Canvas cv) {
        if (model == null) return;
        if (edit) drawGrid(cv);
        cv.save();
        cv.translate(-offX, -offY);
        for (Ctl c : list()) {
            switch (c.type) {
                case PadModel.BUTTON: drawButton(cv, c); break;
                case PadModel.KEY: drawButton(cv, c); break;
                case PadModel.KBD: drawButton(cv, c); break;
                case PadModel.STICK: drawStick(cv, c, false); break;
                case PadModel.MSTICK: drawStick(cv, c, true); break;
                case PadModel.DPAD: drawDpad(cv, c); break;
                default: drawTrackpad(cv, c); break;
            }
            if (edit) drawEditMark(cv, c);
        }
        cv.restore();
    }

    private void drawGrid(Canvas cv) {
        cv.drawColor(0x55000000);
        if (grid <= 0f || model == null) return;
        float st = grid * Math.min(model.getWidth(), model.getHeight());
        if (st < 6f) return;
        for (float x = 0; x <= getWidth(); x += st) cv.drawLine(x, 0, x, getHeight(), gridPaint);
        for (float y = 0; y <= getHeight(); y += st) cv.drawLine(0, y, getWidth(), y, gridPaint);
    }

    private void drawEditMark(Canvas cv, Ctl c) {
        boolean sel = c == selected;
        mark.setStrokeWidth(sel ? 5f : 2f);
        mark.setColor(sel ? 0xFFFFE000 : 0xAAFFFFFF);
        mark.setPathEffect(sel ? null : new DashPathEffect(new float[]{10f, 8f}, 0f));
        if (c.isCircle()) cv.drawCircle(c.cx, c.cy, c.hw, mark);
        else {
            rf.set(c.cx - c.hw, c.cy - c.hh, c.cx + c.hw, c.cy + c.hh);
            cv.drawRoundRect(rf, c.hh * 0.5f, c.hh * 0.5f, mark);
        }
        if (c.type == PadModel.STICK || c.type == PadModel.DPAD) {
            txt.setTextSize(Math.max(18f, c.hw * 0.3f));
            cv.drawText(c.label, c.cx, c.cy + txt.getTextSize() * 0.35f, txt);
        }
    }

    private int colorFor(Ctl c) {
        if (c.type == PadModel.KEY || c.type == PadModel.KBD) return 0x88505A66;
        if (c.glyph != PadModel.GLYPH_NONE) return 0x88303038;
        switch (c.key) {
            case PadModel.K_A: return 0xAA6DC13A;
            case PadModel.K_B: return 0xAAE5483C;
            case PadModel.K_X: return 0xAA3F7DE0;
            case PadModel.K_Y: return 0xAAF2C230;
            default: return 0x88606060;
        }
    }

    private void drawLabel(Canvas cv, String s, float cx, float cy, float maxW, float maxSize) {
        if (s == null || s.length() == 0) return;
        float ts = maxSize;
        txt.setTextSize(ts);
        float tw = txt.measureText(s);
        if (tw > maxW && tw > 0) { ts = ts * maxW / tw; txt.setTextSize(ts); }
        cv.drawText(s, cx, cy + ts * 0.35f, txt);
    }

    private void drawButton(Canvas cv, Ctl c) {
        fill.setColor(colorFor(c));
        if (c.round) cv.drawCircle(c.cx, c.cy, c.hw, fill);
        else {
            rf.set(c.cx - c.hw, c.cy - c.hh, c.cx + c.hw, c.cy + c.hh);
            cv.drawRoundRect(rf, c.hh * 0.5f, c.hh * 0.5f, fill);
        }
        if (c.pressed) {
            fill.setColor(0x88FFFFFF);
            if (c.round) cv.drawCircle(c.cx, c.cy, c.hw, fill);
            else cv.drawRoundRect(rf, c.hh * 0.5f, c.hh * 0.5f, fill);
        }
        if (c.round) cv.drawCircle(c.cx, c.cy, c.hw, line);
        else cv.drawRoundRect(rf, c.hh * 0.5f, c.hh * 0.5f, line);
        if (c.glyph != PadModel.GLYPH_NONE) { drawGlyph(cv, c); return; }
        float maxSize = c.round ? c.hw * 0.95f : Math.min(c.hh * 1.2f, c.hw * 0.7f);
        drawLabel(cv, c.label, c.cx, c.cy, c.hw * 1.7f, maxSize);
    }

    /** Símbolos geométricos dibujados como vectores (no dependen de la fuente del sistema). */
    private void drawGlyph(Canvas cv, Ctl c) {
        float r = c.hw * 0.46f;
        glyphPaint.setStrokeWidth(Math.max(3f, c.hw * 0.13f));
        switch (c.glyph) {
            case PadModel.GLYPH_CROSS:
                glyphPaint.setColor(0xFF7CA6E0);
                cv.drawLine(c.cx - r, c.cy - r, c.cx + r, c.cy + r, glyphPaint);
                cv.drawLine(c.cx - r, c.cy + r, c.cx + r, c.cy - r, glyphPaint);
                break;
            case PadModel.GLYPH_CIRCLE:
                glyphPaint.setColor(0xFFE8606A);
                cv.drawCircle(c.cx, c.cy, r, glyphPaint);
                break;
            case PadModel.GLYPH_SQUARE:
                glyphPaint.setColor(0xFFE59AC8);
                cv.drawRect(c.cx - r * 0.9f, c.cy - r * 0.9f, c.cx + r * 0.9f, c.cy + r * 0.9f, glyphPaint);
                break;
            default:
                glyphPaint.setColor(0xFF5CC79A);
                path.reset();
                path.moveTo(c.cx, c.cy - r);
                path.lineTo(c.cx + r * 1.05f, c.cy + r * 0.8f);
                path.lineTo(c.cx - r * 1.05f, c.cy + r * 0.8f);
                path.close();
                cv.drawPath(path, glyphPaint);
                break;
        }
    }

    private float travel(Ctl c) { return c.hw * 0.5f; }

    private void drawStick(Canvas cv, Ctl c, boolean mapped) {
        fill.setColor(mapped ? 0x30FFFFFF : 0x26FFFFFF);
        cv.drawCircle(c.cx, c.cy, c.hw, fill);
        cv.drawCircle(c.cx, c.cy, c.hw, line);
        if (mapped) {
            float d = c.hw * 0.74f, ts = Math.max(10f, c.hw * 0.24f);
            txt.setTextSize(ts);
            txt.setColor(0xCCFFFFFF);
            cv.drawText(KeyDefs.labelFor(c.keys[0]), c.cx, c.cy - d + ts * 0.35f, txt);
            cv.drawText(KeyDefs.labelFor(c.keys[1]), c.cx, c.cy + d + ts * 0.35f, txt);
            cv.drawText(KeyDefs.labelFor(c.keys[2]), c.cx - d, c.cy + ts * 0.35f, txt);
            cv.drawText(KeyDefs.labelFor(c.keys[3]), c.cx + d, c.cy + ts * 0.35f, txt);
            txt.setColor(0xFFFFFFFF);
        }
        fill.setColor(c.pressed ? 0xEEFFFFFF : 0xAACCCCCC);
        cv.drawCircle(c.cx + c.sx * travel(c), c.cy + c.sy * travel(c), c.hw * 0.4f, fill);
    }

    private void drawDpad(Canvas cv, Ctl c) {
        float t = c.hw * 0.33f;
        fill.setColor(0x77808080);
        rf.set(c.cx - c.hw, c.cy - t, c.cx + c.hw, c.cy + t);
        cv.drawRoundRect(rf, t * 0.6f, t * 0.6f, fill);
        rf.set(c.cx - t, c.cy - c.hw, c.cx + t, c.cy + c.hw);
        cv.drawRoundRect(rf, t * 0.6f, t * 0.6f, fill);
        fill.setColor(0xBBFFFFFF);
        float a = c.hw * 0.66f, rr = t * 0.6f;
        if ((c.mask & 1) != 0) cv.drawCircle(c.cx, c.cy - a, rr, fill);
        if ((c.mask & 2) != 0) cv.drawCircle(c.cx, c.cy + a, rr, fill);
        if ((c.mask & 4) != 0) cv.drawCircle(c.cx - a, c.cy, rr, fill);
        if ((c.mask & 8) != 0) cv.drawCircle(c.cx + a, c.cy, rr, fill);
    }

    private void drawTrackpad(Canvas cv, Ctl c) {
        rf.set(c.cx - c.hw, c.cy - c.hh, c.cx + c.hw, c.cy + c.hh);
        fill.setColor(c.pressed ? 0x44FFFFFF : 0x22FFFFFF);
        cv.drawRoundRect(rf, c.hh * 0.12f, c.hh * 0.12f, fill);
        cv.drawRoundRect(rf, c.hh * 0.12f, c.hh * 0.12f, line);
        txt.setColor(0x66FFFFFF);
        drawLabel(cv, "TRACKPAD", c.cx, c.cy, c.hw * 1.5f, c.hh * 0.28f);
        txt.setColor(0xFFFFFFFF);
    }

    // ---------- Multitáctil ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (model == null) return false;
        if (edit) return editTouch(e);
        // Los clics del ratón virtual caen sobre las ventanas del propio mando: se ignoran para no pulsarlas.
        if (e.getPointerCount() > 0 && e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return false;
        try {
            return handleTouch(e);
        } catch (RuntimeException ex) {
            Log.e("FloatingPad", "Error procesando el toque", ex);
            releaseAll();
            return true;
        }
    }

    private boolean handleTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                down(e.getPointerId(i), e.getX(i) + offX, e.getY(i) + offY);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    move(e.getPointerId(i), e.getX(i) + offX, e.getY(i) + offY, e.getEventTime());
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                release(e.getPointerId(e.getActionIndex()));
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
        }
        invalidate();
        return true;
    }

    /** Suelta todo lo que esté pulsado (al ocultar, editar, rotar o cancelar). */
    public void releaseAll() {
        for (int i = owners.size() - 1; i >= 0; i--) release(owners.keyAt(i));
        for (Map.Entry<Ctl, Tp> en : tps.entrySet()) {
            Tp tp = en.getValue();
            if (tp.dragging && listener != null) listener.onRaw(KeyDefs.BTN_LEFT, false);
            tp.dragging = false;
            tp.id1 = tp.id2 = -1;
            tp.two = false;
            en.getKey().pressed = false;
        }
        tps.clear();
        owners.clear();
        invalidate();
    }

    private boolean owned(Ctl c) {
        for (int i = 0; i < owners.size(); i++) if (owners.valueAt(i) == c) return true;
        return false;
    }

    /** Distancia normalizada al control (<= 1 significa dentro del área táctil), o -1 si no acierta. */
    private float score(Ctl c, float x, float y) {
        float dx = x - c.cx, dy = y - c.cy;
        if (!c.isCircle()) {
            float nx = dx / (c.hw * 1.1f), ny = dy / (c.hh * 1.2f);
            float s = nx * nx + ny * ny;
            return (Math.abs(nx) <= 1f && Math.abs(ny) <= 1f) ? s : -1f;
        }
        float k = (c.type == PadModel.BUTTON || c.type == PadModel.KEY || c.type == PadModel.KBD) ? 1.25f : 1.2f;
        float rr = c.hw * k;
        float s = (dx * dx + dy * dy) / (rr * rr);
        return s <= 1f ? s : -1f;
    }

    private Ctl hit(float x, float y, boolean ignoreOwned) {
        Ctl best = null;
        float bestScore = Float.MAX_VALUE;
        for (Ctl c : list()) {
            if (!ignoreOwned && owned(c) && c.type != PadModel.TRACKPAD) continue;
            float s = score(c, x, y);
            if (s >= 0f && s < bestScore) { bestScore = s; best = c; }
        }
        return best;
    }

    private void fire(Ctl c, boolean down) {
        if (listener == null) return;
        if (c.type == PadModel.BUTTON) listener.onKey(c.key, down);
        else listener.onRaw(c.key, down);
    }

    private void down(int id, float x, float y) {
        Ctl best = hit(x, y, false);
        if (best == null) return;
        if (best.type == PadModel.TRACKPAD) { tpDown(best, id, x, y); return; }
        owners.put(id, best);
        switch (best.type) {
            case PadModel.BUTTON:
            case PadModel.KEY:
                best.pressed = true;
                fire(best, true);
                break;
            case PadModel.KBD:
                best.pressed = true;
                if (listener != null) listener.onToggleKeyboard();
                break;
            case PadModel.STICK:
            case PadModel.MSTICK:
                best.pressed = true;
                moveStick(best, x, y);
                break;
            default:
                setMask(best, maskFor(best, x, y));
        }
    }

    private void move(int id, float x, float y, long t) {
        Ctl c = owners.get(id);
        if (c == null) return;
        if (c.type == PadModel.STICK || c.type == PadModel.MSTICK) moveStick(c, x, y);
        else if (c.type == PadModel.DPAD) setMask(c, maskFor(c, x, y));
        else if (c.type == PadModel.TRACKPAD) tpMove(c, id, x, y, t);
    }

    private void release(int id) {
        Ctl c = owners.get(id);
        if (c == null) return;
        owners.remove(id);
        switch (c.type) {
            case PadModel.BUTTON:
            case PadModel.KEY:
                c.pressed = false;
                fire(c, false);
                break;
            case PadModel.KBD:
                c.pressed = false;
                break;
            case PadModel.STICK:
                c.pressed = false;
                c.sx = c.sy = 0;
                if (listener != null) listener.onStick(c.left, 0, 0);
                break;
            case PadModel.MSTICK:
                c.pressed = false;
                c.sx = c.sy = 0;
                setMask(c, 0);
                break;
            case PadModel.TRACKPAD:
                tpUp(c, id);
                break;
            default:
                setMask(c, 0);
        }
    }

    private void moveStick(Ctl c, float x, float y) {
        float dx = (x - c.cx) / travel(c), dy = (y - c.cy) / travel(c);
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1f) { dx /= len; dy /= len; len = 1f; }
        c.sx = dx;
        c.sy = dy;
        if (c.type == PadModel.STICK) {
            if (listener != null) listener.onStick(c.left, dx, dy);
        } else {
            setMask(c, len < 0.35f ? 0 : bitsFor(dx, dy, len));
        }
    }

    private int bitsFor(float dx, float dy, float len) {
        int m = 0;
        if (dy < -0.38f * len) m |= 1;
        if (dy > 0.38f * len) m |= 2;
        if (dx < -0.38f * len) m |= 4;
        if (dx > 0.38f * len) m |= 8;
        return m;
    }

    private int maskFor(Ctl c, float x, float y) {
        float dx = x - c.cx, dy = y - c.cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < c.hw * 0.2f) return 0;
        return bitsFor(dx, dy, len);
    }

    private void setMask(Ctl c, int m) {
        int old = c.mask;
        if (old == m) return;
        c.mask = m;
        for (int b = 0; b < 4; b++) {
            int bit = 1 << b;
            boolean o = (old & bit) != 0, n = (m & bit) != 0;
            if (o != n && listener != null) {
                if (c.type == PadModel.DPAD) listener.onKey(DPAD_KEYS[b], n);
                else listener.onRaw(c.keys[b], n);
            }
        }
    }

    // ---------- Trackpad ----------

    private void tpDown(Ctl c, int id, float x, float y) {
        Tp tp = tps.get(c);
        if (tp == null) { tp = new Tp(); tps.put(c, tp); }
        long now = SystemClock.uptimeMillis();
        if (tp.id1 < 0) {
            tp.id1 = id;
            tp.x1 = x;
            tp.y1 = y;
            tp.t0 = now;
            tp.tm1 = now;
            tp.travel = 0;
            tp.two = false;
            tp.rightDone = false;
            owners.put(id, c);
            if (tp.lastTapUp > 0 && now - tp.lastTapUp < 280 && listener != null) {
                tp.dragging = true;                       // toque + arrastre = arrastrar con clic izquierdo
                listener.onRaw(KeyDefs.BTN_LEFT, true);
            }
        } else if (tp.id2 < 0) {
            tp.id2 = id;
            tp.x2 = x;
            tp.y2 = y;
            tp.tm2 = now;
            tp.two = true;
            tp.accWheel = 0;
            owners.put(id, c);
            if (tp.dragging && listener != null) { listener.onRaw(KeyDefs.BTN_LEFT, false); tp.dragging = false; }
        }
        c.pressed = true;
    }

    /**
     * Mueve el ratón con aceleración, como un touchpad real: los gestos lentos son precisos y los rápidos
     * cruzan la pantalla entera. La ganancia depende de la sensibilidad del control y de la velocidad (dp/ms).
     */
    private void tpMove(Ctl c, int id, float x, float y, long t) {
        Tp tp = tps.get(c);
        if (tp == null || listener == null) return;
        if (id == tp.id1) {
            float dx = x - tp.x1, dy = y - tp.y1;
            long dt = Math.max(1L, t - tp.tm1);
            tp.x1 = x;
            tp.y1 = y;
            tp.tm1 = t;
            tp.travel += Math.abs(dx) + Math.abs(dy);
            if (tp.two) {
                addScroll(tp, dy * 0.5f);
            } else if (dx != 0f || dy != 0f) {
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float v = dist / dt / density;                       // dp por milisegundo
                float gain = c.sens * (0.8f + 1.6f * Math.min(v, 3.5f));
                listener.onMouseMove(dx * gain, dy * gain);
            }
        } else if (id == tp.id2) {
            float dy = y - tp.y2;
            tp.x2 = x;
            tp.y2 = y;
            tp.tm2 = t;
            tp.travel += Math.abs(dy);
            addScroll(tp, dy * 0.5f);
        }
    }

    private void addScroll(Tp tp, float dy) {
        tp.accWheel += dy;
        float step = 26f * density;
        while (Math.abs(tp.accWheel) >= step) {
            int dir = tp.accWheel < 0 ? -1 : 1;     // dedo hacia abajo = desplazar hacia arriba (+1)
            listener.onWheel(dir);
            tp.accWheel -= dir * step;
        }
    }

    private void tpUp(Ctl c, int id) {
        Tp tp = tps.get(c);
        if (tp == null) return;
        long now = SystemClock.uptimeMillis();
        float slop = 10f * density;
        if (tp.two) {
            if (!tp.rightDone && tp.travel < slop * 2 && now - tp.t0 < 320 && listener != null) {
                listener.onRaw(KeyDefs.BTN_RIGHT, true);   // toque con dos dedos = clic derecho
                listener.onRaw(KeyDefs.BTN_RIGHT, false);
            }
            tp.rightDone = true;
        } else if (id == tp.id1) {
            if (tp.dragging) {
                if (listener != null) listener.onRaw(KeyDefs.BTN_LEFT, false);
                tp.dragging = false;
                tp.lastTapUp = 0;
            } else if (tp.travel < slop && now - tp.t0 < 260 && listener != null) {
                listener.onRaw(KeyDefs.BTN_LEFT, true);    // toque = clic izquierdo
                listener.onRaw(KeyDefs.BTN_LEFT, false);
                tp.lastTapUp = now;
            } else {
                tp.lastTapUp = 0;
            }
        }
        if (id == tp.id1) tp.id1 = -1;
        if (id == tp.id2) tp.id2 = -1;
        if (tp.id1 < 0 && tp.id2 < 0) {
            tp.two = false;
            tp.rightDone = false;
            c.pressed = false;
        }
    }
}
