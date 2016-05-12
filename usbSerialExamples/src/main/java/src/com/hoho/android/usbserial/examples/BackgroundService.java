package src.com.hoho.android.usbserial.examples;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.IBinder;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

//BJ - added for code changes see BJ below
import android.app.PendingIntent;
import android.app.AlarmManager;
import android.os.SystemClock;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.examples.NMEAParser;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
//BJ
//import java.lang.Object;

public class BackgroundService extends Service {

    private boolean mIsRunning = false;
    private volatile UsbDevice mUsbDevice = null;
    private volatile UsbDeviceConnection mUsbConnection = null;
    private NMEAParser parser = new NMEAParser();
    private MockLocationProvider mockLocationProvider;


    private final String TAG = BackgroundService.class.getSimpleName();

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private static UsbSerialPort sPort = null;

    public BackgroundService() {
    }

    public boolean isMockEnabled() {
        try {
            int mock_location = Settings.Secure.getInt(this.getContentResolver(), "mock_location");
            if (mock_location == 0) {
                try {
                    Settings.Secure.putInt(this.getContentResolver(), "mock_location", 1);
                } catch (Exception ex) {
                }
                mock_location = Settings.Secure.getInt(this.getContentResolver(), "mock_location");
            }

            if (mock_location == 0) {
                Toast.makeText(this, "Turn on the mock locations in your Android settings", Toast.LENGTH_LONG).show();
                return false;
            } else {
                return true;
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return false;
    }

    private SerialInputOutputManager mSerialIoManager;

    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

                @Override
                public void onRunError(Exception e) {
                    Log.d(TAG, "Runner stopped.");
                    checkDevice();
                }

                @Override
                public void onNewData(final byte[] data) {
                    StringBuilder result = new StringBuilder();
                    for (int i = 0; i < data.length - 2; i++) {
                        if (data[i] > ' ' && data[i] < '~') {
                            result.append(new String(new byte[]{data[i]}));
                        } else {
                            result.append(".");
                        }
                    }

                    //final String message = "Read " + data.length + " bytes: \n"
                    //        + result.toString() + "\n\n";

                    Location loc = parser.location(result.toString());

                    //Log.i(TAG, "reading GPS");

                    if (loc != null) {
                        mockLocationProvider.pushLocation(loc);
                        //Log.i(TAG, loc.toString());
                    }
                }
            };

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(mReceiver, filter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);
        mUsbDevice = null;
        if(mockLocationProvider != null) {
            mockLocationProvider.shutdown();
        }
        if (mUsbConnection != null) {
            mUsbConnection.close();
        }
    }

	// BJ restart service if it is killed
	@Override
	public void onTaskRemoved(Intent rootIntent){
		Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
		restartServiceIntent.setPackage(getPackageName());

		PendingIntent restartServicePendingIntent = PendingIntent.getService(getApplicationContext(), 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT);
		AlarmManager alarmService = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
		alarmService.set(
		AlarmManager.ELAPSED_REALTIME,
		SystemClock.elapsedRealtime() + 1000,
		restartServicePendingIntent);

		super.onTaskRemoved(rootIntent);
	 }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if(!isMockEnabled()) {
            return Service.START_FLAG_RETRY;
        }

        mockLocationProvider = new MockLocationProvider(LocationManager.GPS_PROVIDER, this);

        ProbeTable customTable = new ProbeTable();
        customTable.addProduct(0x1546, 0x01a7, CdcAcmSerialDriver.class);
        UsbSerialProber prober = new UsbSerialProber(customTable);

        mUsbDevice = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

        UsbManager mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        final List<UsbSerialDriver> drivers =
                prober.findAllDrivers(mUsbManager);

        sPort = drivers.get(0).getPorts().get(0);

        UsbDeviceConnection connection = mUsbManager.openDevice(sPort.getDriver().getDevice());
        if (connection == null) {
            return Service.START_FLAG_RETRY;
        }

        try {
            sPort.open(connection);
            sPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

        } catch (IOException e) {
            Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
            try {
                sPort.close();
            } catch (IOException e2) {
                // Ignore.
            }
            sPort = null;
            return Service.START_REDELIVER_INTENT;
        }

        Log.i(TAG, "started GPS RECEIVER");
        startIoManager();
        return Service.START_REDELIVER_INTENT;
    }


    BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                mUsbDevice = null;
                stopIoManager();
                stopSelf();
            }

//            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
//                //startIoManager();
//            }
        }
    };

    //if the device is still attached try and restart
    private void checkDevice() {
        if(mUsbDevice != null) {
            startIoManager();
        }
    }


    private void stopIoManager() {
        if (mSerialIoManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
    }

    private void startIoManager() {
        if (sPort != null) {
            Log.i(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(sPort, mListener);
            mExecutor.submit(mSerialIoManager);
        }
    }
}
