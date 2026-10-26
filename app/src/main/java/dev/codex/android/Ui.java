package dev.codex.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

final class Ui {
    static final int BG = Color.rgb(12, 15, 20), PANEL = Color.rgb(22, 27, 35), BORDER = Color.rgb(43, 51, 63);
    static final int TEXT = Color.rgb(239, 243, 248), MUTED = Color.rgb(150, 161, 178), GREEN = Color.rgb(135, 232, 186);
    static int dp(Context c, int value) { return (int)(value * c.getResources().getDisplayMetrics().density); }
    static GradientDrawable surface(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(radius); shape.setStroke(1, BORDER); return shape;
    }
    static TextView text(Context c, String text, int size, int color) {
        TextView view = new TextView(c); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setFontFeatureSettings("kern"); return view;
    }
    static LinearLayout column(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(1); return v; }
    static void pad(View v, int n) { int p = dp(v.getContext(), n); v.setPadding(p,p,p,p); }
    static Button button(Context c, String text, boolean primary, View.OnClickListener action) {
        Button b = new Button(c); b.setText(text); b.setAllCaps(false); b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setTextColor(primary ? BG : TEXT);
        b.setBackground(surface(primary ? GREEN : PANEL, dp(c,12))); b.setMinHeight(dp(c,44));
        b.setMinimumHeight(dp(c,44)); b.setPadding(dp(c,14),dp(c,8),dp(c,14),dp(c,8));
        b.setOnClickListener(action); return b;
    }
    static void gap(LinearLayout p, int n) { View v = new View(p.getContext()); p.addView(v,new LinearLayout.LayoutParams(1,dp(p.getContext(),n))); }
}
