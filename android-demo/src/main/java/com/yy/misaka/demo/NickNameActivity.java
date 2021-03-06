package com.yy.misaka.demo;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import android.util.Log;

public class NickNameActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i("DemoLogger", "NickNameActivity onCreate");
        setContentView(R.layout.activity_nick);
        init();
    }

    private void init() {
        final EditText etNickName = (EditText) findViewById(R.id.et_nick_nickname);
        final EditText etHost = (EditText) findViewById(R.id.et_host);
        findViewById(R.id.btn_nick_enter).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String nickname = String.valueOf(etNickName.getText()).trim();
                String host = String.valueOf(etHost.getText()).trim();
                if (nickname.equals("")) {
                    Toast.makeText(NickNameActivity.this, "Nickname can't be null", Toast.LENGTH_SHORT).show();
                } else {
                    ChatActivity.launch(NickNameActivity.this, nickname, host);
                }
            }
        });

        findViewById(R.id.btn_http_enter).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String host = String.valueOf(etHost.getText()).trim();
                HttpActivity.launch(NickNameActivity.this, host);
            }
        });
    }

}
