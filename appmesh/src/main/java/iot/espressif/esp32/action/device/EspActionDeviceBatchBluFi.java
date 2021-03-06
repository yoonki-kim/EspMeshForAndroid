package iot.espressif.esp32.action.device;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.content.Context;

import androidx.annotation.NonNull;

import java.util.Collection;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import iot.espressif.esp32.app.EspApplication;
import iot.espressif.esp32.model.device.ble.MeshBatchBlufiCallback;
import iot.espressif.esp32.model.device.ble.MeshBlufiCallback;
import iot.espressif.esp32.model.device.ble.MeshBlufiClient;
import libs.espressif.ble.EspBleUtils;
import libs.espressif.log.EspLog;

public class EspActionDeviceBatchBluFi extends EspActionDeviceBlufi implements IEspActionDeviceBatchBluFi {
    private static final int CONNECT_COUNT_DEFAULT = 5;

    private final EspLog mLog = new EspLog(getClass());

    private final LinkedList<BluetoothDevice> mDeviceQueue;
    private final AtomicInteger mDeviceCounter;
    private int mMeshVersion;
    private MeshBatchBlufiCallback mUserCallback;

    private int mConnectTryCount = CONNECT_COUNT_DEFAULT;

    private volatile boolean mClosed = false;

    private Map<BluetoothDevice, MeshBlufiClient> mMeshBlufiClients;
    private Thread mThread;

    public EspActionDeviceBatchBluFi(@NonNull Collection<BluetoothDevice> devices, int meshVersion,
                                     @NonNull MeshBatchBlufiCallback userCallback) {
        mDeviceQueue = new LinkedList<>(devices);
        mDeviceCounter = new AtomicInteger(0);
        mMeshVersion = meshVersion;
        mUserCallback = userCallback;

        mMeshBlufiClients = new Hashtable<>();
    }

    @Override
    public void setTryConnectingCount(int count) {
        mConnectTryCount = count;
        if (mConnectTryCount <= 0) {
            mConnectTryCount = CONNECT_COUNT_DEFAULT;
        }
    }

    @Override
    public void close() {
        mClosed = true;

        if (mThread != null) {
            mThread.interrupt();
            mThread = null;
        }

        for (MeshBlufiClient client : mMeshBlufiClients.values()) {
            client.close();
        }
        mMeshBlufiClients.clear();
    }

    @Override
    public MeshBlufiClient doActionConnectMeshBLE(@NonNull BluetoothDevice device, int meshVersion,
                                                  @NonNull MeshBlufiCallback userCallback) {
        throw new UnsupportedOperationException("Forbid this function, please call execute()");
    }

    @Override
    public void notifyNext() {
        synchronized (mDeviceQueue) {
            int count = mDeviceCounter.decrementAndGet();
            if (count < 0) {
                mDeviceCounter.set(0);
            }
            mDeviceQueue.notify();
        }
    }

    @Override
    public void execute() {
        if (mClosed) {
            throw new IllegalStateException("The action has closed");
        }

        mLog.d("Execute Batch BluFi");
        mThread = new Thread(() -> {
            while (!mDeviceQueue.isEmpty()) {
                if (mClosed) {
                    break;
                }

                synchronized (mDeviceQueue) {
                    if (mDeviceCounter.get() > CONNECTION_MAX) {
                        try {
                            mDeviceQueue.wait();
                        } catch (InterruptedException e) {
                            mLog.w("DeviceQueue wait interrupted");
                            break;
                        }
                    }
                }

                BluetoothDevice device = mDeviceQueue.poll();
                assert device != null;
                MeshBlufiClient blufi = new MeshBlufiClient();
                blufi.setMeshVersion(mMeshVersion);
                mMeshBlufiClients.put(device, blufi);
                if (mUserCallback != null) {
                    mUserCallback.onClientCreated(blufi);
                }
                Context context = EspApplication.getEspApplication().getApplicationContext();
                boolean connected = false;
                mDeviceCounter.incrementAndGet();
                for (int i = 0; i < mConnectTryCount; ++i) {
                    if (blufi.isClosed()) {
                        break;
                    }

                    LinkedBlockingQueue<Boolean> connectQueue = new LinkedBlockingQueue<>();
                    BleCallback bleCallback = new BleCallback(blufi, mUserCallback) {
                        @Override
                        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                            super.onConnectionStateChange(gatt, status, newState);

                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                connectQueue.add(false);
                            } else {
                                if (newState == BluetoothGatt.STATE_CONNECTED) {
                                    connectQueue.add(true);
                                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                                    connectQueue.add(false);
                                }
                            }
                        }
                    };
                    BluetoothGatt gatt = EspBleUtils.connectGatt(device, context, bleCallback);
                    blufi.setBluetoothGatt(gatt);

                    try {
                        connected = connectQueue.take();
                        if (connected) {
                            break;
                        } else {
                            gatt.close();
                        }
                    } catch (InterruptedException e) {
                        mLog.w("Take connect queue interrupted");
                        gatt.close();
                        break;
                    }

                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        mLog.w("Sleep connect interrupted");
                        break;
                    }
                }
                if (!connected) {
                    mDeviceCounter.decrementAndGet();
                }
                if (mUserCallback != null) {
                    mUserCallback.onConnectResult(device, connected);
                }
            }
            mLog.d("Batch BluFi Over");
        });
        mThread.start();
    }
}
