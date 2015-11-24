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

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;


import com.zentri.zentri_ble_command.Command;
import com.zentri.zentri_ble_command.CommandMode;
import com.zentri.zentri_ble_command.ErrorCode;
import com.zentri.zentri_ble_command.GPIODirection;
import com.zentri.zentri_ble_command.GPIOFunction;
import com.zentri.zentri_ble_command.ZentriOSBLEManager;
import com.zentri.zentri_ble_command.Result;

public class DeviceInfoActivity extends AppCompatActivity
{
    public static final String TAG = "DeviceInfo";

    private final long DISCONNECT_TIMEOUT_MS = 10000;

    private static final int ADC_GPIO = 12;//thermistor on wahoo
    private static final int TEST_GPIO = 9;//button2 on wahoo
    private static final int LED_GPIO = 14;

    private TextView mADCTextView;
    private TextView mGPIOTextView;
    private Button mUpdateButton;
    private ToggleButton mLedButton;
    private int mLedState = 0;

    private ToggleButton mModeButton;
    private int mCurrentMode;

    private Button mSendTextButton;
    private EditText mTextToSendBox;

    private TextView mReceivedDataTextBox;

    private ServiceConnection mConnection;
    private ZentriOSBLEService mService;
    private boolean mBound = false;

    private LocalBroadcastManager mLocalBroadcastManager;
    private BroadcastReceiver mBroadcastReceiver;
    private IntentFilter mReceiverIntentFilter;

    private ZentriOSBLEManager mZentriOSBLEManager;

    private Dialog mDisconnectDialog;

    private boolean mADCUpdateInProgress = false;
    private boolean mGPIOUpdateInProgress = false;
    private int mADCUpdateID, mGPIOUpdateID;

    private boolean mDisconnecting = false;

