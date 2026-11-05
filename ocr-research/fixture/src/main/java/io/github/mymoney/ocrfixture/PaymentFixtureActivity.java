package io.github.mymoney.ocrfixture;
import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
/** Virtual-device fixture only. No network, payment SDK or financial operation. */
public class PaymentFixtureActivity extends Activity {
 @Override public void onCreate(Bundle saved) {
  super.onCreate(saved);
  if (!(android.os.Build.MODEL.toLowerCase().contains("waydroid") || android.os.Build.PRODUCT.contains("sdk"))) { finish(); return; }
  if (getIntent().getBooleanExtra("secure",false))getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
  String status=getIntent().getStringExtra("status");if(status==null)status="支付成功";
  add(root,"OCR模拟页 · 无真实交易",16);add(root,status,28);
  String tag=getIntent().getStringExtra("tag");if(tag==null)tag="SIM-OCR-DEFAULT";add(root,"¥3.50",56);
  add(root,"商户名称："+tag,20);
  View pixels=new View(this) {
   Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
   @Override public void onDraw(Canvas c) {
    super.onDraw(c);p.setColor(Color.BLACK);p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
    float scale=getResources().getDisplayMetrics().density;p.setTextSize(21*scale);
    c.drawText("商户名称：模拟早餐店",24*scale,55*scale,p);
    c.drawText("支付方式：零钱",24*scale,105*scale,p);
    c.drawText("商品说明：豆浆包子",24*scale,155*scale,p);
   }
  };
  pixels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
  root.addView(pixels,new LinearLayout.LayoutParams(-1, (int)(230*getResources().getDisplayMetrics().density)));
  add(root,"完成",24);setContentView(root);
 }
 void add(LinearLayout root,String text,int size) { TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(Color.BLACK);v.setGravity(Gravity.CENTER);v.setPadding(8,16,8,16);root.addView(v); }
}
