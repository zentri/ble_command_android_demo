/*
 * Copyright (C) 2015, Zentri, Inc. All Rights Reserved.
 *
 * The Zentri BLE Android Libraries and Zentri BLE example applications are provided free of charge
 * by Zentri. The combined source code, and all derivatives, are licensed by Zentri SOLELY for use
 * with devices manufactured by Zentri, or devices approved by Zentri.
 *
 * Use of this software on any other devices or hardware platforms is strictly prohibited.
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR AS IS AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.zentri.zentri_ble_command_demo;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;

public class SplashScreen extends Activity
{
    private static final long SPLASH_SCREEN_TIMEOUT_MS = 3000;

    private Handler mHandler;
    private Runnable mTimeoutTask;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_splash_screen);

        TextView versionTextView = (TextView) findViewById(R.id.version);

        String versionString = String.format("Version %s", Util.getAppVersion(this));
        versionTextView.setText(versionString);

        mHandler = new Handler();
        mTimeoutTask = new Runnable()
        {
            @Override
            public void run()
            {
                Intent connectIntent = new Intent(SplashScreen.this, MainActivity.class);
                SplashScreen.this.startActivity(connectIntent);
                SplashScreen.this.finish();
            }
        };

        final TextView link_company = (TextView) findViewById(R.id.company_link);
        link_company.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                goToWebsite(getString(R.string.website_company));
            }
        });

        final TextView link_docs = (TextView) findViewById(R.id.docs_link);
        link_docs.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                goToWebsite(getString(R.string.website_docs_link));
            }
        });
    }



    private void goToWebsite(String url)
    {
        Intent websiteIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        SplashScreen.this.startActivity(websiteIntent);
    }

    @Override
    public void onStart()
    {
        super.onStart();

        mHandler.postDelayed(mTimeoutTask, SPLASH_SCREEN_TIMEOUT_MS);
    }

    @Override
    public void onStop()
    {
        super.onStop();

        mHandler.removeCallbacks(mTimeoutTask);//stop timer if going to link
    }
}