    private Handler mHandler;
    private Runnable mDisconnectTimeoutTask;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_info);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setTitle(R.string.title_activity_device_info);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null)
        {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mADCTextView = (TextView)findViewById(R.id.adc_value);
        mGPIOTextView = (TextView)findViewById(R.id.gpio_value);
        mLedButton = (ToggleButton)findViewById(R.id.led_button);

        mLedButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                if (mLedButton.isChecked())
                {
                    mLedState = 1;
                    writeLedState();
                }
                else
                {
                    mLedState = 0;
                    writeLedState();
                }
            }
        });

        mUpdateButton = (Button) findViewById(R.id.update_button);
        mUpdateButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                updateValues();
            }
        });

        mModeButton = (ToggleButton) findViewById(R.id.modeButton);
        mModeButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                if (mModeButton.isChecked())
                {
                    mCurrentMode = ZentriOSBLEManager.MODE_STREAM;
                }
                else
                {
                    mCurrentMode = ZentriOSBLEManager.MODE_COMMAND_REMOTE;
                }
                mZentriOSBLEManager.setMode(mCurrentMode);
            }
        });

        mTextToSendBox = (EditText) findViewById(R.id.textToSend);
        mSendTextButton = (Button) findViewById(R.id.sendTextButton);
        mSendTextButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                String data = mTextToSendBox.getText().toString();
                if (data != null && !data.isEmpty())
                {
                    mZentriOSBLEManager.writeData(data);
                }

                mTextToSendBox.setText("");//clear input after send
            }
        });

        mReceivedDataTextBox = (TextView) findViewById(R.id.receivedDataBox);

        initBroadcastManager();
        initBroadcastReceiver();
        initServiceConnection();
        initReceiverIntentFilter();

        GUISetCommandMode();//set up gui for command mode initially

        mHandler = new Handler();

        mDisconnectTimeoutTask = new Runnable()
        {
            @Override
            public void run()
            {
                dismissProgressDialog();
                showErrorDialog(R.string.error, R.string.discon_timeout_message);
            }
        };
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_device_info, menu);
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
    public void onBackPressed()
    {
        disconnect();
    }

    @Override
    protected void onStart()
    {
        super.onStart();

        mLocalBroadcastManager.registerReceiver(mBroadcastReceiver, mReceiverIntentFilter);

        Intent serviceIntent = new Intent(getApplicationContext(), ZentriOSBLEService.class);
        bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop()
    {
        super.onStop();

        //quickly disconnect to make sure we are definitely disconnected
        mZentriOSBLEManager.disconnect(!ZentriOSBLEService.DISABLE_TX_NOTIFY);

        mLocalBroadcastManager.unregisterReceiver(mBroadcastReceiver);

        if (mBound)
        {
            unbindService(mConnection);
        }
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
                mZentriOSBLEManager.setMode(ZentriOSBLEManager.MODE_COMMAND_REMOTE);
                mZentriOSBLEManager.setSystemCommandMode(CommandMode.MACHINE);
                initGPIOs();
                updateValues();
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
                    case ZentriOSBLEService.ACTION_COMMAND_SENT:
                        String command = ZentriOSBLEService.getCommand(intent).toString();
                        Log.d(TAG, "Command " + command + " sent");
                        break;

                    case ZentriOSBLEService.ACTION_COMMAND_RESULT:
                        handleCommandResponse(intent);
                        break;

                    case ZentriOSBLEService.ACTION_MODE_WRITE:
                        int mode = ZentriOSBLEService.getMode(intent);
                        if (mode == ZentriOSBLEManager.MODE_STREAM)
                        {
                            //disable buttons while in stream mode (must be in rem command to work)
                            GUISetStreamMode();
                        }
                        else
                        {
                            GUISetCommandMode();
                        }
                        break;

                    case ZentriOSBLEService.ACTION_STRING_DATA_READ:
                        if (mCurrentMode == ZentriOSBLEManager.MODE_STREAM)
                        {
                            String text = ZentriOSBLEService.getData(intent);
                            updateReceivedTextBox(text);
                        }
                        break;

                    case ZentriOSBLEService.ACTION_ERROR:
                        ErrorCode errorCode = ZentriOSBLEService.getErrorCode(intent);
                        //handle errors
                        switch (errorCode)
                        {
                            case DEVICE_ERROR:
                                //connection state change without request
                                if (mDisconnecting)
                                {
                                    mDisconnecting = false;
                                    dismissProgressDialog();
                                    showErrorDialog(R.string.error, R.string.device_error_message);
                                }
                                break;

                            case DISCONNECT_FAILED:
                                mDisconnecting = false;
                                dismissProgressDialog();
                                showDisconnectErrorDialog(R.string.error, R.string.discon_err_message);
                                break;

                        }
                        break;

                    case ZentriOSBLEService.ACTION_DISCONNECTED:
                        mHandler.removeCallbacks(mDisconnectTimeoutTask);//cancel timeout
                        dismissProgressDialog();
                        finish();
                        break;
                }
            }
        };
    }

    public void initBroadcastManager()
    {
        mLocalBroadcastManager = LocalBroadcastManager.getInstance(DeviceInfoActivity.this);
    }

    public void initReceiverIntentFilter()
    {
        mReceiverIntentFilter = new IntentFilter();
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_DISCONNECTED);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_COMMAND_RESULT);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_ERROR);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_STRING_DATA_READ);
        mReceiverIntentFilter.addAction(ZentriOSBLEService.ACTION_MODE_WRITE);
    }

    private void initGPIOs()
    {
        mZentriOSBLEManager.GPIOFunctionSet(ADC_GPIO, GPIOFunction.NONE);
        mZentriOSBLEManager.GPIOFunctionSet(TEST_GPIO, GPIOFunction.NONE);
        mZentriOSBLEManager.GPIOFunctionSet(LED_GPIO, GPIOFunction.NONE);

        mZentriOSBLEManager.GPIOFunctionSet(TEST_GPIO, GPIOFunction.STDIO);
        mZentriOSBLEManager.GPIOFunctionSet(LED_GPIO, GPIOFunction.STDIO);

        mZentriOSBLEManager.GPIODirectionSet(TEST_GPIO, GPIODirection.INPUT);
        mZentriOSBLEManager.GPIODirectionSet(LED_GPIO, GPIODirection.OUTPUT_LOW);
    }

    private void updateValues()
    {
        if (!mADCUpdateInProgress && !mGPIOUpdateInProgress)
        {
            mADCUpdateInProgress = true;
            mGPIOUpdateInProgress = true;

            Log.d(TAG, "Updating values");
            mADCUpdateID = mZentriOSBLEManager.adc(ADC_GPIO);
            mGPIOUpdateID = mZentriOSBLEManager.GPIOGet(TEST_GPIO);
        }
        else
        {
            showToast("Update in progress...", Toast.LENGTH_SHORT);
        }
    }

    private void handleCommandResponse(Intent intent)
    {
        Command command = ZentriOSBLEService.getCommand(intent);
        int code = ZentriOSBLEService.getResponseCode(intent);
        int id = ZentriOSBLEService.getCommandID(intent);

        String result = ZentriOSBLEService.getData(intent);
        String message = "";

        Log.d(TAG, "Command " + command + " result");

        if (id == mADCUpdateID)
        {
            mADCUpdateInProgress = false;

            if (code == Result.SUCCESS)
            {
                message = String.format("ADC: %s", result);
                mADCTextView.setText(message);
            }
            else
            {
                showToast("ERROR - failed to update ADC", Toast.LENGTH_SHORT);
            }
        }
        else if (id == mGPIOUpdateID)
        {
            mGPIOUpdateInProgress = false;

            if (code == Result.SUCCESS)
            {
                message = String.format("GPIO: %s", result);
                mGPIOTextView.setText(message);
            }
            else
            {
                showToast("ERROR - failed to update GPIO", Toast.LENGTH_SHORT);
            }
        }
    }

    //set up gui elements for command mode operation
    private void GUISetCommandMode()
    {
        mLedButton.setEnabled(true);
        mUpdateButton.setEnabled(true);
        mSendTextButton.setEnabled(false);
        mTextToSendBox.setVisibility(View.INVISIBLE);
    }

    //set up gui elements for command mode operation
    private void GUISetStreamMode()
    {
        mLedButton.setEnabled(false);
        mUpdateButton.setEnabled(false);
        mSendTextButton.setEnabled(true);
        mTextToSendBox.setVisibility(View.VISIBLE);
    }

    private void disconnect()
    {
        mDisconnecting = true;
        showDisconnectDialog();
        mZentriOSBLEManager.disconnect(ZentriOSBLEService.DISABLE_TX_NOTIFY);
        mHandler.postDelayed(mDisconnectTimeoutTask, DISCONNECT_TIMEOUT_MS);
    }

    private void updateReceivedTextBox(String newData)
    {
        mReceivedDataTextBox.append(newData);
    }

    private void clearReceivedTextBox()
    {
        mReceivedDataTextBox.setText("");
    }

    private void showDisconnectDialog()
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                mDisconnectDialog = Util.showProgressDialog(DeviceInfoActivity.this,
                                                            R.string.disconnect_dialog_title,
                                                            R.string.disconnect_dialog_message);
            }
        });
    }

    private void showDisconnectErrorDialog(final int titleID, final int msgID)
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(DeviceInfoActivity.this);

        builder.setTitle(titleID)
                .setMessage(msgID)
                .setPositiveButton(R.string.retry, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        dialog.dismiss();
                        disconnect();
                    }
                })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        dialog.dismiss();
                        DeviceInfoActivity.this.finish();
                    }
                })
                .create()
                .show();
    }

    private void showErrorDialog(final int titleID, final int msgID)
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(DeviceInfoActivity.this);

        builder.setTitle(titleID)
                .setMessage(msgID)
                .setNegativeButton(R.string.ok, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        dialog.dismiss();
                        DeviceInfoActivity.this.finish();
                    }
                })
                .create()
                .show();
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

    private void dismissProgressDialog()
    {
        if (mDisconnectDialog != null)
        {
            mDisconnectDialog.dismiss();
        }
    }

    private void writeLedState()
    {
        mZentriOSBLEManager.GPIOSet(LED_GPIO, mLedState);
    }

    private void openAboutDialog()
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                Util.makeAboutDialog(DeviceInfoActivity.this);
            }
        });
    }
}
