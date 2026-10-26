package dev.codex.android;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="w411dp-h823dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class UiPreviewTest {
    private ActivityController<MainActivity> screen(String page) {
        Intent intent=new Intent(Intent.ACTION_MAIN).putExtra("preview",page);
        return Robolectric.buildActivity(MainActivity.class,intent).setup().visible();
    }
    private void capture(MainActivity activity,String name) throws Exception {
        View decor=activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(822,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1646,View.MeasureSpec.EXACTLY));
        decor.layout(0,0,822,1646);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        decor.measure(View.MeasureSpec.makeMeasureSpec(822,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1646,View.MeasureSpec.EXACTLY));
        decor.layout(0,0,822,1646);
        View send=find(decor,"↑");
        if(send!=null && send.isShown()) {
            int[] position=new int[2];send.getLocationOnScreen(position);
            assertTrue("Composer must fit the screen: "+position[1],position[1]+send.getHeight()<=1646);
        }
        Bitmap bitmap=Bitmap.createBitmap(822,1646,Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bitmap));
        File directory=new File("../previews");directory.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(directory,name+".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,out); }
    }
    private View find(View root,String text) {
        if(root instanceof TextView && ((TextView)root).getText().toString().equals(text))return root;
        if(root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++){View result=find(group.getChildAt(i),text);if(result!=null)return result;}
        }
        return null;
    }
    @Test public void renderLoginChatAndFileAccess() throws Exception {
        for(String page:new String[]{"login","chat","files"}) {
            try(ActivityController<MainActivity> controller=screen(page)) {
                MainActivity activity=controller.get();
                assertNotNull(find(activity.getWindow().getDecorView(),"Files & access"));
                capture(activity,page+"-ui");
            }
        }
    }
    @Test public void projectChooserUsesAndroidPermissionFlow() {
        try(ActivityController<MainActivity> controller=screen("files")) {
            MainActivity activity=controller.get();
            View choose=find(activity.getWindow().getDecorView(),"Choose project folder");
            assertNotNull(choose);choose.performClick();
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE,shadowOf(activity).getNextStartedActivityForResult().intent.getAction());
        }
    }
    @Test public void permissionSelectorShowsAllThreeModes() {
        try(ActivityController<MainActivity> controller=screen("chat")) {
            MainActivity activity=controller.get();
            View permissions=find(activity.getWindow().getDecorView(),"Project access ▾");
            assertNotNull(permissions);permissions.performClick();
            android.app.AlertDialog dialog=(android.app.AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertEquals(3,dialog.getListView().getAdapter().getCount());
            assertTrue(dialog.getListView().getAdapter().getItem(2).toString().contains("Full access"));
            dialog.dismiss();
        }
    }
}
