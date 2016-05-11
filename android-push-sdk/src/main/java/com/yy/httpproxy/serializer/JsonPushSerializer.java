package com.yy.httpproxy.serializer;

import com.google.gson.Gson;
import com.yy.httpproxy.serializer.PushSerializer;

import java.io.UnsupportedEncodingException;

/**
 * Created by xuduo on 11/23/15.
 */
public class JsonPushSerializer implements PushSerializer {

    private Gson gson = new Gson();

    @Override
    public Object toObject(String topic, Object clazz, byte[] body) {
        if (body == null || clazz == null) {
            return null;
        }
        try {
            String bodyStr = new String(body, "UTF-8");
            if (bodyStr.isEmpty()) {
                return null;
            }
            return gson.fromJson(bodyStr, (Class) clazz);
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }
}
