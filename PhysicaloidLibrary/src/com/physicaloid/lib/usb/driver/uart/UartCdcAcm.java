package com.physicaloid.lib.usb.driver.uart;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;

import com.physicaloid.lib.UsbVidList;
import com.physicaloid.lib.framework.SerialCommunicator;
import com.physicaloid.lib.usb.UsbCdcConnection;
import com.physicaloid.lib.usb.UsbVidPid;
import com.physicaloid.misc.RingBuffer;

public class UartCdcAcm extends SerialCommunicator{
    private static final String TAG = UartCdcAcm.class.getSimpleName();

    private static final boolean DEBUG_SHOW = true;
    private static final int DEFAULT_BAUDRATE = 9600;

    private UsbCdcConnection mUsbConnetionManager;

    private UartConfig mUartConfig;
    private static final int RING_BUFFER_SIZE       = 1024;
    private static final int USB_READ_BUFFER_SIZE   = 256;
    private static final int USB_WRITE_BUFFER_SIZE  = 256;
    private RingBuffer mBuffer;

    private boolean mReadThreadStop = true;

    private UsbDeviceConnection mConnection;
    private UsbEndpoint mEndpointIn;
    private UsbEndpoint mEndpointOut;

    public UartCdcAcm(Context context) {
        super(context);
        mUsbConnetionManager = new UsbCdcConnection(context);
        mUartConfig = new UartConfig();
        mBuffer = new RingBuffer(RING_BUFFER_SIZE);
    }

    @Override
    public boolean open() {
        for(UsbVidList id : UsbVidList.values()) {
            if(open(new UsbVidPid(id.getVid(), 0))){
                return true;
            }
        }
        return false;
    }

    public boolean open(UsbVidPid ids) {
        if(mUsbConnetionManager.open(ids)) {
            mConnection     = mUsbConnetionManager.getConnection();
            mEndpointIn     = mUsbConnetionManager.getEndpointIn();
            mEndpointOut    = mUsbConnetionManager.getEndpointOut();
            if(!init()) { return false; }
            if(!setBaudrate(DEFAULT_BAUDRATE)) {return false;}
            startRead();
            return true;
        }
        return false;
    }

    @Override
    public boolean close() {
        stopRead();
        return mUsbConnetionManager.close();
    }

    @Override
    public int read(byte[] buf, int size) {
        return mBuffer.get(buf, size);
    }

    @Override
    public int write(byte[] buf, int size) {
        if(buf == null) { return 0; }
        int offset = 0;
        int write_size;
        int written_size;
        byte[] wbuf = new byte[USB_WRITE_BUFFER_SIZE];

        while (offset < size) {
            write_size = USB_WRITE_BUFFER_SIZE;

            if (offset + write_size > size) {
                write_size = size - offset;
            }
            System.arraycopy(buf, offset, wbuf, 0, write_size);

            written_size = mConnection.bulkTransfer(mEndpointOut, wbuf, write_size, 100);

            if (written_size < 0) {
                return -1;
            }
            offset += written_size;
        }

        return offset;
    }

    private void stopRead() {
        mReadThreadStop = true;
    }

    private void startRead() {
        if(mReadThreadStop) {
            mReadThreadStop = false;
            new Thread(mLoop).start();
        }
    }

