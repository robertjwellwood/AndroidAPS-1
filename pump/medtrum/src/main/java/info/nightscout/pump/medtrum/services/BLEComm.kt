package info.nightscout.pump.medtrum.services

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanFilter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import dagger.android.HasAndroidInjector
import info.nightscout.core.ui.toast.ToastUtils
import info.nightscout.core.utils.notify
import info.nightscout.core.utils.waitMillis
import info.nightscout.interfaces.notifications.Notification
import info.nightscout.interfaces.pump.PumpSync
import info.nightscout.interfaces.ui.UiInteraction
import info.nightscout.pump.medtrum.extension.toByteArray
import info.nightscout.pump.medtrum.extension.toInt
import info.nightscout.pump.medtrum.encryption.Crypt
import info.nightscout.pump.medtrum.comm.WriteCommandPackets
import info.nightscout.pump.medtrum.comm.ManufacturerData
import info.nightscout.rx.bus.RxBus
import info.nightscout.rx.events.EventDismissNotification
import info.nightscout.rx.events.EventPumpStatusChanged
import info.nightscout.rx.logging.AAPSLogger
import info.nightscout.rx.logging.LTag
import info.nightscout.shared.interfaces.ResourceHelper
import info.nightscout.shared.sharedPreferences.SP
import info.nightscout.shared.utils.DateUtil
import java.util.UUID
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BLEComm @Inject internal constructor(
    private val injector: HasAndroidInjector,
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val context: Context,
    private val rxBus: RxBus,
    private val sp: SP,
    private val pumpSync: PumpSync,
    private val dateUtil: DateUtil,
    private val uiInteraction: UiInteraction
) {

    companion object {

        private const val WRITE_DELAY_MILLIS: Long = 50
        private const val SERVICE_UUID = "669A9001-0008-968F-E311-6050405558B3"
        private const val READ_UUID = "669a9120-0008-968f-e311-6050405558b3"
        private const val WRITE_UUID = "669a9101-0008-968f-e311-6050405558b"
        private const val CHARACTERISTIC_CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb"

        private const val MANUFACTURER_ID = 18305
        private const val COMMAND_AUTH_REQ: Byte = 5
    }

    private val mBluetoothAdapter: BluetoothAdapter? get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private var mBluetoothGatt: BluetoothGatt? = null
    private val mCrypt = Crypt()

    var isConnected = false
    var isConnecting = false
    private var uartWrite: BluetoothGattCharacteristic? = null

    private var mDeviceSN: Long = 0

    /** Connect flow: 1. Start scanning for our device (SN entered in settings) */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startScan(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            ToastUtils.errorToast(context, context.getString(info.nightscout.core.ui.R.string.need_connect_permission))
            aapsLogger.error(LTag.PUMPBTCOMM, "missing permissions")
            return false
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "Start scan!!")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = mutableListOf<ScanFilter>()


        isConnected = false
        // TODO: Maybe replace this  by (or add) a isScanning parameter?
        isConnecting = true

        // Find our Medtrum Device!
        filters.add(
            ScanFilter.Builder().setDeviceName("MT").build()
        )
        // TODO Check if we need to add MAC for reconnects? Not sure if otherwise we can find the device
        mBluetoothAdapter?.bluetoothLeScanner?.startScan(filters, settings, mScanCallback)
        return true
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stopScan() {
        mBluetoothAdapter?.bluetoothLeScanner?.stopScan(mScanCallback)
    }

    fun connect(from: String, deviceSN: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            ToastUtils.errorToast(context, context.getString(info.nightscout.core.ui.R.string.need_connect_permission))
            aapsLogger.error(LTag.PUMPBTCOMM, "missing permission: $from")
            return false
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "Initializing BLEComm.")
        if (mBluetoothAdapter == null) {
            aapsLogger.error("Unable to obtain a BluetoothAdapter.")
            return false
        }
        mDeviceSN = deviceSN
        isConnecting = true
        startScan()
        return true
    }

    /** Connect flow: 2. When device is found this is called by onScanResult() */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun connectGatt(device: BluetoothDevice) {
        mBluetoothGatt =
            device.connectGatt(context, false, mGattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun disconnect(from: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            aapsLogger.error(LTag.PUMPBTCOMM, "missing permission: $from")
            return
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "disconnect from: $from")
        mBluetoothGatt?.disconnect()
        mBluetoothGatt = null
    }

    @Synchronized
    fun stopConnecting() {
        isConnecting = false
    }

    @SuppressLint("MissingPermission")
    @Synchronized fun close() {
        aapsLogger.debug(LTag.PUMPBTCOMM, "BluetoothAdapter close")
        mBluetoothGatt?.close()
        mBluetoothGatt = null
    }

    /** Scan callback  */
    private val mScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "OnScanResult!" + result)
            super.onScanResult(callbackType, result)

            val manufacturerData =
                result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)
                    ?.let { ManufacturerData(it) }

            aapsLogger.debug(LTag.PUMPBTCOMM, "Found deviceSN: " + manufacturerData?.getDeviceSN())

            if (manufacturerData?.getDeviceSN() == mDeviceSN) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Found our device! deviceSN: " + manufacturerData.getDeviceSN())
                stopScan()
                connectGatt(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Scan FAILED!")
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val mGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            onConnectionStateChangeSynchronized(gatt, status, newState) // call it synchronized
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "onServicesDiscovered")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                findCharacteristic()
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "onCharacteristicRead status = " + status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readDataParsing(characteristic.value)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "onCharacteristicChanged")
            readDataParsing(characteristic.value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "onCharacteristicWrite status = " + status)
            // TODO (note also queue, note that in danars there is no response, so check where to handle multiple packets)
            // aapsLogger.debug(LTag.PUMPBTCOMM, "onCharacteristicWrite: " + DanaRS_Packet.toHexString(characteristic.value))
            // Thread {
            //     synchronized(mSendQueue) {
            //         // after message sent, check if there is the rest of the message waiting and send it
            //         if (mSendQueue.size > 0) {
            //             val bytes = mSendQueue[0]
            //             mSendQueue.removeAt(0)
            //             writeCharacteristicNoResponse(uartWriteBTGattChar, bytes)
            //         }
            //     }
            // }.start()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            aapsLogger.debug(LTag.PUMPBTCOMM, "onDescriptorWrite " + status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readDescriptor(descriptor)
            }
        }

        /** Connect flow: 5. Notifications enabled read descriptor to verify and start auth process*/
        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorRead(gatt, descriptor, status)
            aapsLogger.debug(LTag.PUMPBTCOMM, "onDescriptorRead status: " + status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                checkDescriptor(descriptor)
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun readDescriptor(descriptor: BluetoothGattDescriptor?) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "readDescriptor")
        if (mBluetoothAdapter == null || mBluetoothGatt == null || descriptor == null) {
            aapsLogger.error("BluetoothAdapter not initialized_ERROR")
            isConnecting = false
            isConnected = false
            return
        }
        mBluetoothGatt?.readDescriptor(descriptor)
    }

    @Suppress("DEPRECATION")
    private fun checkDescriptor(descriptor: BluetoothGattDescriptor) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "checkDescriptor")
        val service = getGattService()
        if (mBluetoothAdapter == null || mBluetoothGatt == null || service == null) {
            aapsLogger.error("BluetoothAdapter not initialized_ERROR")
            isConnecting = false
            isConnected = false
            return
        }
        if (descriptor.value.toInt() > 0) {
            var notificationEnabled = true
            val characteristics = service.characteristics
            for (j in 0 until characteristics.size) {
                val configDescriptor =
                    characteristics[j].getDescriptor(UUID.fromString(CHARACTERISTIC_CONFIG_UUID))
                if (configDescriptor.value == null || configDescriptor.value.toInt() <= 0) {
                    notificationEnabled = false
                }
            }
            if (notificationEnabled) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Notifications enabled!")
                authorize()
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic?, enabled: Boolean) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "setCharacteristicNotification")
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            aapsLogger.error("BluetoothAdapter not initialized_ERROR")
            isConnecting = false
            isConnected = false
            return
        }
        mBluetoothGatt?.setCharacteristicNotification(characteristic, enabled)
        characteristic?.getDescriptor(UUID.fromString(CHARACTERISTIC_CONFIG_UUID))?.let {
            if (characteristic.properties and 0x10 > 0) {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                mBluetoothGatt?.writeDescriptor(it)
            } else if (characteristic.properties and 0x20 > 0) {
                it.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                mBluetoothGatt?.writeDescriptor(it)
            } else {

            }
        }
    }

    /** Connect flow: 3. When we are connected discover services*/
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun onConnectionStateChangeSynchronized(gatt: BluetoothGatt, status: Int, newState: Int) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "onConnectionStateChange newState: " + newState + " status: " + status)
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            gatt.discoverServices()
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            close()
            isConnected = false
            isConnecting = false
            rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
            aapsLogger.debug(LTag.PUMPBTCOMM, "Device was disconnected " + gatt.device.name) //Device was disconnected
        }
    }

    private fun readDataParsing(receivedData: ByteArray) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "<<<readDataParsing>>> " + Arrays.toString(receivedData))
        // TODO
        /** Connect flow: 6. Authorized */ // TODO place this at the correct place
    }

    private fun authorize() {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Start auth!")
        val role = 2 // Fixed to 2 for pump
        val key = mCrypt.keyGen(mDeviceSN)
        val commandData = byteArrayOf(COMMAND_AUTH_REQ) + byteArrayOf(role.toByte()) + 0.toByteArray(4) + key.toByteArray(4)
        sendMessage(commandData)
    }

    fun sendMessage(message: ByteArray) {
        // TODO: Handle packages which consist of multiple, Create a queue of packages
        aapsLogger.debug(LTag.PUMPBTCOMM, "sendMessage message = " + Arrays.toString(message))
        val writePacket = WriteCommandPackets(message)
        val value: ByteArray? = writePacket.getNextPacket()

        // TODO: queue
        writeCharacteristic(uartWriteBTGattChar, value)
    }

    private fun getGattService(): BluetoothGattService? {
        aapsLogger.debug(LTag.PUMPBTCOMM, "getGattService")
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            aapsLogger.error("BluetoothAdapter not initialized_ERROR")
            isConnecting = false
            isConnected = false
            return null
        }
        return mBluetoothGatt?.getService(UUID.fromString(SERVICE_UUID))
    }

    private fun getGattCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        aapsLogger.debug(LTag.PUMPBTCOMM, "getGattCharacteristic $uuid")
        val service = getGattService()
        if (mBluetoothAdapter == null || mBluetoothGatt == null || service == null) {
            aapsLogger.error("BluetoothAdapter not initialized_ERROR")
            isConnecting = false
            isConnected = false
            return null
        }
        return service.getCharacteristic(uuid)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, data: ByteArray?) {
        Thread(Runnable {
            SystemClock.sleep(WRITE_DELAY_MILLIS)
            if (mBluetoothAdapter == null || mBluetoothGatt == null) {
                aapsLogger.error("BluetoothAdapter not initialized_ERROR")
                isConnecting = false
                isConnected = false
                return@Runnable
            }
            characteristic.value = data
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            aapsLogger.debug("writeCharacteristic:" + Arrays.toString(data))
            mBluetoothGatt?.writeCharacteristic(characteristic)
        }).start()
        SystemClock.sleep(WRITE_DELAY_MILLIS)
    }

    private val uartWriteBTGattChar: BluetoothGattCharacteristic
        get() = uartWrite
            ?: BluetoothGattCharacteristic(UUID.fromString(WRITE_UUID), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, 0).also { uartWrite = it }

    /** Connect flow: 4. When services are discovered find characteristics and set notifications*/
    private fun findCharacteristic() {
        val gattService = getGattService() ?: return
        val gattCharacteristics = gattService.characteristics
        for (gattCharacteristic in gattCharacteristics) {
            setCharacteristicNotification(gattCharacteristic, true)
        }
    }
}