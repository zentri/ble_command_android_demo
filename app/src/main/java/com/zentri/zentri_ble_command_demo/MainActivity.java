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

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;

import android.bluetooth.BluetoothAdapter;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import com.zentri.zentri_ble_command.ErrorCode;
import com.zentri.zentri_ble_command.ZentriOSBLEManager;

import java.util.regex.Pattern;

import fr.castorflex.android.smoothprogressbar.SmoothProgressBar;


public class MainActivity extends AppCompatActivity
{
    private static final String TAG = MainActivity.class.getSimpleName();

    private static final String LOC_PERM = Manifest.permission.ACCESS_COARSE_LOCATION;
    private static final int BLE_ENABLE_REQ_CODE = 1;
    private static final int LOC_ENABLE_REQ_CODE = 2;

    private static final long SCAN_PERIOD = 30000;
    private static final long CONNECT_TIMEOUT_MS = 10000;

    private static final String PATTERN_MAC_ADDRESS = "(\\p{XDigit}{2}:){5}\\p{XDigit}{2}";

    private SmoothProgressBar mScanProgressBar;
    private Dialog mConnectProgressDialog;
    private DeviceList mDeviceList;
    private Button mScanButton;

    private Handler mHandler;
    private Runnable mStopScanTask;
    private Runnable mConnectTimeoutTask;

    private ZentriOSBLEManager mZentriOSBLEManager;
    private boolean mConnecting = false;
    private boolean mConnected = false;

    private String mCurrentDeviceName;

    private ServiceConnection mConnection;
    private ZentriOSBLEService mService;
    private boolean mBound = false;

    private LocalBroadcastManager mLocalBroadcastManager;
    private BroadcastReceiver mBroadcastReceiver;
    private IntentFilter mReceiverIntentFilter;

    private Dialog mLocationEnableDialog;
    private Dialog mPermissionRationaleDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        toolbar.setTitle(R.string.app_name_short);

        initProgressBar();
        initScanButton();
        initDeviceList();
        initBroadcastManager();
        initServiceConnection();
        initBroadcastReceiver();
        initReceiverIntentFilter();

        startService(new Intent(this, ZentriOSBLEService.class));

        mHandler = new Handler();

        mStopScanTask = new Runnable()
        {
            @Override
            public void run()
            {
                stopScan();
            }
        };