    private Runnable mLoop = new Runnable() {
        @Override
        public void run() {
            int len=0;
            byte[] rbuf = new byte[USB_READ_BUFFER_SIZE];
            for (;;) {// this is the main loop for transferring

                try {
                    len = mConnection.bulkTransfer(mEndpointIn,
                            rbuf, rbuf.length, 50);
                } catch(Exception e) {
                    Log.e(TAG, e.toString());
                }

                if (len > 0) {
                    mBuffer.add(rbuf, len);
                    onRead(len);
                }

                if (mReadThreadStop) {
                    return;
                }

                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                }

            }
        } // end of run()
    }; // end of runnable


    /**
     * Sets Uart configurations
     * @param config configurations
     * @return true : successful, false : fail
     */
    public boolean setUartConfig(UartConfig config) {
        boolean res = true;
        boolean ret = true;
        if(mUartConfig.baudrate != config.baudrate) {
            res = setBaudrate(config.baudrate);
            ret = ret && res;
        }

        if(mUartConfig.dataBits != config.dataBits) {
            res = setDataBits(config.dataBits);
            ret = ret && res;
        }

        if(mUartConfig.parity != config.parity) {
            res = setParity(config.parity);
            ret = ret && res;
        }

        if(mUartConfig.stopBits != config.stopBits) {
            res = setStopBits(config.stopBits);
            ret = ret && res;
        }

        if(mUartConfig.dtrOn != config.dtrOn ||
           mUartConfig.rtsOn != config.rtsOn) {
            res = setDtrRts(config.dtrOn, config.rtsOn);
            ret = ret && res;
        }

        return ret;
    }

    /**
     * Initializes CDC communication
     * @return true : successful, false : fail
     */
    public boolean init() {
        if(mConnection == null) return false;
        int ret = mConnection.controlTransfer(0x21, 0x22, 0x00, 0, null, 0, 0); // init CDC
        if(ret < 0) { return false; }
        return true;
    }

    /**
     * Sets baudrate
     * @param baudrate baudrate e.g. 9600
     * @return true : successful, false : fail
     */
    public boolean setBaudrate(int baudrate) {
        byte[] baudByte = new byte[4];

        baudByte[0] = (byte) (baudrate & 0x000000FF);
        baudByte[1] = (byte) ((baudrate & 0x0000FF00) >> 8);
        baudByte[2] = (byte) ((baudrate & 0x00FF0000) >> 16);
        baudByte[3] = (byte) ((baudrate & 0xFF000000) >> 24);
        int ret = mConnection.controlTransfer(0x21, 0x20, 0, 0, new byte[] {
                baudByte[0], baudByte[1], baudByte[2], baudByte[3], 0x00, 0x00,
                0x08}, 7, 100);
        if(ret < 0) { 
            if(DEBUG_SHOW) { Log.d(TAG, "Fail to setBaudrate"); }
            return false;
        }
        mUartConfig.baudrate = baudrate;
        return true;
    }

    /**
     * Sets Data bits
     * @param dataBits data bits e.g. UartConfig.DATA_BITS8
     * @return true : successful, false : fail
     */
    public boolean setDataBits(int dataBits) {
        // TODO : implement
        if(DEBUG_SHOW) { Log.d(TAG, "Fail to setDataBits"); }
        mUartConfig.dataBits = dataBits;
        return false;
    }

    /**
     * Sets Parity bit
     * @param parity parity bits e.g. UartConfig.PARITY_NONE
     * @return true : successful, false : fail
     */
    public boolean setParity(int parity) {
        // TODO : implement
        if(DEBUG_SHOW) { Log.d(TAG, "Fail to setParity"); }
        mUartConfig.parity = parity;
        return false;
    }

    /**
     * Sets Stop bits
     * @param stopBits stop bits e.g. UartConfig.STOP_BITS1
     * @return true : successful, false : fail
     */
    public boolean setStopBits(int stopBits) {
        // TODO : implement
        if(DEBUG_SHOW) { Log.d(TAG, "Fail to setStopBits"); }
        mUartConfig.stopBits = stopBits;
        return false;
    }

    @Override
    public boolean setDtrRts(boolean dtrOn, boolean rtsOn) {
        int ctrlValue = 0x0000;
        if(dtrOn) {
            ctrlValue |= 0x0001;
        }
        if(rtsOn) {
            ctrlValue |= 0x0002;
        }
        int ret = mConnection.controlTransfer(0x21, 0x22, ctrlValue, 0, null, 0, 100);
        if(ret < 0) { 
            if(DEBUG_SHOW) { Log.d(TAG, "Fail to setDtrRts"); }
            return false;
        }
        mUartConfig.dtrOn = dtrOn;
        mUartConfig.rtsOn = rtsOn;
        return true;
    }

    @Override
    public UartConfig getUartConfig() {
        return mUartConfig;
    }

    @Override
    public int getBaudrate() {
        return mUartConfig.baudrate;
    }

    @Override
    public int getDataBits() {
        return mUartConfig.dataBits;
    }

    @Override
    public int getParity() {
        return mUartConfig.parity;
    }

    @Override
    public int getStopBits() {
        return mUartConfig.stopBits;
    }

    @Override
    public boolean getDtr() {
        return mUartConfig.dtrOn;
    }

    @Override
    public boolean getRts() {
        return mUartConfig.rtsOn;
    }

    //////////////////////////////////////////////////////////
    // Listener for reading uart
    //////////////////////////////////////////////////////////
    private List<UartReadLisener> uartReadListenerList
        = new ArrayList<UartReadLisener>();

    public void addReadListener(UartReadLisener listener) {
        uartReadListenerList.add(listener);
    }

    public void clearReadListener() {
        uartReadListenerList.clear();
    }

    private void onRead(int size) {
        for (UartReadLisener listener: uartReadListenerList) {
            listener.onRead(size);
        }
    }
    //////////////////////////////////////////////////////////

}
