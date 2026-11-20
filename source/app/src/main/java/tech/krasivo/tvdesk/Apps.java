package tech.krasivo.tvdesk;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.text.Collator;
import java.util.*;

final class Apps {
    static final class Entry {
        final String id, name; final ComponentName component; final boolean system;
        Entry(ApplicationInfo a, ComponentName activity, PackageManager pm) {
            id = a.packageName;
            name = String.valueOf(a.loadLabel(pm));
            component = activity;
            system = (a.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        }
    }
    final Activity host;
    final SharedPreferences prefs;
    final List<Entry> list = new ArrayList<>();
    String title, city;
    Set<String> pins, hidden;
    Apps(Activity host) {
        this.host = host;
        prefs = host.getSharedPreferences("home", Context.MODE_PRIVATE);
        load();
    }
    void load() {
        title = prefs.getString("title", "Мои приложения");
        city = prefs.getString("city", "");
        pins = new HashSet<>(prefs.getStringSet("pins", Collections.emptySet()));
        hidden = new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
    }
    void scan(boolean includeBackgroundApps) {
        list.clear();
        PackageManager pm = host.getPackageManager();
        Set<String> seen = new HashSet<>(); seen.add(host.getPackageName());
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo r : pm.queryIntentActivities(intent, 0)) {
                if (r.activityInfo.exported && seen.add(r.activityInfo.packageName)) 
                    list.add(new Entry(r.activityInfo.applicationInfo, new ComponentName(r.activityInfo.packageName, r.activityInfo.name), pm));
            }
        }
        if (includeBackgroundApps) {
            for (ApplicationInfo a : pm.getInstalledApplications(0)) {
                if (seen.add(a.packageName)) list.add(new Entry(a, null, pm));
            }
        }
        Collator coll = Collator.getInstance();
        list.sort((a,b) -> { int n = coll.compare(a.name, b.name); return n == 0 ? a.id.compareTo(b.id) : n; });
    }
    void inspect(String id) {
        if (host.getPackageName().equals(id)) return;
        PackageManager pm = host.getPackageManager();
        try { list.add(new Entry(pm.getApplicationInfo(id, 0), null, pm)); }
        catch (PackageManager.NameNotFoundException ignored) { }
    }
    Entry byId(String id) {
        for (Entry e : list) if (e.id.equals(id)) return e;
        throw new IllegalArgumentException("Список приложений изменился. Обновите панель.");
    }
    boolean kept(Entry e) {
        String key = (e.id+" "+e.name).toLowerCase(Locale.ROOT);
        return pins.contains(e.id) || key.contains("kinopoisk") || key.contains("кинопоиск");
    }
    private JSONObject environment() throws JSONException {
        Locale locale = host.getResources().getConfiguration().getLocales().get(0);
        JSONObject env = new JSONObject().put("language",locale.toLanguageTag())
            .put("localeRegion",locale.getCountry()).put("zone",TimeZone.getDefault().getID())
            .put("clock",new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",locale).format(new Date()));
        android.content.ContentResolver r = host.getContentResolver();
        for (String name : new String[]{"auto_time","auto_time_zone"}) {
            try {
                int flag = android.provider.Settings.Global.getInt(r,name,-1);
                if (flag==0 || flag==1) env.put(name,flag==1?"Вкл":"Выкл");
            } catch (SecurityException ignored) { }
        }
        if (Build.VERSION.SDK_INT>=28) {
            android.location.LocationManager lm=(android.location.LocationManager) host.getSystemService(Context.LOCATION_SERVICE);
            try { if(lm!=null) env.put("locationService",lm.isLocationEnabled()?"Вкл":"Выкл"); }
            catch(SecurityException ignored) { }
        }
        return env;
    }
    JSONObject state() throws JSONException {
        JSONArray arr = new JSONArray();
        for (Entry e : list) arr.put(new JSONObject().put("id",e.id).put("name",e.name)
            .put("launchable",e.component != null).put("system",e.system).put("keep",kept(e)).put("pin",e.component != null && pins.contains(e.id)).put("hide",e.component != null && hidden.contains(e.id)));
        StatFs fs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        ActivityManager.MemoryInfo ram = new ActivityManager.MemoryInfo();
        ((ActivityManager)host.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(ram);
        return new JSONObject().put("city",city).put("environment",environment()).put("apps",arr).put("title",title).put("model",Build.MANUFACTURER+" "+Build.MODEL)
            .put("android",Build.VERSION.RELEASE).put("freeDisk",fs.getAvailableBytes()).put("totalDisk",fs.getTotalBytes())
            .put("freeRam",ram.availMem).put("totalRam",ram.totalMem);
    }
    private Set<String> decode(JSONArray value) throws JSONException {
        if (value.length() > list.size()) throw new IllegalArgumentException("Проверьте список приложений");
        Set<String> out = new HashSet<>();
        for (int i = 0; i < value.length(); i++) {
            Entry e = byId(value.getString(i));
            if (e.component == null) throw new IllegalArgumentException("Ярлык приложения недоступен. Обновите список.");
            out.add(e.id);
        }
        return out;
    }
    void apply(JSONObject request) throws JSONException {
        String name = request.getString("title").trim();
        if (name.isEmpty() || name.length()>48 || name.matches("(?s).*\\p{Cntrl}.*")) throw new IllegalArgumentException("Введите название длиной до 48 символов");
        Set<String> p = decode(request.getJSONArray("pins")), h = decode(request.getJSONArray("hidden"));
        if (!Collections.disjoint(p,h)) throw new IllegalArgumentException("Приложение одновременно скрыто и закреплено");
        String place = request.has("city") ? request.getString("city").trim() : city;
        if(place.length()>80 || place.matches("(?s).*\\p{Cntrl}.*")) throw new IllegalArgumentException("Проверьте город профиля");
        if (!prefs.edit().putString("city",place).putString("title",name).putStringSet("pins",p).putStringSet("hidden",h).commit())
            throw new IllegalStateException("Android не сохранил профиль");
        load();
    }
}