        mConnectTimeoutTask = new Runnable()
        {
            @Override
            public void run()
            {
                runOnUiThread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        dismissDialog(mConnectProgressDialog);
                        showErrorDialog(R.string.con_timeout_message, false);
                        mConnecting = false;
                        mConnected = false;
                        if(mZentriOSBLEManager != null && mZentriOSBLEManager.isConnected())
                        {
                            mZentriOSBLEManager.disconnect(ZentriOSBLEService.DISABLE_TX_NOTIFY);
                        }
                    }
                });
            }
        };
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        // Handle item selection
        switch (item.getItemId())
        {
            case R.id.action_about:
                openAboutDialog();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onStart()
    {
        super.onStart();

        mDeviceList.clear();
        mConnected = false;
        mConnecting = false;

        Intent intent = new Intent(this, ZentriOSBLEService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        mLocalBroadcastManager.registerReceiver(mBroadcastReceiver, mReceiverIntentFilter);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
    }

    @Override
    protected void onPause()
    {
        super.onPause();
    }

    @Override
    protected void onStop()
    {
        mHandler.removeCallbacks(mStopScanTask);

        //ensure dialogs are closed
        dismissDialog(mConnectProgressDialog);
        dismissDialog(mLocationEnableDialog);
        dismissDialog(mPermissionRationaleDialog);

        if (mBound)
        {
            mLocalBroadcastManager.unregisterReceiver(mBroadcastReceiver);
            unbindService(mConnection);
            mBound = false;
        }

        super.onStop();
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();

        stopService(new Intent(this, ZentriOSBLEService.class));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (requestCode == BLE_ENABLE_REQ_CODE)
        {
            mService.initTruconnectManager();//try again
            if (mZentriOSBLEManager.isInitialised())
            {
                if (requirementsMet())
                {
                    startScan();
                }
            }
            else
            {
                showErrorDialog(R.string.init_fail_msg, true);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults)
    {
        switch (requestCode)
        {
            case LOC_ENABLE_REQ_CODE:
            {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED)
                {
                    if (requirementsMet())
                    {
                        startScan();
                    }
                }
                else
                {
                    //show unrecoverable error dialog
                    showErrorDialog(R.string.error_permission_denied, true);
                }
            }
        }
    }

    private void initProgressBar()
    {
        mScanProgressBar = (SmoothProgressBar) findViewById(R.id.progressBar);
        mScanProgressBar.setVisibility(View.VISIBLE);
    }

    private void initScanButton()
    {
        mScanButton = (Button) findViewById(R.id.scanButton);
        mScanButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                mDeviceList.clear();
                startScan();
            }
        });
    }

    private void initDeviceList()
    {
        ListView deviceListView = (ListView) findViewById(R.id.listView);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.listitem, R.id.textView);

        initialiseListviewListener(deviceListView);
        mDeviceList = new DeviceList(adapter, deviceListView);
    }

    private void initServiceConnection()
    {
        mConnection = new ServiceConnection()
        {
            @Override
            public void onServiceConnected(ComponentName className, IBinder service)
            {
                ZentriOSBLEService.LocalBinder binder = (ZentriOSBLEService.LocalBinder) service;
                mService = binder.getService();
                mBound = true;

                mZentriOSBLEManager = mService.getManager();

                //if requirements not met, action will already be taken
                if (requirementsMet())
                {
                    startScan();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName arg0)
            {
                mBound = false;
            }
        };
    }

    private void initBroadcastReceiver()
    {
        mBroadcastReceiver = new BroadcastReceiver()
        {
            @Override
            public void onReceive(Context context, Intent intent)
            {
                // Get extra data included in the Intent
                String action = intent.getAction();

                switch (action)
                {
                    case ZentriOSBLEService.ACTION_SCAN_RESULT:
                        String name = ZentriOSBLEService.getData(intent);

                        //dont show devices with no name (mac addresses)
                        if (name != null && !Pattern.matches(PATTERN_MAC_ADDRESS, name))
                        {
                            addDeviceToList(name);
                        }
                        break;

                    case ZentriOSBLEService.ACTION_CONNECTED:
                        String deviceName = ZentriOSBLEService.getData(intent);

                        mConnected = true;
                        mHandler.removeCallbacks(mConnectTimeoutTask);//cancel timeout timer
                        dismissDialog(mConnectProgressDialog);
                        showToast("Connected to " + deviceName, Toast.LENGTH_SHORT);
                        Log.d(TAG, "Connected to " + deviceName);

                        startDeviceInfoActivity();
                        break;

                    case ZentriOSBLEService.ACTION_DISCONNECTED:
                        mConnected = false;
                        break;

                    case ZentriOSBLEService.ACTION_ERROR:
                        ErrorCode errorCode = ZentriOSBLEService.getErrorCode(intent);
                        //handle errors
                        if (errorCode == ErrorCode.CONNECT_FAILED)
                        {
                            if (!mConnected && mConnecting)
                            {
                                mConnecting = false;//allow another attempt to connect
                                dismissDialog(mConnectProgressDialog);
                            }
                            else
                            {
                                mConnected = false;
                            }

                            showErrorDialog(R.string.con_err_message, false);
                        }
                        break;
                }
            }
        };
    }

    public void initBroadcastManager()
    {
        mLocalBroadcastManager = LocalBroadcastManager.getInstance(getApplicationContext());
    }

    public void initReceiverIntentFilter()
    {
        mReceiverIntentFilter = new IntentFilter();
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_SCAN_RESULT);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_CONNECTED);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_DISCONNECTED);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_ERROR);
    }

    private void startBLEEnableIntent()
    {
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableBtIntent, BLE_ENABLE_REQ_CODE);
    }

    private void initialiseListviewListener(ListView listView)
    {
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener()
        {
            @Override
            public void onItemClick(AdapterView<?> parent, View view,
                                    int position, long id)
            {
                mCurrentDeviceName = mDeviceList.get(position);

                if (!mConnecting)
                {
                    mConnecting = true;

                    stopScan();
                    Log.d(TAG, "Connecting to BLE device " + mCurrentDeviceName);
                    mZentriOSBLEManager.connect(mCurrentDeviceName);

                    showConnectingDialog();

                    mHandler.postDelayed(mConnectTimeoutTask, CONNECT_TIMEOUT_MS);
                }
            }
        });
    }

    private void startScan()
    {
        if (mZentriOSBLEManager != null)
        {
            runOnUiThread(new Runnable()
              {
                  @Override
                  public void run()
                  {
                      mZentriOSBLEManager.startScan();
                  }
              });
            startProgressBar();
            disableScanButton();
            mHandler.postDelayed(mStopScanTask, SCAN_PERIOD);
        }
    }

    private void stopScan()
    {
        if (mZentriOSBLEManager != null && mZentriOSBLEManager.stopScan())
        {
            stopProgressBar();
            enableScanButton();
        }
    }

    private void showConnectingDialog()
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mConnectProgressDialog = Util.showProgressDialog(MainActivity.this,
                        R.string.progress_title,
                        R.string.progress_message);
            }
        });
    }

    private void startLocationEnableIntent()
    {
        Log.d(TAG, "Directing user to enable location services");
        Intent enableBtIntent = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        startActivityForResult(enableBtIntent, LOC_ENABLE_REQ_CODE);
    }

    private void showLocationEnableDialog()
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mLocationEnableDialog = new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.loc_enable_title)
                        .setMessage(R.string.loc_enable_msg)
                        .setPositiveButton(R.string.settings, new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialog, int which)
                            {
                                startLocationEnableIntent();
                            }
                        })
                        .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialog, int which)
                            {
                                dialog.dismiss();
                                showErrorDialog(R.string.error_loc_disabled, true);
                            }
                        }).create();
                mLocationEnableDialog.show();
                Resources res = getResources();
                Util.setTitleColour(res, mLocationEnableDialog, R.color.zentri_orange);
                Util.setDividerColour(res, mLocationEnableDialog, R.color.transparent);
            }
        });
    }

    private boolean requestPermissions()
    {
        boolean result = true;

        if (ContextCompat.checkSelfPermission(MainActivity.this, LOC_PERM)
                != PackageManager.PERMISSION_GRANTED)
        {
            result = false;

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(MainActivity.this, LOC_PERM))
            {

                // Show an explanation to the user
                showPermissionsRationaleDialog();
            }
            else
            {
                ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{LOC_PERM},
                        LOC_ENABLE_REQ_CODE);
            }
        }

        return result;
    }

    /**
     * Checks if requirements for this app to run are met.
     * @return true if requirements to run are met
     */
    private boolean requirementsMet()
    {
        boolean reqMet = false;

        if (!mZentriOSBLEManager.isInitialised())
        {
            startBLEEnableIntent();
        }
        else if (!requestPermissions())
        {
        }
        else if (!Util.isLocationEnabled(this))
        {
            showLocationEnableDialog();
        }
        else
        {
            reqMet = true;
        }

        return reqMet;
    }

    private void showPermissionsRationaleDialog()
    {
        mPermissionRationaleDialog = new AlertDialog.Builder(MainActivity.this)
                .setTitle(R.string.permission_rationale_title)
                .setMessage(R.string.permission_rationale_msg)
                .setPositiveButton(R.string.enable, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        dialog.dismiss();
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{LOC_PERM},
                                LOC_ENABLE_REQ_CODE);
                    }
                })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        dialog.dismiss();
                        showErrorDialog(R.string.error_permission_denied, true);
                    }
                }).create();

        mPermissionRationaleDialog.show();
        Resources res = getResources();
        Util.setTitleColour(res, mPermissionRationaleDialog, R.color.zentri_orange);
        Util.setDividerColour(res, mPermissionRationaleDialog, R.color.transparent);
    }

    private void startDeviceInfoActivity()
    {
        startActivity(new Intent(getApplicationContext(), DeviceInfoActivity.class));
    }

    private void startProgressBar()
    {
        updateProgressBar(true);
    }

    private void stopProgressBar()
    {
        updateProgressBar(false);
    }

    private void enableScanButton()
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mScanButton.setEnabled(true);
            }
        });
    }

    private void disableScanButton()
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mScanButton.setEnabled(false);
            }
        });
    }

    private void showToast(final String msg, final int duration)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                Toast.makeText(getApplicationContext(), msg, duration).show();
            }
        });
    }

    private void showErrorDialog(final int msgID, final boolean finishOnClose)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                Util.showErrorDialog(MainActivity.this, R.string.error, msgID, finishOnClose);
            }
        });
    }

    private void dismissDialog(final Dialog dialog)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                if (dialog != null)
                {
                    dialog.dismiss();
                }
            }
        });
    }

    //Only adds to the list if not already in it
    private void addDeviceToList(final String name)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mDeviceList.add(name);
            }
        });
    }

    private void updateProgressBar(final boolean start)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                if (start)
                {
                    mScanProgressBar.progressiveStart();
                }
                else
                {
                    mScanProgressBar.progressiveStop();
                }
            }
        });
    }

    private void openAboutDialog()
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                Util.makeAboutDialog(MainActivity.this);
            }
        });
    }
}