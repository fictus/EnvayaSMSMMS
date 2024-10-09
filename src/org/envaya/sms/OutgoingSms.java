package org.envaya.sms;

import android.content.Context;
import android.os.Environment;
import android.content.Intent;
import android.net.Uri;
import android.telephony.SmsManager;
import java.util.ArrayList;
import java.util.UUID;
import java.io.File;
import java.io.FileOutputStream;

public class OutgoingSms extends OutgoingMessage {
    public OutgoingSms(App app)
    {
        super(app);
    }    
    
    public String getMessageType()
    {
        return App.MESSAGE_TYPE_SMS;
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

        if (messageType.contains("MMS"))
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

        if (bouncedFromMMS == true)
        {
            app.log("(bounced from MMS but sent via SMS)");
        }

        intent.putExtra(App.OUTGOING_SMS_EXTRA_BODY, bodyParts);
        
        app.sendBroadcast(intent, "android.permission.SEND_SMS"); 
    }

    public void SendMMSBroadcast(ScheduleInfo schedule)
    {
        Boolean mmsSent = false; 
        ArrayList<String> bodyParts = getBodyParts();
        int numParts = bodyParts.size();
        if (numParts > 1)
        {
            app.log("(Multipart message with "+numParts+" parts)");
        }

        try
        {
            String fileExtension = getMessageAttachmentFileExtension();
            String base64string = getMessageAttachmentFileBase64();

            if (base64string != "")
            {
                if (fileExtension == "")
                {
                    fileExtension = "jpg";
                }

                byte[] imageBytes = null;
                
                try
                {
                    imageBytes = org.envaya.sms.Base64Coder.decode(base64string);
                }
                catch (Exception ex)
                {
                    app.log("(failed to decode base64 string to bytes)");
                }
                
                if (imageBytes != null)
                {
                    Context cntext = app.getApplicationContext();
                    String someRandomName = UUID.randomUUID().toString().substring(0, 8); 
                    String myFileFolder = Environment.getExternalStorageDirectory() + "/MMSDocuments"; 
                    String myFileImage = someRandomName + "." + fileExtension;
                    File imageFile = SavePhotoTask(imageBytes, myFileFolder, myFileImage);

                    app.log("(iamge folder: " + myFileFolder + ")");
                    app.log("(iamge name: " + myFileImage + ")");
                    app.log("(iamge exists: " + imageFile.exists() + ")");

                    if (imageFile != null)
                    {
                        Uri attachedFileUri = Uri.fromFile(imageFile);

                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        intent.setData(Uri.parse("mmsto:" + getTo()));
                        intent.putExtra(Intent.EXTRA_STREAM, attachedFileUri);                        
                        intent.putExtra("sms_body", "sent with an image MMS " + myFileFolder + " " + myFileImage); //bodyParts
                        intent.setType("image/jpeg");

                        //app.sendBroadcast(Intent.createChooser(intent, "Send"), "android.permission.SEND_SMS");
                        //app.sendBroadcast(intent, "android.permission.SEND_SMS");

                        //app.startActivity(Intent.createChooser(intent, "Send"));
                        //app.startActivity(intent);

                        if (intent.resolveActivity(app.getPackageManager()) != null)
                        {
                            app.startActivity(intent);
                        }

                        mmsSent = true;
                    }
                    else
                    {
                        app.log("(imageFile was null)");
                    }
                }
                else
                {
                    app.log("(image bytes was null)");
                }
            }
            else
            {
                app.log("(base64 string was empty)");
            }
        }
        catch (Exception ex)
        {
            app.log("(" + ex.toString() + ")");
        }

        if (mmsSent == false)
        {
            SendSMSBroadcast(schedule, mmsSent == false);
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
