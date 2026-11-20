package tech.krasivo.tvdesk;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;

public final class Home extends Activity {
    private static final int ACCESS = 71, DISCOVER = 72, EXTERNAL = 73;
    private static final int DARK = 0xff112621, CARD = 0xff25413a, MINT = 0xffa5f4c7;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Apps apps;
    private boolean servicePage, visible, external;
    private boolean wasMounted;
    private String status = "Сеанс закрыт";
    private TextView statusView;
    private LinearLayout body;
    private Link link;
    private AlertDialog offer;
    private BluetoothAdapter bt;

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        apps = new Apps(this);
        BluetoothManager bm = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        bt = bm == null ? null : bm.getAdapter();
    }
    @Override public void onResume() {
        super.onResume(); visible = true; apps.scan(false); render();
        wasMounted = boundPresent();
        ui.removeCallbacks(watchUsb); ui.postDelayed(watchUsb, 1800);
    }
    @Override public void onPause() { visible = false; ui.removeCallbacks(watchUsb); super.onPause(); }
    @Override public void onStop() {
        if (!external && link != null) finishLink(link, "Сеанс закрыт");
        super.onStop();
    }
    @Override public void onDestroy() {
        if (link != null) finishLink(link, "Сеанс закрыт");
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (servicePage) { servicePage=false; render(); }
        else super.onBackPressed();
    }
    private int dp(int n) { return (int)(getResources().getDisplayMetrics().density*n+.5f); }
    private TextView text(String s,int size,int color) {
        TextView v=new TextView(this); v.setText(s); v.setTextColor(color); v.setTextSize(size); return v;
    }
    private void heading(String s) {
        TextView h=text(s,30,Color.WHITE); h.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        h.setPadding(0,dp(9),0,dp(12)); body.addView(h);
    }
    private void group(String s) {
        TextView h=text(s,17,MINT); h.setPadding(0,dp(16),0,dp(6)); body.addView(h);
    }
    private void style(Button b,boolean focused) {
        GradientDrawable bg=new GradientDrawable(); bg.setColor(focused?MINT:CARD);
        bg.setCornerRadius(dp(9)); b.setBackground(bg); b.setTextColor(focused?DARK:Color.WHITE);
    }
    private Button button(String s,Runnable action) {
        Button b=new Button(this); b.setAllCaps(false); b.setText(s); b.setTextSize(16);
        b.setMaxLines(2); b.setPadding(dp(12),dp(8),dp(12),dp(8)); style(b,false);
        b.setOnFocusChangeListener((v,focus)->style(b,focus)); b.setOnClickListener(v->action.run()); return b;
    }
    private void add(String s,Runnable action) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(6);
        body.addView(button(s,action),p);
    }
    private void tiles(boolean pinned) {
        LinearLayout row=null; int count=0;
        for (Apps.Entry a:apps.list) {
            if (a.component == null || apps.hidden.contains(a.id) || apps.pins.contains(a.id)!=pinned) continue;
            if (count++%4==0) {
                row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); body.addView(row);
            }
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(148),dp(58)); p.setMargins(0,0,dp(5),dp(5));
            row.addView(button(a.name,()->launch(a)),p);
        }
        if (count==0) body.addView(text(pinned?"Закрепите приложения с ноутбука.":"Других ярлыков нет.",15,0xffb3cac2));
    }
    private void render() {
        ScrollView sc=new ScrollView(this); sc.setFillViewport(true); sc.setBackgroundColor(DARK);
        body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(33),dp(20),dp(33),dp(22)); sc.addView(body); setContentView(sc);
        statusView=null;
        if (!servicePage) {
            heading(apps.title);
            android.widget.TextClock clock = new android.widget.TextClock(this);
            clock.setFormat12Hour("EEE, d MMM · h:mm a"); clock.setFormat24Hour("EEE, d MMM · HH:mm");
            clock.setTextSize(23); clock.setTextColor(MINT); body.addView(clock);
            if(!apps.city.isEmpty()) body.addView(text(apps.city,18,Color.WHITE));
            if (!apps.pins.isEmpty()) { group("Избранное"); tiles(true); }
            group("Приложения"); tiles(false);
            group("Телевизор");
            add("Настройка с ноутбука",()->{servicePage=true;render();});
            add("Настройки Android",()->openSystem(new Intent(Settings.ACTION_SETTINGS)));
        } else {
            heading("Настройка с ноутбука");
            statusView=text(status,19,MINT); statusView.setPadding(0,0,0,dp(12)); body.addView(statusView);
            add(link==null?"Открыть сеанс Bluetooth":"Завершить сеанс",()->{
                if(link==null) startLink(); else finishLink(link,"Сеанс закрыт пультом");
            });
            add("Сделать ТВ видимым для сопряжения",this::discover);
            add("Привязать сервисную флешку",this::bindUsb);
            if (apps.prefs.contains("usb")) add("Отвязать флешку",()->{
                apps.prefs.edit().remove("usb").apply(); wasMounted=false; note("Привязка удалена");
                render();
            });
            add("Выбрать домашний экран Android",()->openSystem(new Intent(Settings.ACTION_HOME_SETTINGS)));
            add("Все приложения в настройках Android",()->openSystem(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)));
            add("Вернуться к приложениям",()->{servicePage=false;render();});
        }
    }
    private void note(String s) {
        status=s;
        if(statusView!=null) statusView.setText(s);
        else Toast.makeText(this,s,Toast.LENGTH_LONG).show();
    }
    private void launch(Apps.Entry app) {
        try { startActivity(new Intent(Intent.ACTION_MAIN).setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException e) {note("Android не смог открыть "+app.name);}
    }
    private boolean openSystem(Intent i) {
        try { external=true; startActivityForResult(i,EXTERNAL); return true; }
        catch (RuntimeException e) {external=false; note("Прошивка не открыла этот раздел. Используйте системное меню телевизора."); return false;}
    }
    @Override protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if(code==EXTERNAL) external=false;
    }
    private boolean permitted(String p) { return Build.VERSION.SDK_INT<31 || checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED; }
    private void startLink() {
        if(link!=null) return;
        if(bt==null) {note("Bluetooth Classic недоступен"); return;}
        if(!permitted(Manifest.permission.BLUETOOTH_CONNECT)) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE},ACCESS); return;
        }
        if(!bt.isEnabled()) {note("Включите Bluetooth в настройках телевизора и повторите");return;}
        servicePage=true; status="Открываю сеанс…";
        link=new Link(this,bt); render(); new Thread(link,"tvdesk-rfcomm").start();
    }
    private void discover() {
        if(bt==null){note("Bluetooth недоступен");return;}
        if(!permitted(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_ADVERTISE},DISCOVER);return;
        }
        openSystem(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,120));
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(code,permissions,grants);
        if(grants.length==0 || grants[0]!=PackageManager.PERMISSION_GRANTED) {note("Для связи разрешите доступ к ближайшим устройствам");return;}
        if(code==ACCESS) startLink(); else if(code==DISCOVER) discover();
    }
    void offer(Link s,String name) {
        if(link!=s || !visible) {s.decide(false);return;}
        String display = name == null ? "Устройство Bluetooth" : name;
        offer=new AlertDialog.Builder(this).setTitle("Разрешить настройку с ноутбука?")
            .setMessage(display+" сможет видеть список приложений, менять домашний экран TV Desk и открывать системные настройки. Сеанс длится до 15 минут.")
            .setNegativeButton("Отклонить",(d,w)->s.decide(false))
            .setPositiveButton("Разрешить",(d,w)->{note("Подключено · "+display);s.decide(true);})
            .setOnCancelListener(d->s.decide(false)).create();
        offer.show();offer.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }
    void linkStatus(Link s,String message){if(s==link)note(message);}
    void finishLink(Link s,String message){
        if(link!=s)return;link=null;s.stop();
        if(offer!=null){offer.dismiss();offer=null;}
        status=message;
        if(visible && !isFinishing())render();
    }
    JSONObject command(Link s,JSONObject request, Apps inventory) throws Exception {
        if(s!=link)throw new IllegalStateException("Сеанс завершён");
        if(!visible)throw new IllegalStateException("Вернитесь в TV Desk кнопкой «Назад» на пульте");
        switch(request.getString("op")){
            case "state": return inventory.state();
            case "profile": inventory.apply(request);apps.load();render();return inventory.state();
            case "uninstall":
                Apps.Entry removable = inventory.byId(request.getString("app"));
                if (inventory.kept(removable)) throw new IllegalStateException("TV Desk сохраняет Кинопоиск и закреплённые приложения");
                if (removable.system) throw new IllegalStateException("Для системных приложений используйте карточку Android");
                Intent remove = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package",removable.id,null));
                remove.putExtra(Intent.EXTRA_RETURN_RESULT,true);
                if(!openSystem(remove)) throw new IllegalStateException("Прошивка не открыла диалог удаления. Откройте карточку приложения.");
                return new JSONObject();
            case "details":
                Apps.Entry a=inventory.byId(request.getString("app"));
                if(!openSystem(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package",a.id,null))))
                    throw new IllegalStateException("Карточка недоступна на этой прошивке");
                return new JSONObject();
            case "clock":
            case "locale":
            case "location":
                String op=request.getString("op");
                String sys="clock".equals(op)?Settings.ACTION_DATE_SETTINGS:("locale".equals(op)?Settings.ACTION_LOCALE_SETTINGS:Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                if(!openSystem(new Intent(sys)))throw new IllegalStateException("Откройте раздел в настройках телевизора");
                return new JSONObject();
            case "store":
                String pkg=request.getString("app");
                if(pkg.length()>200 || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
                    throw new IllegalArgumentException("Некорректный идентификатор приложения");
                Intent details=new Intent(Intent.ACTION_VIEW,Uri.parse("market://details?id="+pkg)).setPackage("com.android.vending");
                if(!openSystem(details)) throw new IllegalStateException("Откройте магазин приложений на телевизоре вручную");
                return new JSONObject();
            case "manage":
            case "home":
                String action="manage".equals(request.getString("op"))?Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS:Settings.ACTION_HOME_SETTINGS;
                if(!openSystem(new Intent(action))) throw new IllegalStateException("Откройте этот раздел вручную в меню Android");
                return new JSONObject();
            default: throw new IllegalArgumentException("Неизвестное действие");
        }
    }
    private List<StorageVolume> volumes() {
        List<StorageVolume> found=new ArrayList<>();
        StorageManager sm=(StorageManager)getSystemService(STORAGE_SERVICE);
        if(sm!=null)for(StorageVolume v:sm.getStorageVolumes()){
            String state=v.getState();
            if(v.isRemovable() && v.getUuid()!=null && (Environment.MEDIA_MOUNTED.equals(state)||Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)))found.add(v);
        }
        return found;
    }
    private boolean boundPresent() {
        String id=apps.prefs.getString("usb",null);
        if(id==null)return false;
        for(StorageVolume v:volumes())if(id.equals(v.getUuid()))return true;
        return false;
    }
    private void bindUsb() {
        List<StorageVolume> found=volumes();
        if(found.isEmpty()){note("Вставьте флешку. Нужен съёмный том, которому Android присвоил UUID.");return;}
        String[] names=new String[found.size()];
        for(int i=0;i<names.length;i++)names[i]=found.get(i).getDescription(this)+" · "+found.get(i).getUuid();
        new AlertDialog.Builder(this).setTitle("Какую флешку использовать для настройки?").setItems(names,(d,i)->{
            apps.prefs.edit().putString("usb",found.get(i).getUuid()).apply();wasMounted=true;
            status="Флешка привязана. Оставьте TV Desk на экране и вставьте её повторно.";render();
        }).show();
    }
    private final Runnable watchUsb=new Runnable(){public void run(){
        if(!visible)return;
        boolean mounted=boundPresent(),arrived=mounted && !wasMounted;wasMounted=mounted;
        if(arrived && link==null)startLink();
        ui.postDelayed(this,1800);
    }};
}
