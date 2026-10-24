package online.mtradius.mtping;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A native RTL slide-out sidebar shared by all MT_TOOLS pages. */
final class Sidebar {
    private final Activity activity;
    private final DrawerOverlay overlay;
    private final String active;
    private static final int BG=0xFF07121E,CARD=0xFF101F30,BORDER=0xFF20364B,
            GREEN=0xFF3EDAB6,WHITE=0xFFF1F5F9,MUTED=0xFF90A2B8;
    private Sidebar(Activity activity,DrawerOverlay overlay,String active){
        this.activity=activity;this.overlay=overlay;this.active=active;
    }
    private int dp(int px){return Math.round(px*activity.getResources().getDisplayMetrics().density);}
    private GradientDrawable bg(int color,int border){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);d.setCornerRadius(dp(13));
        if(border!=0)d.setStroke(dp(1),border);
        return d;
    }

    /** Detect a horizontal closing swipe without stealing vertical scroll events. */
    private static final class DrawerOverlay extends FrameLayout {
        private float startX,startY;
        private final float threshold;
        private boolean dragging;
        private Runnable dismiss;
        DrawerOverlay(Activity activity){
            super(activity);
            threshold=activity.getResources().getDisplayMetrics().density*56f;
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                startX=e.getX(); startY=e.getY(); dragging=false;
                return super.onInterceptTouchEvent(e);
            }
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                float dx=e.getX()-startX, dy=e.getY()-startY;
                if(Math.abs(dx)>threshold*.65f && Math.abs(dx)>Math.abs(dy)*1.3f){
                    dragging=true;
                    return true;
                }
            }
            return super.onInterceptTouchEvent(e);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(dragging && (e.getActionMasked()==MotionEvent.ACTION_UP ||
                    e.getActionMasked()==MotionEvent.ACTION_MOVE)){
                if(Math.abs(e.getX()-startX)>threshold){
                    dragging=false;
                    if(dismiss!=null)dismiss.run();
                    return true;
                }
            }
            if(e.getActionMasked()==MotionEvent.ACTION_CANCEL ||
                    e.getActionMasked()==MotionEvent.ACTION_UP)dragging=false;
            return true;
        }
    }

    static Sidebar attach(Activity activity,View content,String page){
        FrameLayout parent=new FrameLayout(activity);
        parent.addView(content,new FrameLayout.LayoutParams(-1,-1));
        DrawerOverlay overlay=new DrawerOverlay(activity);
        overlay.setVisibility(View.GONE);
        parent.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        Sidebar menu=new Sidebar(activity,overlay,page);
        overlay.dismiss=menu::close;
        menu.setup();
        activity.setContentView(parent);
        return menu;
    }
    private void setup(){
        View dim=new View(activity);
        dim.setBackgroundColor(0xBE030913);
        overlay.addView(dim,new FrameLayout.LayoutParams(-1,-1));
        dim.setOnClickListener(v->close());
        LinearLayout sheet=new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sheet.setPadding(dp(14),dp(21),dp(14),dp(15));
        sheet.setBackgroundColor(CARD);
        FrameLayout.LayoutParams frame=new FrameLayout.LayoutParams(dp(295),-1,Gravity.RIGHT);
        overlay.addView(sheet,frame);
        TextView brand=new TextView(activity);
        brand.setText("MT_TOOLS");brand.setTextColor(WHITE);brand.setTextSize(27);
        brand.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        brand.setPadding(dp(13),dp(14),dp(13),dp(12));
        sheet.addView(brand);
        View line=new View(activity);line.setBackgroundColor(BORDER);
        sheet.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
        ScrollView scroll=new ScrollView(activity);
        scroll.setFillViewport(false);
        sheet.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout items=new LinearLayout(activity);
        items.setPadding(0,dp(11),0,0);
        items.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(items);
        add(items,"ping","◉   مراقبة Ping");
        add(items,"network","◈   معلومات الاتصال والراوتر");
        add(items,"scanner","▤   كشف أجهزة الشبكة");
        add(items,"trace","↝   تتبّع المسار Traceroute");
        add(items,"ubnt","◉   اكتشاف أجهزة Ubiquiti");
        add(items,"diagnostics","⚙   فحوص DNS والمنافذ");
        TextView close=new TextView(activity);
        close.setText("✕   إغلاق القائمة");
        close.setGravity(Gravity.CENTER);close.setTextSize(15);
        close.setTextColor(MUTED);close.setBackground(bg(BG,BORDER));
        sheet.addView(close,new LinearLayout.LayoutParams(-1,dp(51)));
        close.setOnClickListener(v->close());
    }
    private void add(LinearLayout layout,String page,String title){
        TextView item=new TextView(activity);
        item.setText(title);item.setTextSize(15);
        item.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);
        item.setPadding(dp(13),0,dp(12),0);
        item.setTextColor(page.equals(active)?GREEN:WHITE);
        item.setBackground(bg(page.equals(active)?0xFF173245:CARD,
                    page.equals(active)?BORDER:0));
        layout.addView(item,new LinearLayout.LayoutParams(-1,dp(56)));
        if(!page.equals(active))item.setOnClickListener(v->navigate(page));
    }
    void open(){
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(160).start();
    }
    void close(){overlay.setVisibility(View.GONE);}
    boolean isOpen(){return overlay.getVisibility()==View.VISIBLE;}
    void navigate(String page){
        close();
        if(page.equals(active))return;
        if("ping".equals(page)){
            Intent target=new Intent(activity,MainActivity.class);
            target.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(target);
        }else{
            Intent target=new Intent(activity,ToolsActivity.class).putExtra("page",page);
            activity.startActivity(target);
        }
        if(activity instanceof ToolsActivity)activity.finish();
    }
}
