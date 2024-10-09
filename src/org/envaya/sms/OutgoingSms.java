package org.envaya.sms;

import com.androidbridge.SendMMS3.PhoneEx;
import com.androidbridge.nokia.IMMConstants;
import com.androidbridge.nokia.MMContent;
import com.androidbridge.nokia.MMEncoder;
import com.androidbridge.nokia.MMMessage;
import com.androidbridge.nokia.MMResponse;
import com.androidbridge.nokia.MMSender;

import android.net.wifi.WifiManager;
import android.graphics.BitmapFactory;
import android.database.Cursor;
import android.content.Context;
import android.os.Environment;
import android.content.Intent;
import android.net.Uri;
import android.telephony.SmsManager;
import android.os.PowerManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import java.net.InetAddress;
import java.util.Enumeration;
import java.util.Date;
import java.util.ArrayList;
import java.util.UUID;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.lang.reflect.Method;

public class OutgoingSms extends OutgoingMessage {
	private PowerManager.WakeLock mWakeLock;
    private ConnectivityManager mConnMgr;

    public OutgoingSms(App app)
    {
        super(app);
        createWakeLock();
        mConnMgr = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
    }    
    
    public String getMessageType()
    {
        return App.MESSAGE_TYPE_SMS;
    }

    private synchronized void createWakeLock() {
		// Create a new wake lock if we haven't made one yet.
		if (mWakeLock == null) {
            //Context cntext = app.getApplicationContext();
			PowerManager pm = (PowerManager)app.getSystemService(Context.POWER_SERVICE);
			mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MMS Connectivity");
			mWakeLock.setReferenceCounted(false);
		}
	}

    private ArrayList<String> _bodyParts;
    
    public ArrayList<String> getBodyParts()
    {
        if (_bodyParts == null)
        {
            SmsManager smgr = SmsManager.getDefault();
            _bodyParts = smgr.divideMessage(getMessageBody());
        }
        return _bodyParts;
    }
    
    public int getNumParts()
    {
        return getBodyParts().size();        
    }
    
    public class ScheduleInfo extends OutgoingMessage.ScheduleInfo
    {
        public String packageName;
    }
        
    public OutgoingMessage.ScheduleInfo scheduleSend()
    {        
        ScheduleInfo schedule = new ScheduleInfo();
        
        int numParts = getNumParts();
        String packageName = app.chooseOutgoingSmsPackage(numParts);            

        if (packageName == null)
        {            
            schedule.time = app.getNextValidOutgoingTime(numParts);                
            schedule.now = false;
        }                
        else
        {
            schedule.now = true;
            schedule.packageName = packageName;
        }
        
        return schedule;
    }
    
    public void send(OutgoingMessage.ScheduleInfo _schedule)
    {
        ScheduleInfo schedule = (ScheduleInfo)_schedule;
        String messageType = getDisplayType();
        
        if (numRetries == 0)
        {
            app.log("Sending " + messageType);
        }
        else
        {        
            app.log("Retrying sending " + getDescription());
        }

        if (messageType.equals("MMS"))
        {
            SendMMSBroadcast(schedule);
        }
        else
        {
            SendSMSBroadcast(schedule, false);            
        }
    }

    public void SendSMSBroadcast(ScheduleInfo schedule, Boolean bouncedFromMMS)
    {                
        ArrayList<String> bodyParts = getBodyParts();
        int numParts = bodyParts.size();
        if (numParts > 1)
        {
            app.log("(Multipart message with " + numParts + " parts)");
        }

        Intent intent = new Intent(schedule.packageName + App.OUTGOING_SMS_INTENT_SUFFIX, this.getUri());
        intent.putExtra(App.OUTGOING_SMS_EXTRA_DELIVERY_REPORT, false);
        intent.putExtra(App.OUTGOING_SMS_EXTRA_TO, getTo());

        if (bouncedFromMMS)
        {
            app.log("(bounced from MMS but sent via SMS)");
        }

        intent.putExtra(App.OUTGOING_SMS_EXTRA_BODY, bodyParts);
        
        app.sendBroadcast(intent, "android.permission.SEND_SMS"); 
    }

