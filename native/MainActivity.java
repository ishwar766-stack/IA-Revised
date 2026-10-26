package com.ia.expenses;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
public class MainActivity extends BridgeActivity {
  @Override public void onCreate(Bundle b) { registerPlugin(IaNativePlugin.class); super.onCreate(b); }
}
