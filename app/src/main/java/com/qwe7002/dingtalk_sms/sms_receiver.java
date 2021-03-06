package com.qwe7002.dingtalk_sms;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Telephony;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.PermissionChecker;

import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static android.content.Context.MODE_PRIVATE;
import static androidx.core.content.PermissionChecker.checkSelfPermission;

public class sms_receiver extends BroadcastReceiver {
    public void onReceive(final Context context, Intent intent) {
        try {
            System.out.println("收到信息:");
            Log.d(public_func.log_tag, "onReceive: " + intent.getAction());
            Bundle extras = intent.getExtras();
            if (extras == null) {
                Log.d(public_func.log_tag, "reject: Error Extras");
                return;
            }

            final boolean is_default = Telephony.Sms.getDefaultSmsPackage(context).equals(context.getPackageName());
            if ("android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction()) && is_default) {
                //When it is the default application, it will receive two broadcasts.
                Log.i(public_func.log_tag, "reject: android.provider.Telephony.SMS_RECEIVED");
                return;
            }

            final SharedPreferences sharedPreferences = context.getSharedPreferences("data", MODE_PRIVATE);
//        if (!sharedPreferences.getBoolean("initialized", false)) {
//            public_func.write_log(context, "Receive SMS:Uninitialized");
//            return;
//        }
            //String bot_token = sharedPreferences.getString("bot_token", "");
            String dual_sim = "";
            SubscriptionManager manager = SubscriptionManager.from(context);
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                if (manager.getActiveSubscriptionInfoCount() >= 2) {
                    int slot = extras.getInt("slot", -1);
                    if (slot != -1) {
                        String display_name = public_func.get_sim_name_title(context, sharedPreferences, slot);
                        dual_sim = "SIM" + (slot + 1) + display_name + " ";
                    }
                }
            }
            final int sub = extras.getInt("subscription", -1);
            Object[] pdus = (Object[]) extras.get("pdus");
            assert pdus != null;
            final SmsMessage[] messages = new SmsMessage[pdus.length];
            for (int i = 0; i < pdus.length; i++) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    String format = extras.getString("format");
                    messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i], format);
                } else {
                    messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i]);
                }
                if (is_default) {
                    ContentValues values = new ContentValues();
                    values.put(Telephony.Sms.ADDRESS, messages[i].getOriginatingAddress());
                    values.put(Telephony.Sms.BODY, messages[i].getMessageBody());
                    values.put(Telephony.Sms.SUBSCRIPTION_ID, String.valueOf(sub));
                    values.put(Telephony.Sms.READ, "1");
                    context.getContentResolver().insert(Telephony.Sms.CONTENT_URI, values);
                }
            }
            if (messages.length == 0) {
                public_func.write_log(context, "Message length is equal to 0");
                return;
            }
            StringBuilder msgBody = new StringBuilder();
            for (SmsMessage item : messages) {
                msgBody.append(item.getMessageBody());
            }
            String msg_address = messages[0].getOriginatingAddress();

            final request_json request_body = new request_json();
            String display_address = msg_address;
            if (display_address != null) {
                String display_name = public_func.get_contact_name(context, display_address);
                if (display_name != null) {
                    display_address = display_name + "(" + display_address + ")";
                }
            }
            String Content =  msgBody.toString();
            assert msg_address != null;
            if (checkSelfPermission(context, Manifest.permission.SEND_SMS) == PermissionChecker.PERMISSION_GRANTED) {
                if (msg_address.equals(sharedPreferences.getString("trusted_phone_number", null))) {
                    String[] msg_send_list = msgBody.toString().split("\n");
                    String msg_send_to = public_func.get_send_phone_number(msg_send_list[0]);
                    if (msg_send_to.equals("restart-service")) {
                        public_func.stop_all_service(context.getApplicationContext());
                        public_func.start_service(context.getApplicationContext(), sharedPreferences.getBoolean("battery_monitoring_switch", false));
                        Content = context.getString(R.string.system_message_head) + "\n" + context.getString(R.string.restart_service);
                    }
                    if (public_func.is_numeric(msg_send_to) && msg_send_list.length != 1) {
                        StringBuilder msg_send_content = new StringBuilder();
                        for (int i = 1; i < msg_send_list.length; i++) {
                            if (msg_send_list.length != 2 && i != 1) {
                                msg_send_content.append("\n");
                            }
                            msg_send_content.append(msg_send_list[i]);
                        }
                        public_func.send_sms(context, msg_send_to, msg_send_content.toString(), sub);
                        return;
                    }
                }
            }
            JsonObject object = new JsonObject();
            object.addProperty("content", Content);
            request_body.text = object;
            DingAddress address = getWebHook(Content, context);
            request_body.at = getAtPhones(msgBody.toString(),address!=null?address.tel:"");
            Gson gson = new Gson();
            String request_body_raw = gson.toJson(request_body);

            System.out.println(request_body.at);

            RequestBody body = RequestBody.create(request_body_raw, public_func.JSON);
            OkHttpClient okhttp_client = public_func.get_okhttp_obj();
            okhttp_client.retryOnConnectionFailure();
            okhttp_client.connectTimeoutMillis();
            System.out.println("utl:"+(address==null?"":address.webHook));
            Request request = new Request.Builder().url(address==null?"":address.webHook).method("POST", body).build();
            Call call = okhttp_client.newCall(request);
            String finalContent = Content;
            System.out.println(request_body_raw);
            call.enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    try {
                        String error_message = "SMS forwarding failed:" + e.getMessage();
                        public_func.write_log(context, error_message);
                        public_func.write_log(context, "message body:" + request_body.text);
                        if (checkSelfPermission(context, Manifest.permission.SEND_SMS) == PermissionChecker.PERMISSION_GRANTED) {
                            if (sharedPreferences.getBoolean("fallback_sms", false)) {
                                String msg_send_to = sharedPreferences.getString("trusted_phone_number", null);
                                if (msg_send_to != null) {
                                    public_func.send_fallback_sms(msg_send_to, finalContent, sub);
                                }
                            }
                        }
                    }catch (Exception e2){
                        e2.printStackTrace();
                    }
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.code() != 200) {
                        try {
                            assert response.body() != null;
                            String error_message = "SMS forwarding failed:" + Objects.requireNonNull(response.body()).string();
                            public_func.write_log(context, error_message);
                            public_func.write_log(context, "message body:" + request_body.text);
                        }catch (Exception e3){
                            e3.printStackTrace();
                        }
                    }
                }
            });
        }catch (Exception e){
            e.printStackTrace();
        }
    }


    private DingAddress getWebHook(String content, final Context context) {
        final SharedPreferences sharedPreferences = context.getSharedPreferences("data", MODE_PRIVATE);

        String config = sharedPreferences.getString("config", null);
        if (config == null || config == "") {
            System.out.println("错了");
            return null;
        }
        JsonArray result_obj = new JsonParser().parse(config).getAsJsonArray();
        for (int i = 0; i < result_obj.size(); i++) {
            JsonObject object = result_obj.get(i).getAsJsonObject();
            String keyWord = object.get("KEY_WORD").getAsString();
            String webHook = object.get("WEB_HOOK").getAsString();
            String tel = object.get("TEL") == null || object.get("TEL") instanceof JsonNull
                    ?"":object.get("TEL").getAsString();
            if(content.indexOf(keyWord)!=-1){
                DingAddress dingAddress = new DingAddress();
                dingAddress.webHook = webHook;
                dingAddress.tel = tel;
                return dingAddress;
            }
        }
        return null;
    }

    class DingAddress{
        private String webHook;
        private String tel;
    }

    private static JsonObject getAtPhones(String content,String defaultAddress){
        JsonObject object = new JsonObject();
        Pattern pattern = Pattern.compile("1\\d{10}");
        Matcher matcher = pattern.matcher(content);
        JsonArray phones = new JsonArray();
        while(matcher.find()) {
            //每一个符合正则的字符串
            String e = matcher.group();
            phones.add(e);
        }
        if(phones.size() == 0){
            if(defaultAddress!=null && !defaultAddress.trim().equals("")){
                object.addProperty("isAtAll",false);
                object.add("atMobiles",transStringToJsonArray(defaultAddress) );
            }else {
                object.addProperty("isAtAll", true);
            }
        }else{
            object.addProperty("isAtAll",false);
            object.add("atMobiles",phones);
        }
        return object;
    }

    private static JsonArray transStringToJsonArray(String str){
        String[] defaultAddressArray  = str.split(",");
        List defaultAddressList = Arrays.asList(defaultAddressArray);
        JsonArray array = new JsonArray();
        for(Iterator iterator = defaultAddressList.iterator();iterator.hasNext();){
            String tel = (String)iterator.next();
            array.add(tel);
        }
        return array;
    }

    public static void main(String []a){
        String abc = "sdfdfdfd1565927677015659276770afdddfd";
        //JsonObject s = getAtPhones(abc);
        //System.out.println(s);
    }
}