    public void SendMMSBroadcast(ScheduleInfo schedule)
    {
        ArrayList<String> bodyParts = getBodyParts();
        int numParts = bodyParts.size();
        if (numParts > 1)
        {
            app.log("(Multipart message with "+numParts+" parts)");
        }

        try
        {
            String fileExtension = getMessageAttachmentFileExtension();
            String cacheName = getMessageAttachmentUrlOrBase64();
            String fileBaseType = getMessageAttachmentFileType();

            if (!cacheName.equals(""))
            {
                if (fileExtension.equals(""))
                {
                    fileExtension = "jpg";
                    if (fileBaseType.equals("url"))
                    {
                        String[] urlFileNameParts = cacheName.split(".");
                        if (urlFileNameParts.length > 1)
                        {
                            fileExtension = urlFileNameParts[urlFileNameParts.length -1].toLowerCase();
                        }
                    }                    
                }

                byte[] fileBytes = app.cachedInstanceImages.get(cacheName);
                
                //app.log("fiteBytes is Null: " + fileBytes == null ? "true" : "false");
                //app.log("items in cache: " + app.cachedInstanceImages.size());
                
                final String MMSCenterUrl = getAPNMMSC();
                final String MMSProxy = getAPNProxy();
                final int MMSPort = Integer.valueOf(getAPNPort()); 
                
                waitForMobileNetworkToBeActive();
                waitForActiveConnectivity();

                //acquireWakeLock();
                //beginMmsConnectivity(MMSPort);
                // waitForMobileNetworkToBeActive(MMSPort);
                //waitForActiveConnectivity();

                //Context cntext = app.getApplicationContext();
                String someRandomId = UUID.randomUUID().toString().substring(0, 8);
                String fromNumber = getFrom().replaceAll("-", "").replaceAll(" ", ""); 
                String toNumber = getTo().replaceAll("-", "").replaceAll(" ", "");

                if (!fromNumber.startsWith("+"))
                {
                    fromNumber = "+1" + fromNumber;
                }

                if ((!toNumber.trim().equals("")) && (!toNumber.startsWith("+")))
                {
                    toNumber = "+1" + toNumber;
                }

                // if (fromNumber.trim().equals(""))
                // {
                //     fromNumber = "+19039187777";
                // }

                app.log("MMS sending to: " + toNumber);
                //app.log("MMS sending from: " + fromNumber);

                MMMessage mm = new MMMessage();
                mm.setVersion(IMMConstants.MMS_VERSION_10);
                mm.setMessageType(IMMConstants.MESSAGE_TYPE_M_SEND_REQ);
                mm.setTransactionId(someRandomId);
                mm.setDate(new Date(System.currentTimeMillis()));
                mm.setFrom(fromNumber + "/TYPE=PLMN"); // doesnt work, i wish this worked as it should be
                mm.addToAddress(toNumber + "/TYPE=PLMN");
                mm.setDeliveryReport(true);
                mm.setReadReply(false);
                mm.setSenderVisibility(IMMConstants.SENDER_VISIBILITY_SHOW);
                //mm.setSubject(sb.toString());
                mm.setMessageClass(IMMConstants.MESSAGE_CLASS_PERSONAL);
                mm.setPriority(IMMConstants.PRIORITY_LOW);
                mm.setContentType(IMMConstants.CT_APPLICATION_MULTIPART_MIXED);

                Integer partId = 0;
                if (fileBytes != null)
                {
                    String attachmentPartType = IMMConstants.CT_IMAGE_JPEG;
                    if (fileExtension.toLowerCase().equals("jpg"))
                    {
                        attachmentPartType = IMMConstants.CT_IMAGE_JPEG;
                    }
                    else if (fileExtension.toLowerCase().equals("jpeg"))
                    {
                        attachmentPartType = IMMConstants.CT_IMAGE_JPEG;
                    }
                    else if (fileExtension.toLowerCase().equals("gif"))
                    {
                        attachmentPartType = IMMConstants.CT_IMAGE_GIF;
                    }
                    else if (fileExtension.toLowerCase().equals("tiff"))
                    {
                        attachmentPartType = IMMConstants.CT_IMAGE_TIFF;
                    }
                    else if (fileExtension.toLowerCase().equals("png"))
                    {
                        attachmentPartType = IMMConstants.CT_IMAGE_PNG;
                    }
                    else if (fileExtension.toLowerCase().equals("wbmp"))
                    {
                        attachmentPartType = IMMConstants.CT_IMAGE_WBMP;
                    }
                    else if (fileExtension.toLowerCase().equals("avi"))
                    {
                        attachmentPartType = IMMConstants.CT_VIDEO_AVI;
                    }
                    else if (fileExtension.toLowerCase().equals("mp4"))
                    {
                        attachmentPartType = IMMConstants.CT_VIDEO_MP4;
                    }
                    else if (fileExtension.toLowerCase().equals("m4v"))
                    {
                        attachmentPartType = IMMConstants.CT_VIDEO_MP4;
                    }
                    else if (fileExtension.toLowerCase().equals("mpg"))
                    {
                        attachmentPartType = IMMConstants.CT_VIDEO_MPEG;
                    }
                    
                    // Adds image content
                    MMContent part1 = new MMContent();
                    part1.setContent(fileBytes, 0, fileBytes.length);
                    part1.setContentId("<0>");
                    part1.setType(attachmentPartType);
                    mm.addContent(part1);

                    partId++;
                }

                // Adds text content
                for (String messageText : bodyParts)
                {                        
                    MMContent part2 = new MMContent();
                    byte[] textBytes = messageText.getBytes("UTF-8");
                    part2.setContent(textBytes, 0, textBytes.length);
                    part2.setContentId("<" + String.valueOf(partId) + ">");
                    part2.setType(IMMConstants.CT_TEXT_PLAIN);
                    mm.addContent(part2);
                    partId++;
                }

                MMEncoder encoder = new MMEncoder();
                encoder.setMessage(mm);

                encoder.encodeMessage();
                byte[] out = encoder.getMessage();

                MMSender sender = new MMSender();                    
                sender.setMMSCURL(MMSCenterUrl);
                sender.addHeader("X-NOKIA-MMSC-Charging", "100");

    
                MMResponse mmResponse = sender.send(out, true, MMSProxy, MMSPort);

                // Enumeration keys = mmResponse.getHeadersList();
                // while (keys.hasMoreElements()){
                //     String key = (String) keys.nextElement();
                //     String value = (String) mmResponse.getHeaderValue(key);
                //     app.log(key + ": " + value);
                // }
                
                Boolean wasSentSuccessfully = false;
                if (mmResponse.getResponseCode() == 200)
                {
                    // 200 Successful, disconnect and reset.
                    //endMmsConnectivity();                        
                    
                    wasSentSuccessfully = true;
                    app.log("MMS Message sent to " + toNumber);
                    //app.log("Response code: " + mmResponse.getResponseCode() + " " + mmResponse.getResponseMessage());
                }
                else
                {
                    // kill dew :D hhaha
                    app.log("MMS Message wasn't sent");                        
                }
                                    
                //wifi.setWifiEnabled(true);

                //endMmsConnectivity();
                // waitForWifiToBeActive();
                //releaseWakeLock();
                //waitForActiveConnectivity();

                //app.switchingNetworkBecauseOfMMS = false;

                // Intent intent = new Intent(schedule.packageName + App.OUTGOING_SMS_INTENT_SUFFIX, this.getUri());
                // app.sendBroadcast(intent, "android.permission.SEND_SMS"); 

                if (wasSentSuccessfully)
                {
                    app.outbox.messageSent(this);
                    // this.setProcessingState(ProcessingState.Sent);
                    // app.outbox.deleteMessage(this);
                    //app.markPollComplete();
                    // app.outbox.deleteMessage(this);
                    // app.outbox.maybeDequeueMessage();
                }
                else
                {
                    app.outbox.messageFailed(this, "MMS Failed with responseCode " + mmResponse.getResponseCode());
                }

                // String someRandomName = UUID.randomUUID().toString().substring(0, 8); 
                // String myFileFolder = Environment.getExternalStorageDirectory() + "/MMSDocuments"; 
                // String myFileImage = someRandomName + "." + fileExtension;
                // File imageFile = SavePhotoTask(imageBytes, myFileFolder, myFileImage);

                //Uri attachedFileUri = Uri.fromFile(imageFile);
                
                // com.klinker.android.send_message.Settings sendSettings = new com.klinker.android.send_message.Settings();
                // sendSettings.setMmsc(getAPNMMSC());
                // sendSettings.setProxy(getAPNProxy());
                // sendSettings.setPort(getAPNPort());
                // sendSettings.setUseSystemSending(true);

                // com.klinker.android.send_message.Transaction transaction = new com.klinker.android.send_message.Transaction(cntext, sendSettings);

                // StringBuilder sb = new StringBuilder();
                // for (String s : bodyParts)
                // {
                //     sb.append(s);
                //     sb.append("\t");
                // }

                // com.klinker.android.send_message.Message mMessage = new com.klinker.android.send_message.Message(sb.toString(), getTo());
                // BitmapFactory.Options options = new BitmapFactory.Options();
                // mMessage.setImage(BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options));
                // //mMessage.setType(com.klinker.android.send_message.Message.TYPE_SMSMMS);
                
                // transaction.sendNewMessage(mMessage, Transaction.NO_THREAD_ID);
                
            }
            else
            {
                app.log("(base64 string was empty)");
                app.outbox.messageFailed(this, "Failed to send MMS message: no valid image exists in base64 or in a Url");
            }
        }
        catch (Exception ex)
        {
            app.log("(" + ex.toString() + ")");
            app.outbox.messageFailed(this, "Failed to send MMS message: " + ex.getMessage());
        }
    }

