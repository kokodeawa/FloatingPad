package com.example.floatingpad;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Guarda los perfiles de distribución del mando en SharedPreferences (JSON). */
public class ProfileStore {
    private static final String PREFS = "pad_profiles";
    private static final String KEY_ALL = "all";
    private static final String KEY_ACTIVE = "active";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject all(Context c) {
        try {
            return new JSONObject(sp(c).getString(KEY_ALL, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public static List<String> names(Context c) {
        List<String> out = new ArrayList<String>();
        Iterator<?> it = all(c).keys();
        while (it.hasNext()) out.add(String.valueOf(it.next()));
        Collections.sort(out);
        return out;
    }

    public static JSONObject get(Context c, String name) {
        return all(c).optJSONObject(name);
    }

    public static void put(Context c, String name, JSONObject profile) {
        JSONObject a = all(c);
        try { a.put(name, profile); } catch (JSONException ignored) { }
        sp(c).edit().putString(KEY_ALL, a.toString()).apply();
    }

    public static void delete(Context c, String name) {
        JSONObject a = all(c);
        a.remove(name);
        sp(c).edit().putString(KEY_ALL, a.toString()).apply();
    }

    public static String nextName(Context c) {
        List<String> n = names(c);
        int i = 1;
        while (n.contains("Perfil " + i)) i++;
        return "Perfil " + i;
    }

    public static String getActive(Context c) { return sp(c).getString(KEY_ACTIVE, null); }

    public static void setActive(Context c, String name) { sp(c).edit().putString(KEY_ACTIVE, name).apply(); }

    /** Perfil activo por orientación (horizontal / vertical); el antiguo valor único sirve para horizontal. */
    public static String getActive(Context c, boolean portrait) {
        String v = sp(c).getString(portrait ? "active_port" : "active_land", null);
        return v != null ? v : (portrait ? null : getActive(c));
    }

    public static void setActive(Context c, boolean portrait, String name) {
        sp(c).edit().putString(portrait ? "active_port" : "active_land", name).apply();
    }
}
