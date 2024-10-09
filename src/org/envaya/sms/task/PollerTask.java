
package org.envaya.sms.task;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.content.Context;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicNameValuePair;
import org.envaya.sms.App;

public class PollerTask extends HttpTask {

    private ConnectivityManager mConnMgr;  
    public PollerTask(App app) {
        super(app, new BasicNameValuePair("action", App.ACTION_OUTGOING));
        mConnMgr = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);

        waitForWifiToBeActive();
    }

    private void waitForWifiToBeActive()
    {
        NetworkInfo info = mConnMgr.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        Boolean finished = false;

        if (!info.isConnected())
        {
            app.switchingNetworkBecauseOfMMS = true;
            WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            wifi.setWifiEnabled(true);    
        }
        else
        {
            finished = true;
        }

        while(!finished)
        {
            NetworkInfo activeNetwork = mConnMgr.getActiveNetworkInfo();

            finished = activeNetwork != null && activeNetwork.getState() == NetworkInfo.State.CONNECTED && activeNetwork.getType() == ConnectivityManager.TYPE_WIFI;
            
            if (!finished)
            {
                //app.log("(waiting for WIFI Connectivity)");
                try 
                {
                    Thread.sleep(800);
                }
                catch (InterruptedException ex)
                {
                    //Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    protected void onPostExecute(HttpResponse response) {
        super.onPostExecute(response);
        app.markPollComplete();

        waitForWifiToBeActive();
    }
    
    @Override
    protected void handleUnknownContentType(String contentType)
            throws Exception
    {
        throw new Exception("Invalid response type " + contentType);
    }
}