    private void waitForActiveConnectivity()
    {
        Boolean finished = false;
        while(!finished)
        {
            try
            {
                InetAddress ipAddr = InetAddress.getByName("google.com"); 
                //You can replace it with your name
                finished = !ipAddr.equals("");        
            }
            catch (Exception e)
            {

            }
            
            if (!finished)
            {
                app.log("(waiting for Network Connectivity)");
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

    private void beginMmsConnectivity(Integer port)
    {
        mConnMgr.startUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE, "enableMMS"); //ConnectivityManager.TYPE_MOBILE
        mConnMgr.requestRouteToHost(ConnectivityManager.TYPE_MOBILE, port);

        // if (!mConnMgr.getNetworkInfo(ConnectivityManager.TYPE_MOBILE_MMS).isAvailable())
        // {
        //     //throw new NoConnectivityException(mContext.getString(R.string.not_available));
        // }
        //int count = 0;
        NetworkInfo info = mConnMgr.getNetworkInfo(ConnectivityManager.TYPE_MOBILE); //ConnectivityManager.TYPE_MOBILE
        while (!info.isConnected())
        {
            try 
            {
                Thread.sleep(800);
            }
            catch (InterruptedException ex)
            {
                //Thread.currentThread().interrupt();
            }

            info = mConnMgr.getNetworkInfo(ConnectivityManager.TYPE_MOBILE); //ConnectivityManager.TYPE_MOBILE
                //throw new ConnectTimeoutException(mContext.getString(R.string.failed_to_connect));
        }

        // NetworkRequest.Builder builder = new NetworkRequest.Builder();
        // builder.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
        // builder.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);

        // NetworkRequest networkRequest = builder.build();
        // mConnMgr.requestNetwork(networkRequest, new ConnectivityManager.NetworkCallback() {

        //     @Override
        //     public void onAvailable(Network network) {
        //         super.onAvailable(network);
        //         dialog.dismiss();
        //         sendNormalMms();
        //     }
        // });

        // mConnMgr.startUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE, "enableMMS");
        // mConnMgr.setNetworkPreference(ConnectivityManager.TYPE_MOBILE);

        // try
        // {
        //     final Method method = mConnMgr.getClass().getMethod(
        //             "startUsingNetworkFeature", Integer.TYPE, String.class);
        //     if (method != null)
        //     {
        //         Integer results = (Integer) method.invoke(
        //             mConnMgr, ConnectivityManager.TYPE_MOBILE, "enableMMS");
        //     }
        // }
        // catch (Exception ex)
        // {

        // }
    }

    private void endMmsConnectivity()
    {
        mConnMgr.stopUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE, "enableMMS");

        NetworkInfo info = mConnMgr.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        while (!info.isConnected())
        {
            try 
            {
                Thread.sleep(800);
            }
            catch (InterruptedException ex)
            {
                //Thread.currentThread().interrupt();
            }

            info = mConnMgr.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                //throw new ConnectTimeoutException(mContext.getString(R.string.failed_to_connect));
        }


        // mConnMgr.startUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE, "enableMMS");
        // mConnMgr.setNetworkPreference(ConnectivityManager.TYPE_MOBILE);

        // try
        // {
        //     final Method method = mConnMgr.getClass().getMethod(
        //             "stopUsingNetworkFeature", Integer.TYPE, String.class);
        //     if (method != null)
        //     {
        //         Integer results = (Integer) method.invoke(
        //             mConnMgr, ConnectivityManager.TYPE_MOBILE, "enableMMS");
        //     }
        // }
        // catch (Exception ex)
        // {
            
        // }
    }

    private void waitForMobileNetworkToBeActive()
    {
        NetworkInfo info = mConnMgr.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        Boolean finished = false;

        if (!info.isConnected())
        {
            app.switchingNetworkBecauseOfMMS = true;
            WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            wifi.setWifiEnabled(false);    
        }
        else
        {
            finished = true;
        }

        while(!finished)
        {
            NetworkInfo activeNetwork = mConnMgr.getActiveNetworkInfo();

            finished = activeNetwork != null && activeNetwork.getState() == NetworkInfo.State.CONNECTED && activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE;
            
            if (!finished)
            {
                //app.log("(waiting for MOBILE Connectivity)");
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

    private void acquireWakeLock() {
		// It's okay to double-acquire this because we are not using it
		// in reference-counted mode.
		mWakeLock.acquire();
	}

	private void releaseWakeLock() {
		// Don't release the wake lock if it hasn't been created and acquired.
		if (mWakeLock != null && mWakeLock.isHeld()) {
			mWakeLock.release();
		}
	}
    
    public File SavePhotoTask(byte [] jpeg, String imagesFolder, String ImagName)
    {       
        File directory = new File(imagesFolder);
        File photo = null;

        try
        {
            boolean isPresent = false;
            if (!directory.exists()) {
                isPresent = directory.mkdirs();
            }
            else
            {
                isPresent = true;
            }
            
            if (isPresent)
            {
                photo = new File(directory.getAbsolutePath(), ImagName);

                FileOutputStream fos = new FileOutputStream(photo.getPath());
                fos.write(jpeg);
                fos.close();
            }
        }
        catch(Exception e)
        {
            
        }

        return photo;
    }
    
    public String getDisplayType()
    {
        return getMessageAttachmentMessageType(); //"SMS";
    }

    @Override
    public void validate() throws ValidationException
    {                        
        super.validate();
                
        String to = getTo();
        if (to == null || to.length() == 0)
        {
            throw new ValidationException("Destination address is empty");
        }                        
        
        if (!app.isForwardablePhoneNumber(to))
        {
            if (app.isTestMode() && app.autoAddTestNumber())
            {
                app.addTestPhoneNumber(to);
            }
            else
            {
                // this is mostly to prevent accidentally sending real messages to
                // random people while testing...
                throw new ValidationException("Destination address is not allowed");
            }
        }
        
        String messageBody = getMessageBody();
        
        if (messageBody == null || messageBody.length() == 0)
        {
            throw new ValidationException("Message body is empty");
        }        
        
        int numParts = getNumParts();

        if (numParts > App.OUTGOING_SMS_MAX_COUNT)
        {
            throw new ValidationException("Message has too many parts ("+numParts+")");
        }
    }
}
