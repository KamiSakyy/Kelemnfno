package com.tsuyu.line;

import android.app.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;

public final class ImageViewerActivity extends Activity {
    private ZoomImage image;
    @Override public void onCreate(Bundle b){super.onCreate(b);if(!Ui.allow(this))return;getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);setContentView(root);image=new ZoomImage(this);image.setBackgroundColor(Color.BLACK);root.addView(image,new FrameLayout.LayoutParams(-1,-1));String url=getIntent().getStringExtra("url"),title=getIntent().getStringExtra("title");YoruApp.app().images.load(image,url==null?"":url,"image-viewer");LinearLayout top=Ui.row(this);top.setPadding(Ui.dp(this,14),Ui.dp(this,22),Ui.dp(this,14),Ui.dp(this,8));top.setBackgroundColor(0x66000000);top.addView(Ui.iconButton(this,"back","Назад",this::finish),Ui.lp(this,46,46));TextView name=Ui.text(this,title==null||title.isEmpty()?"Скриншот":title,14,Ui.TEXT,true);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,-2,1);np.leftMargin=Ui.dp(this,12);top.addView(name,np);root.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));TextView hint=Ui.text(this,"Разведите пальцы, чтобы приблизить",11,Ui.ZINC,false);hint.setGravity(Gravity.CENTER);hint.setPadding(Ui.dp(this,14),Ui.dp(this,10),Ui.dp(this,14),Ui.dp(this,22));hint.setBackgroundColor(0x44000000);root.addView(hint,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));}
    public static final class ZoomImage extends ImageView {
        private final Matrix matrix=new Matrix();private final float[] values=new float[9];private ScaleGestureDetector scale;private float lastX,lastY,minScale=1f;private boolean drag;
        public ZoomImage(android.content.Context c){super(c);setScaleType(ScaleType.MATRIX);scale=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){float factor=d.getScaleFactor();float current=currentScale();float max=Math.max(5f,minScale*5f);float next=Math.max(minScale,Math.min(max,current*factor));float real=current<=0?1f:next/current;matrix.postScale(real,real,d.getFocusX(),d.getFocusY());fitBounds();setImageMatrix(matrix);return true;}});}
        @Override public void setImageBitmap(Bitmap bm){super.setImageBitmap(bm);post(this::reset);}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);post(this::reset);}
        private float currentScale(){matrix.getValues(values);return values[Matrix.MSCALE_X]<=0?1f:values[Matrix.MSCALE_X];}
        private void reset(){android.graphics.drawable.Drawable d=getDrawable();if(d==null||getWidth()==0||getHeight()==0)return;matrix.reset();float dw=d.getIntrinsicWidth(),dh=d.getIntrinsicHeight();if(dw<=0||dh<=0)return;float scale=Math.min(getWidth()/dw,getHeight()/dh);minScale=scale;float dx=(getWidth()-dw*scale)/2f,dy=(getHeight()-dh*scale)/2f;matrix.postScale(scale,scale);matrix.postTranslate(dx,dy);setImageMatrix(matrix);}
        private void fitBounds(){android.graphics.drawable.Drawable d=getDrawable();if(d==null)return;RectF r=new RectF(0,0,d.getIntrinsicWidth(),d.getIntrinsicHeight());matrix.mapRect(r);float dx=0,dy=0;if(r.width()<=getWidth())dx=getWidth()/2f-r.centerX();else if(r.left>0)dx=-r.left;else if(r.right<getWidth())dx=getWidth()-r.right;if(r.height()<=getHeight())dy=getHeight()/2f-r.centerY();else if(r.top>0)dy=-r.top;else if(r.bottom<getHeight())dy=getHeight()-r.bottom;matrix.postTranslate(dx,dy);}
        @Override public boolean onTouchEvent(MotionEvent e){scale.onTouchEvent(e);switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:lastX=e.getX();lastY=e.getY();drag=true;return true;case MotionEvent.ACTION_MOVE:if(drag&&!scale.isInProgress()){float dx=e.getX()-lastX,dy=e.getY()-lastY;matrix.postTranslate(dx,dy);fitBounds();setImageMatrix(matrix);lastX=e.getX();lastY=e.getY();}return true;case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:drag=false;if(e.getEventTime()-e.getDownTime()<180&&currentScale()>minScale+.05f)reset();return true;}return true;}
    }
}
