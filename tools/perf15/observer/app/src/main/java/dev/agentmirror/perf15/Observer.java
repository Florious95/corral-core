package dev.agentmirror.perf15;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicLong;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** @contract Read existing target objects only; never feed frames or invoke VM mutations.
 * @inv A trial completes only after real hardware frame commit and exact active model equality.
 * @err Missing reflection/UI/history/commit evidence terminates the trial without a PASS.
 */
public final class Observer extends Instrumentation {
    private volatile Activity activity;
    private Bundle args;
    private File root;
    private final JSONArray records = new JSONArray();
    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args=args; start(); }
    @Override public void callActivityOnResume(Activity a) { super.callActivityOnResume(a); activity=a; }
    static String hex(byte[] data) { StringBuilder b=new StringBuilder(); for(byte x:data)b.append(String.format("%02x",x&255));return b.toString(); }
    static Object field(Object o, String name) throws Exception {
        for (Class<?> c=o.getClass();c!=null;c=c.getSuperclass()) {
            try { Field f=c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
            catch(NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    static Object call(Object o,String name,Object... args) throws Exception {
        for(Method m:o.getClass().getMethods()) if(m.getName().equals(name)&&m.getParameterCount()==args.length) {
            m.setAccessible(true); return m.invoke(o,args);
        }
        throw new NoSuchMethodException(name);
    }
    static void find(View v,List<View> out) {
        if(v.getClass().getName().equals("dev.agentmirror.app.termview.TermSurfaceView") && v.isShown() && v.isAttachedToWindow()) out.add(v);
        if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) find(((ViewGroup)v).getChildAt(i),out);
    }
    static JSONArray cells(Object rows) throws Exception {
        JSONArray result=new JSONArray();
        for(Object row:(Iterable<?>)rows) {
            JSONArray line=new JSONArray();
            for(Object cell:(Iterable<?>)row) {
                Object style=call(cell,"getStyle");
                JSONArray tuple=new JSONArray().put(call(cell,"getText")).put(call(cell,"getWidth"));
                // Kotlin data values preserve exact RGB/index and all six attributes.
                tuple.put(call(style,"getFg").toString()).put(call(style,"getBg").toString());
                for(String key:new String[]{"Bold","Dim","Italic","Underline","Inverse","Strikethrough"}) tuple.put(call(style,"get"+key));
                line.put(tuple);
            }
            result.put(line);
        }
        return result;
    }
    static JSONObject read(View view) throws Exception {
        Object bound=call(view,"getOnRemoteScrollBy");
        Object vm=call(bound,"getBoundReceiver");
        if(!vm.getClass().getName().equals("dev.agentmirror.app.session.SessionViewModel")) throw new IllegalStateException("wrong bound VM");
        Object emulator=call(vm,"getEmulator"), presenter=call(view,"getPresenter");
        if(presenter!=call(vm,"getPresenter")) throw new IllegalStateException("detached presenter");
        JSONObject out=new JSONObject();
        synchronized(emulator) {
            Object snapshot=call(emulator,"snapshot"), history=field(emulator,"scrollback");
            synchronized(field(history,"lock")) {
                List<Object> rows=new ArrayList<>(); int size=((Number)call(history,"getSize")).intValue();
                for(int i=0;i<size;i++) rows.add(call(history,"line",i));
                out.put("history",cells(rows));
            }
            for(String key:new String[]{"Cols","Rows","CursorX","CursorY","CursorVisible","AltScreen"}) out.put(key,call(snapshot,"get"+key));
            out.put("grid",cells(call(snapshot,"getLines")));
            Object window=call(presenter,"getWindow");
            int first=((Number)call(window,"getFirst")).intValue(),last=((Number)call(window,"getLast")).intValue();
            int historySize=out.getJSONArray("history").length();
            out.put("wholeScreenVisible",first<=historySize && last>=historySize+((Number)call(snapshot,"getRows")).intValue()-1);
            out.put("historyPending",field(vm,"historyRequestInFlight"));
            out.put("prefetched",field(vm,"hasPrefetchedHistory"));
        }
        out.put("ref",call(view,"getSessionRef"));
        if(!out.getString("ref").equals(call(vm,"getRef"))) throw new IllegalStateException("view/VM ref mismatch");
        out.put("state",call(vm,"getConnectionState").toString());
        out.put("error",call(vm,"getTransientError")==null?JSONObject.NULL:call(vm,"getTransientError"));
        out.put("vmIdentity",System.identityHashCode(vm));
        Object connection=field(field(vm,"manager"),"connection");
        out.put("connectionIdentity",connection==null?0:System.identityHashCode(connection));
        out.put("visible",view.isShown()&&view.isAttachedToWindow()&&view.hasWindowFocus());
        return out;
    }
    static boolean equal(JSONObject a,JSONObject b,String key) throws Exception { return a.get(key).toString().equals(b.get(key).toString()); }
    static boolean matches(JSONObject actual,JSONObject expected) throws Exception {
        if(!actual.getBoolean("wholeScreenVisible")||!actual.getBoolean("visible")||!actual.getString("state").equals("READY")||!actual.isNull("error")||actual.getBoolean("historyPending")||!actual.getBoolean("prefetched")) return false;
        for(String key:new String[]{"ref","Cols","Rows","CursorX","CursorY","CursorVisible","AltScreen","grid"}) if(!equal(actual,expected,key)) return false;
        JSONArray have=actual.getJSONArray("history"),need=expected.getJSONArray("history");
        if(need.length()==0||have.length()<need.length())return false;
        for(int i=0;i<need.length();i++)if(!have.get(have.length()-need.length()+i).toString().equals(need.get(i).toString()))return false;
        return true;
    }
    static void controls(JSONObject observed,JSONObject expected) throws Exception {
        if(!matches(observed,expected)) throw new IllegalStateException("positive oracle not met");
        JSONObject wrong=new JSONObject(observed.toString()); wrong.put("ref","wrong-ref");
        if(matches(wrong,expected)) throw new IllegalStateException("wrong-ref control accepted");
        wrong=new JSONObject(observed.toString()); wrong.put("historyPending",true);
        if(matches(wrong,expected)) throw new IllegalStateException("unapplied history control accepted");
        wrong=new JSONObject(observed.toString()); wrong.put("history",new JSONArray());
        if(expected.getJSONArray("history").length()==0) throw new IllegalStateException("history oracle must be nonempty");
        if(matches(wrong,expected)) throw new IllegalStateException("missing history accepted");
        wrong=new JSONObject(observed.toString()); wrong.put("grid",new JSONArray());
        if(matches(wrong,expected)) throw new IllegalStateException("wrong screen accepted");
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            root=new File(getTargetContext().getExternalFilesDir(null),"perf15-observer");
            JSONObject plan=new JSONObject(Files.readString(new File(root,args.getString("plan","plan.json")).toPath()));
            if(!plan.getString("apkSha256").equals("bd4ababfcec3301c19ced02350ff2193f183311f9ec0bdd455145deab3b3e861")) throw new IllegalStateException("fixed APK contract missing");
            String actualHash=hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(getTargetContext().getApplicationInfo().sourceDir).toPath())));
            if(!actualHash.equals(plan.getString("apkSha256"))) throw new IllegalStateException("installed product APK changed");
            Intent launch=getTargetContext().getPackageManager().getLaunchIntentForPackage("dev.agentmirror.app");
            if(launch==null) throw new IllegalStateException("target launch missing");
            startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync();
            JSONArray trials=plan.getJSONArray("trials");
            if(trials.length()<1||trials.length()>20)throw new IllegalArgumentException("one bounded block requires 1..20 trials");
            for(int i=0;i<trials.length();i++) trial(trials.getJSONObject(i));
            result.putString("status","observed");
            Files.writeString(new File(root,"result.json").toPath(),new JSONObject().put("records",records).put("performance_pass",false).toString(2));
            finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {
            result.putString("failure",e.getClass().getName()+": "+e.getMessage());
            try { if(root!=null)Files.writeString(new File(root,"result.json").toPath(),new JSONObject().put("records",records).put("failure",result.getString("failure")).put("performance_pass",false).toString(2)); }
            catch(Exception writeError) { result.putString("evidenceWriteFailure",writeError.toString()); }
            finish(Activity.RESULT_CANCELED,result);
        }
    }
    private void trial(JSONObject spec) throws Exception {
        JSONObject expected=spec.getJSONObject("expected"), previous=spec.getJSONObject("previousExpected");
        if(spec.getLong("deadlineMs")<1||spec.getLong("deadlineMs")>35000)throw new IllegalArgumentException("invalid observation budget");
        if(expected.getJSONArray("history").length()>500)throw new IllegalArgumentException("unbounded history oracle");
        // External coordinator prepares only owned real fixtures/navigation outside timing.
        String id=spec.getString("id"); if(!id.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("unsafe trial id");
        File go=new File(root,id+".go"); if(go.exists())throw new IllegalStateException("stale trial trigger");
        Files.writeString(new File(root,"ready.json").toPath(),new JSONObject().put("id",id).put("phase","prepare").toString());
        Bundle ready=new Bundle();ready.putString("readyId",id);sendStatus(10,ready);
        long prepareEnd=SystemClock.elapsedRealtime()+120000;
        while(!go.exists()&&SystemClock.elapsedRealtime()<prepareEnd)SystemClock.sleep(50);
        if(!go.exists())throw new IllegalStateException("fixture/navigation prepare signal absent");
        if(!go.delete())throw new IllegalStateException("cannot consume owned trigger");
        if(equal(expected,previous,"grid")&&equal(expected,previous,"history")&&equal(expected,previous,"ref")) throw new IllegalArgumentException("old generation oracle indistinguishable");
        JSONObject record=new JSONObject().put("id",spec.getString("id")).put("generation",spec.getString("generation")).put("ref",expected.getString("ref"));
        CountDownLatch done=new CountDownLatch(1); Throwable[] failure={null}; AtomicLong start=new AtomicLong(); boolean[] pending={false}; long[] overhead={0,0};
        View decor=activity.getWindow().getDecorView();
        if(!decor.isHardwareAccelerated()) throw new IllegalStateException("hardware frame commit unavailable");
        ViewTreeObserver.OnDrawListener listener=()->{
            if(start.get()==0||pending[0]||done.getCount()==0) return;
            try {
                List<View> views=new ArrayList<>(); find(decor,views);
                if(views.size()!=1) return;
                View view=views.get(0); long readStart=SystemClock.elapsedRealtimeNanos(); JSONObject before=read(view);
                overhead[0]++; overhead[1]+=SystemClock.elapsedRealtimeNanos()-readStart;
                if(!matches(before,expected)) { record.put("lastUnmatched",before); return; }
                if(matches(before,previous)) throw new IllegalStateException("old generation accepted");
                controls(before,expected); pending[0]=true;
                long readEnd=SystemClock.elapsedRealtimeNanos();
                decor.getViewTreeObserver().registerFrameCommitCallback(()->new Handler(Looper.getMainLooper()).post(()->{
                    try {
                        if(done.getCount()==0)return;
                        long committed=SystemClock.elapsedRealtimeNanos(); JSONObject after=read(view);
                        if(!matches(after,expected)||!equal(before,after,"vmIdentity")||!equal(before,after,"connectionIdentity")) { pending[0]=false; return; }
                        record.put("actionStartNs",start.get()).put("frameCommitObservedNs",committed).put("readStartNs",readStart).put("readEndNs",readEnd).put("afterReadNs",SystemClock.elapsedRealtimeNanos()).put("actual",after).put("negativeControls",true).put("readAttempts",overhead[0]).put("totalBeforeReadNs",overhead[1]);
                        done.countDown();
                    } catch(Throwable e) { failure[0]=e;done.countDown(); }
                }));
            } catch(Throwable e) {failure[0]=e;done.countDown();}
        };
        runOnMainSync(()->decor.getViewTreeObserver().addOnDrawListener(listener));
        try {
            String action=spec.getString("action");
            if(action.equals("resume")) {
                getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
                long backgroundMs=spec.getLong("backgroundMs"); if(backgroundMs<1||backgroundMs>10000)throw new IllegalArgumentException("background budget");
                SystemClock.sleep(backgroundMs);
                start.set(SystemClock.elapsedRealtimeNanos());
                startActivitySync(getTargetContext().getPackageManager().getLaunchIntentForPackage("dev.agentmirror.app").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if(action.equals("select")) {
                start.set(SystemClock.elapsedRealtimeNanos()); long now=SystemClock.uptimeMillis();
                MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,(float)spec.getDouble("x"),(float)spec.getDouble("y"),0);
                MotionEvent up=MotionEvent.obtain(now,now+1,MotionEvent.ACTION_UP,(float)spec.getDouble("x"),(float)spec.getDouble("y"),0);
                try {sendPointerSync(down);sendPointerSync(up);} finally {down.recycle();up.recycle();}
            } else throw new IllegalArgumentException("action must be select or resume");
            if(!done.await(spec.getLong("deadlineMs"),TimeUnit.MILLISECONDS)) throw new IllegalStateException("correct committed current grid/history endpoint absent");
            if(failure[0]!=null) throw new IllegalStateException("observer failed",failure[0]);
            record.put("completed",true);
            Files.writeString(new File(root,id+".result.json").toPath(),record.toString(2));
        } finally {done.countDown();runOnMainSync(()->decor.getViewTreeObserver().removeOnDrawListener(listener));records.put(record);}
    }
}
