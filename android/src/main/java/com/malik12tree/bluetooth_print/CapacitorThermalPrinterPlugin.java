package com.malik12tree.bluetooth_print;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;
import androidx.annotation.RequiresApi;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.rt.printerlibrary.bean.BluetoothEdrConfigBean;
import com.rt.printerlibrary.cmd.Cmd;
import com.rt.printerlibrary.cmd.EscCmd;
import com.rt.printerlibrary.cmd.EscFactory;
import com.rt.printerlibrary.connect.PrinterInterface;
import com.rt.printerlibrary.enumerate.BarcodeStringPosition;
import com.rt.printerlibrary.enumerate.BarcodeType;
import com.rt.printerlibrary.enumerate.BmpPrintMode;
import com.rt.printerlibrary.enumerate.CommonEnum;
import com.rt.printerlibrary.enumerate.ConnectStateEnum;
import com.rt.printerlibrary.enumerate.ESCBarcodeFontTypeEnum;
import com.rt.printerlibrary.enumerate.ESCFontTypeEnum;
import com.rt.printerlibrary.enumerate.SettingEnum;
import com.rt.printerlibrary.exception.SdkException;
import com.rt.printerlibrary.factory.cmd.CmdFactory;
import com.rt.printerlibrary.factory.connect.BluetoothFactory;
import com.rt.printerlibrary.factory.connect.PIFactory;
import com.rt.printerlibrary.factory.printer.ThermalPrinterFactory;
import com.rt.printerlibrary.observer.PrinterObserver;
import com.rt.printerlibrary.observer.PrinterObserverManager;
import com.rt.printerlibrary.printer.RTPrinter;
import com.rt.printerlibrary.setting.BarcodeSetting;
import com.rt.printerlibrary.setting.BitmapSetting;
import com.rt.printerlibrary.setting.TextSetting;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONException;

@CapacitorPlugin(name = "CapacitorThermalPrinter", permissions = {
        @Permission(strings = { Manifest.permission.ACCESS_COARSE_LOCATION }, alias = "ACCESS_COARSE_LOCATION"),
        @Permission(strings = { Manifest.permission.ACCESS_FINE_LOCATION }, alias = "ACCESS_FINE_LOCATION"),
        @Permission(strings = { Manifest.permission.BLUETOOTH }, alias = "BLUETOOTH"),
        @Permission(strings = { Manifest.permission.BLUETOOTH_ADMIN }, alias = "BLUETOOTH_ADMIN"),
        @Permission(strings = { Manifest.permission.BLUETOOTH_SCAN }, alias = "BLUETOOTH_SCAN"),
        @Permission(strings = { Manifest.permission.BLUETOOTH_CONNECT }, alias = "BLUETOOTH_CONNECT")
})
public class CapacitorThermalPrinterPlugin extends Plugin implements PrinterObserver {

    private static final String TAG = "CapacitorThermalPrinterPlugin";
    static final List<String> alignments = Arrays.asList("left", "center", "right");
    static final List<String> fonts = Arrays.asList("A", "B");
    static final List<String> placements = Arrays.asList("none", "above", "below", "both");
    static final ESCFontTypeEnum[] fontEnumValues = ESCFontTypeEnum.values();
    static final ESCBarcodeFontTypeEnum[] dataFontEnumValues = ESCBarcodeFontTypeEnum.values();
    static final BarcodeStringPosition[] placementEnumValues = BarcodeStringPosition.values();

    BluetoothManager bluetoothManager = null;
    BluetoothAdapter mBluetoothAdapter = null;
    ArrayList<String> bluetoothPermissions = new ArrayList<>();

    ArrayList<BluetoothDevice> devices = new ArrayList<>();
    BroadcastReceiver mBluetoothReceiver = null;
    boolean mRegistered = false;

    private final Map<String, ConnectionContext> connectionsById = new ConcurrentHashMap<>();
    private final Map<String, ConnectionContext> connectionsByAddress = new ConcurrentHashMap<>();
    private final Map<PrinterInterface, ConnectionContext> connectionsByInterface = new ConcurrentHashMap<>();
    private final Map<String, ConnectionContext> pendingConnectionsByAddress = new ConcurrentHashMap<>();
    private final Map<String, PluginCall> pendingConnectCallsById = new ConcurrentHashMap<>();

    private final ThermalPrinterFactory thermalPrinterFactory = new ThermalPrinterFactory();

    private class ConnectionContext {
        final String connectionId = UUID.randomUUID().toString();
        final BluetoothDevice device;
        final BluetoothEdrConfigBean config;
        final RTPrinter printer;
        final PrinterInterface printerInterface;
        String displayName;
        Cmd cmd;
        TextSetting textSetting;
        BitmapSetting bitmapSetting;
        BarcodeSetting barcodeSetting;
        String encoding = "GBK"; // Default to GBK for best Chinese character support

        ConnectionContext(BluetoothDevice device) {
            this.device = device;
            this.config = new BluetoothEdrConfigBean(device);
            this.printer = thermalPrinterFactory.create();
            this.displayName = device.getName();

            PrinterInterface printerInterface = new BluetoothFactory().create();
            if (printerInterface == null) {
                throw new IllegalStateException("Failed to create printer interface");
            }
            printerInterface.setConfigObject(this.config);
            this.printerInterface = printerInterface;

            this.cmd = new EscCmd();
            this.textSetting = new TextSetting();
            this.bitmapSetting = new BitmapSetting();
            this.barcodeSetting = new BarcodeSetting();
            resetFormattingState();
        }

        void resetFormattingState() {
            this.textSetting = new TextSetting();
            this.bitmapSetting = new BitmapSetting();
            this.barcodeSetting = new BarcodeSetting();
            this.bitmapSetting.setBimtapLimitWidth(48 * 8);
        }

        JSObject toJson() {
            JSObject obj = new JSObject();
            obj.put("connectionId", connectionId);
            obj.put("address", device.getAddress());
            obj.put("name", displayName);
            return obj;
        }
    }

    private class BluetoothDeviceReceiver extends BroadcastReceiver {

        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // When discovery finds a device
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Get the BluetoothDevice object from the Intent
                BluetoothDevice device = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                }
                if (device == null)
                    return;
                int devType = device.getBluetoothClass().getMajorDeviceClass();
                if (devType != BluetoothClass.Device.Major.IMAGING) {
                    return;
                }

                if (!devices.contains(device)) {
                    devices.add(device);
                }

                CapacitorThermalPrinterPlugin.this.notifyListeners(
                        "discoverDevices",
                        new JSObject() {
                            {
                                put("devices", getJsonDevices());
                            }
                        });
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                notifyListeners("discoveryFinish", null);
                mBluetoothAdapter.cancelDiscovery();
                getContext().unregisterReceiver(mBluetoothReceiver);
                mRegistered = false;
            }
        }
    }

    public CapacitorThermalPrinterPlugin() {
        super();
        PrinterObserverManager.getInstance().add(this);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            bluetoothPermissions.add("BLUETOOTH");
            bluetoothPermissions.add("BLUETOOTH_ADMIN");
            bluetoothPermissions.add("ACCESS_FINE_LOCATION");
            bluetoothPermissions.add("ACCESS_COARSE_LOCATION");
        } else {
            bluetoothPermissions.add("BLUETOOTH_CONNECT");
            bluetoothPermissions.add("BLUETOOTH_SCAN");
            bluetoothPermissions.add("ACCESS_FINE_LOCATION");
        }

        Log.d(TAG, "Loading Bluetooth Permissions: " + bluetoothPermissions);
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();

        if (mRegistered && mBluetoothReceiver != null) {
            try {
                getContext().unregisterReceiver(mBluetoothReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver already unregistered.
            }
            mRegistered = false;
            mBluetoothReceiver = null;
        }

        for (ConnectionContext context : connectionsById.values()) {
            try {
                context.printer.disConnect();
            } catch (Exception ignored) {
                // Ignore teardown errors.
            }
        }

        connectionsById.clear();
        connectionsByAddress.clear();
        connectionsByInterface.clear();
        pendingConnectionsByAddress.clear();
        pendingConnectCallsById.clear();

        PrinterObserverManager.getInstance().remove(this);
    }

    private ConnectionContext resolveContext(PluginCall call, boolean requireConnected) {
        String connectionId = call.getString("connectionId");
        ConnectionContext context = null;

        if (connectionId != null) {
            context = connectionsById.get(connectionId);
        } else if (connectionsById.size() == 1) {
            Iterator<ConnectionContext> iterator = connectionsById.values().iterator();
            if (iterator.hasNext()) {
                context = iterator.next();
            }
        }

        if (context == null) {
            call.reject("Unknown printer connection. Provide a valid connectionId.");
            return null;
        }

        if (requireConnected && context.printer.getConnectState() != ConnectStateEnum.Connected) {
            call.reject("Printer is not connected!");
            return null;
        }

        return context;
    }

    private void applyDefaultFormatting(ConnectionContext context) {
        context.resetFormattingState();
        applyAlignment(context, CommonEnum.ALIGN_LEFT);
        applyLineSpacing(context, 30);
        applyCharSpacing(context, 1);
    }

    private void applyAlignment(ConnectionContext context, int alignment) {
        if (alignment > 2 || alignment < 0)
            alignment = 0;

        context.cmd.append(new byte[] { 27, 97, (byte) alignment });
    }

    private void applyLineSpacing(ConnectionContext context, int spacing) {
        if (spacing < 0)
            spacing = 0;
        if (spacing > 255)
            spacing = 255;

        context.cmd.append(new byte[] { 27, 51, (byte) spacing });
    }

    private void applyCharSpacing(ConnectionContext context, int spacing) {
        if (spacing < 0)
            spacing = 0;
        if (spacing > 30)
            spacing = 30;

        context.cmd.append(new byte[] { 27, 32, (byte) spacing });
    }

    private boolean isContextConnected(ConnectionContext context) {
        if (context == null) {
            return false;
        }

        if (context.printer.getConnectState() != ConnectStateEnum.Connected) {
            return false;
        }

        try {
            context.printer.writeMsg(new byte[] {});
        } catch (Exception ignored) {
            return false;
        }

        return context.printer.getConnectState() == ConnectStateEnum.Connected;
    }

    private JSObject buildDisconnectedPayload(ConnectionContext context) {
        JSObject payload = new JSObject();
        payload.put("connectionId", context.connectionId);
        payload.put("address", context.device.getAddress());
        payload.put("name", context.displayName);
        return payload;
    }

    private void removeContext(ConnectionContext context) {
        connectionsById.remove(context.connectionId);
        connectionsByAddress.remove(context.device.getAddress());
        connectionsByInterface.remove(context.printerInterface);
        pendingConnectionsByAddress.remove(context.device.getAddress());
        pendingConnectCallsById.remove(context.connectionId);
    }

    @PluginMethod
    @SuppressLint("MissingPermission")
    public void startScan(PluginCall call) {
        if (!bluetoothCheck(call))
            return;

        if (mRegistered) {
            call.reject("Already Scanning!");
            return;
        }

        devices = new ArrayList<>();
        boolean success = mBluetoothAdapter.startDiscovery();
        mRegistered = success;

        if (success) {
            mBluetoothReceiver = new BluetoothDeviceReceiver();
            IntentFilter mBluetoothIntentFilter = new IntentFilter();
            mBluetoothIntentFilter.addAction(BluetoothDevice.ACTION_FOUND);
            mBluetoothIntentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

            getContext().registerReceiver(mBluetoothReceiver, mBluetoothIntentFilter);
            call.resolve();
        } else {
            call.reject("Failed to start scan!");
        }
    }

    @PluginMethod
    @SuppressLint("MissingPermission")
    public void stopScan(PluginCall call) {
        if (!bluetoothCheck(call))
            return;

        boolean success = mBluetoothAdapter.cancelDiscovery();

        if (success) {
            call.resolve();
        } else {
            call.reject("Failed to stop scan!");
        }
    }

    @PluginMethod
    public void isConnected(PluginCall call) {
        String connectionId = call.getString("connectionId");
        boolean state;

        if (connectionId != null) {
            state = isContextConnected(connectionsById.get(connectionId));
        } else {
            state = false;
            for (ConnectionContext context : connectionsById.values()) {
                if (isContextConnected(context)) {
                    state = true;
                    break;
                }
            }
        }

        boolean finalState = state;
        call.resolve(new JSObject() {
            {
                put("state", finalState);
            }
        });
    }

    @SuppressLint("MissingPermission")
    @PluginMethod
    public void connect(PluginCall call) {
        if (!bluetoothCheck(call))
            return;
        String address = call.getString("address");
        if (address == null) {
            call.reject("Please provide address!");
            return;
        }

        // Get encoding parameter, default to GBK for best Chinese character support
        String encoding = call.getString("encoding", "GBK");
        if (!encoding.equals("GBK") && !encoding.equals("UTF-8")) {
            encoding = "GBK"; // Default to GBK if invalid encoding provided
        }

        ConnectionContext existing = connectionsByAddress.get(address);
        if (existing != null && existing.printer.getConnectState() == ConnectStateEnum.Connected) {
            call.resolve(existing.toJson());
            return;
        }

        if (pendingConnectionsByAddress.containsKey(address)) {
            call.reject("Printer already connecting!");
            return;
        }

        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        Log.d(TAG, "Connecting to " + device + " with encoding: " + encoding);

        ConnectionContext context;
        try {
            context = new ConnectionContext(device);
            context.encoding = encoding; // Set the encoding for this connection
        } catch (IllegalStateException e) {
            call.reject("Failed to create printer interface!");
            return;
        }
        pendingConnectionsByAddress.put(address, context);
        pendingConnectCallsById.put(context.connectionId, call);
        connectionsByInterface.put(context.printerInterface, context);

        context.printer.setPrinterInterface(context.printerInterface);
        try {
            context.printer.connect(context.config);
        } catch (Exception e) {
            removeContext(context);
            call.reject("Failed to connect!");
        }
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        ConnectionContext context = resolveContext(call, false);
        if (context == null) {
            return;
        }

        if (context.printer.getConnectState() == ConnectStateEnum.Connected) {
            context.printer.disConnect();
            call.resolve();
        } else {
            call.reject("Not Connected!");
        }
    }

    @PluginMethod
    public void listConnections(PluginCall call) {
        JSArray array = new JSArray();
        ArrayList<ConnectionContext> disconnectedContexts = new ArrayList<>();

        for (ConnectionContext context : connectionsById.values()) {
            if (!isContextConnected(context)) {
                disconnectedContexts.add(context);
                continue;
            }

            array.put(context.toJson());
        }

        for (ConnectionContext context : disconnectedContexts) {
            JSObject payload = buildDisconnectedPayload(context);
            removeContext(context);
            context.printer.setPrinterInterface(null);
            notifyListeners("disconnected", payload);
        }

        call.resolve(new JSObject() {
            {
                put("connections", array);
            }
        });
    }

    // region Text Formatting
    @PluginMethod
    public void bold(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        context.textSetting.setBold(parseIsEnabled(call));
        call.resolve();
    }

    @PluginMethod
    public void underline(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        context.textSetting.setUnderline(parseIsEnabled(call));
        call.resolve();
    }

    @PluginMethod
    public void doubleWidth(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        context.textSetting.setDoubleWidth(parseIsEnabled(call));
        call.resolve();
    }

    @PluginMethod
    public void doubleHeight(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        context.textSetting.setDoubleHeight(parseIsEnabled(call));
        call.resolve();
    }

    @PluginMethod
    public void inverse(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        context.textSetting.setIsAntiWhite(parseIsEnabled(call));
        call.resolve();
    }

    @PluginMethod
    public void setEncoding(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        String encoding = call.getString("encoding", "GBK");
        if (!encoding.equals("GBK") && !encoding.equals("UTF-8")) {
            call.reject("Invalid encoding. Use 'GBK' or 'UTF-8'.");
            return;
        }

        context.encoding = encoding;
        Log.d(TAG, "Encoding set to: " + encoding + " for connection: " + context.connectionId);
        call.resolve();
    }

    // endregion

    // region Image Formatting
    @PluginMethod
    public void dpi(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        Integer dpi = call.getInt("dpi");
        if (dpi == null) {
            dpi = 0;
        }

        context.bitmapSetting.setBmpDpi(dpi);
        call.resolve();
    }

    @PluginMethod
    public void limitWidth(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        Integer width = call.getInt("width");
        if (width == null) {
            width = 0;
        }

        context.bitmapSetting.setBimtapLimitWidth(width * 8);
        call.resolve();
    }

    // endregion

    // region Hybrid Formatting
    @PluginMethod
    public void align(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        String alignmentName = call.getString("alignment");
        int alignment = alignments.indexOf(alignmentName);
        if (alignment == -1) {
            call.reject("Invalid Alignment");
            return;
        }

        applyAlignment(context, alignment);
        call.resolve();
    }

    @PluginMethod
    public void lineSpacing(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        int spacing = call.getInt("lineSpacing", 0);
        applyLineSpacing(context, spacing);
        call.resolve();
    }

    @PluginMethod
    public void charSpacing(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        int spacing = call.getInt("charSpacing", 0);
        applyCharSpacing(context, spacing);
        call.resolve();
    }

    @PluginMethod
    public void font(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        String fontName = call.getString("font", "A");
        int font = fonts.indexOf(fontName);
        if (font == -1) {
            call.reject("Invalid Font");
            return;
        }

        context.textSetting.setEscFontType(fontEnumValues[font]);
        context.barcodeSetting.setEscBarcodFont(dataFontEnumValues[font]);
        call.resolve();
    }

    @PluginMethod
    public void clearFormatting(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        applyDefaultFormatting(context);
        call.resolve();
    }

    // endregion

    // region Data Code Formatting

    @PluginMethod
    public void barcodeWidth(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        Integer width = call.getInt("width", 0);
        if (width != null)
            context.barcodeSetting.setBarcodeWidth(width);
        call.resolve();
    }

    @PluginMethod
    public void barcodeHeight(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        Integer height = call.getInt("height");
        if (height != null)
            context.barcodeSetting.setHeightInDot(height);

        call.resolve();
    }

    @PluginMethod
    public void barcodeTextPlacement(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        String placementName = call.getString("placement");
        int placement = placements.indexOf(placementName);
        if (placement == -1) {
            call.reject("Invalid Placement");
            return;
        }

        context.barcodeSetting.setBarcodeStringPosition(placementEnumValues[placement]);
        call.resolve();
    }

    // endregion

    // region Content
    @PluginMethod
    public void text(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        String text = call.getString("text");

        try {
            if (text != null)
                context.cmd.append(context.cmd.getTextCmd(context.textSetting, text, context.encoding));
        } catch (UnsupportedEncodingException ignored) {
        }
        call.resolve();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @PluginMethod
    public void image(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        String image = call.getString("image");

        context.bitmapSetting.setBmpPrintMode(BmpPrintMode.MODE_SINGLE_COLOR);

        try {
            if (image != null) {
                byte[] d = Base64.getDecoder().decode(image.substring(image.indexOf(",") + 1));
                context.cmd.append(
                        context.cmd.getBitmapCmd(context.bitmapSetting, BitmapFactory.decodeByteArray(d, 0, d.length)));
            }
        } catch (SdkException ignored) {
        }
        call.resolve();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @PluginMethod
    public void raw(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        String base64 = call.getString("data");
        if (base64 != null) {
            try {
                context.cmd.append(Base64.getDecoder().decode(base64));
            } catch (Exception ignored) {
                call.reject("Invalid Base64");
                return;
            }
            call.resolve();
            return;
        }

        JSArray dataArray = call.getArray("data");
        if (dataArray == null) {
            call.reject("Invalid Data");
            return;
        }

        byte[] data = new byte[dataArray.length()];
        for (int i = 0; i < dataArray.length(); i++) {
            try {
                data[i] = (byte) (dataArray.getInt(i) & 0xff);
            } catch (JSONException e) {
                call.reject("Invalid Data");
                return;
            }
        }

        context.cmd.append(data);
        call.resolve();
    }

    @PluginMethod
    public void qr(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        String data = call.getString("data", "");
        try {
            context.cmd.append(context.cmd.getBarcodeCmd(BarcodeType.QR_CODE, context.barcodeSetting, data));
        } catch (SdkException ignored) {
        }
        call.resolve();
    }

    @PluginMethod
    public void barcode(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        String typeName = call.getString("type");

        BarcodeType type;
        try {
            type = BarcodeType.valueOf(typeName);
        } catch (Exception ignored) {
            call.reject("Invalid Type");
            return;
        }

        if (type == BarcodeType.QR_CODE) {
            call.reject("Invalid Type");
            return;
        }
        String data = call.getString("data", "");

        try {
            context.cmd.append(context.cmd.getBarcodeCmd(type, context.barcodeSetting, data));
        } catch (SdkException ignored) {
        }

        call.resolve();
    }

    @PluginMethod
    public void selfTest(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        context.cmd.append(context.cmd.getSelfTestCmd());
        call.resolve();
    }

    // endregion

    // region Content Actions
    @PluginMethod
    public void beep(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        context.cmd.append(context.cmd.getBeepCmd());
        call.resolve();
    }

    @PluginMethod
    public void openDrawer(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        // ESC p m t1 t2 - Standard ESC/POS cash drawer kick command
        // 0x1B = ESC, 0x70 = p, 0x00 = drawer pin 2
        // 0x32 = pulse ON time (50 * 2ms = 100ms), 0x7D = pulse OFF time (125 * 2ms =
        // 250ms)
        context.cmd.append(new byte[] { 0x1B, 0x70, 0x00, 0x32, 0x7D });
        call.resolve();
    }

    @PluginMethod
    public void cutPaper(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        boolean half = Boolean.TRUE.equals(call.getBoolean("half", false));
        context.cmd.append(half ? context.cmd.getHalfCutCmd() : context.cmd.getAllCutCmd());

        call.resolve();
    }

    @PluginMethod
    public void feedCutPaper(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        boolean half = Boolean.TRUE.equals(call.getBoolean("half", false));
        context.cmd.append(new byte[] { (byte) '\n' });
        context.cmd.append(half ? context.cmd.getHalfCutCmd() : context.cmd.getAllCutCmd());
        call.resolve();
    }

    // endregion

    // region Printing Actions
    @PluginMethod
    public void begin(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        context.cmd = new EscCmd();
        applyDefaultFormatting(context);
        call.resolve();
    }

    @PluginMethod
    public void write(PluginCall call) {
        ConnectionContext context = resolveContext(call, true);
        if (context == null)
            return;

        _writeRaw(call, context, context.cmd.getAppendCmds());
    }

    // endregion

    // region Utils
    SettingEnum parseIsEnabled(PluginCall call) {
        if ("default".equals(call.getString("enabled")))
            return SettingEnum.NoSetting;

        Boolean enabled = call.getBoolean("enabled", true);
        if (enabled == null)
            return SettingEnum.NoSetting;

        return enabled ? SettingEnum.Enable : SettingEnum.Disable;
    }

    private void _writeRaw(PluginCall call, ConnectionContext context, byte[] data) {
        PrinterInterface printerInterface = context.printer.getPrinterInterface();
        if (printerInterface == null || printerInterface.getConnectState() != ConnectStateEnum.Connected) {
            call.reject("Printer is not connected!");
            return;
        }

        CmdFactory escFac = new EscFactory();
        Cmd escCmd = escFac.create();
        escCmd.append(escCmd.getHeaderCmd());
        escCmd.setChartsetName(context.encoding);
        escCmd.append(data);
        escCmd.append(escCmd.getLFCRCmd());
        escCmd.append(escCmd.getLFCRCmd());
        escCmd.append(escCmd.getLFCRCmd());
        escCmd.append(escCmd.getEndCmd());
        context.printer.writeMsgAsync(escCmd.getAppendCmds());
        call.resolve();
    }

    private boolean bluetoothCheck(PluginCall call) {
        if (bluetoothManager == null) {
            bluetoothManager = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = bluetoothManager.getAdapter();
        }

        Log.d(TAG, "Has Bluetooth Permissions: " + hasBluetoothPermission());
        if (!hasBluetoothPermission()) {
            requestPermissionForAliases(bluetoothPermissions.toArray(new String[] {}), call, "permissionCallback");

            return false;
        }

        Log.d(TAG, "Is Bluetooth Enabled: " + mBluetoothAdapter.isEnabled());
        if (mBluetoothAdapter.isEnabled()) {
            return true;
        }

        call.reject("Please enable bluetooth!");
        return false;
    }

    private boolean hasBluetoothPermission() {
        // return getPermissionState("bluetooth") == PermissionState.GRANTED;
        for (String permission : bluetoothPermissions) {
            if (getPermissionState(permission) != PermissionState.GRANTED) {
                return false;
            }
        }

        return true;
    }

    @PermissionCallback
    protected void permissionCallback(PluginCall call) {
        if (hasBluetoothPermission()) {
            try {
                CapacitorThermalPrinterPlugin.class.getMethod(call.getMethodName(), PluginCall.class).invoke(this,
                        call);
            } catch (Exception e) {
                call.reject("Bluetooth method doesn't exit?!");
            }
        } else {
            call.reject("Permission is required to continue!");
        }
    }

    @SuppressLint("MissingPermission")
    private JSArray getJsonDevices() {
        JSArray array = new JSArray();
        for (BluetoothDevice device : devices) {
            array.put(
                    new JSObject() {
                        {
                            put("name", device.getName());
                            put("address", device.getAddress());
                        }
                    });
        }

        return array;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void printerObserverCallback(PrinterInterface printerInterface, int state) {
        if (printerInterface == null) {
            return;
        }

        ConnectionContext context = connectionsByInterface.get(printerInterface);
        if (context == null) {
            return;
        }

        Log.d(TAG, "STATE CHANGE " + state + " for " + context.device.getAddress());
        switch (state) {
            case CommonEnum.CONNECT_STATE_SUCCESS:
                context.printer.setPrinterInterface(printerInterface);
                BluetoothEdrConfigBean config = (BluetoothEdrConfigBean) printerInterface.getConfigObject();
                if (config != null && config.mBluetoothDevice != null) {
                    context.displayName = config.mBluetoothDevice.getName();
                }
                connectionsById.put(context.connectionId, context);
                connectionsByAddress.put(context.device.getAddress(), context);
                pendingConnectionsByAddress.remove(context.device.getAddress());

                PluginCall pendingCall = pendingConnectCallsById.remove(context.connectionId);
                JSObject connectedPayload = context.toJson();
                if (pendingCall != null) {
                    pendingCall.resolve(connectedPayload);
                }

                notifyListeners("connected", connectedPayload);
                break;
            case CommonEnum.CONNECT_STATE_INTERRUPTED:
                boolean pending = pendingConnectCallsById.containsKey(context.connectionId);
                PluginCall pendingConnect = pendingConnectCallsById.remove(context.connectionId);

                JSObject disconnectedPayload = buildDisconnectedPayload(context);

                removeContext(context);
                context.printer.setPrinterInterface(null);
                connectionsByInterface.remove(printerInterface);

                if (pending) {
                    if (pendingConnect != null) {
                        pendingConnect.resolve(null);
                    }
                } else {
                    notifyListeners("disconnected", disconnectedPayload);
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void printerReadMsgCallback(PrinterInterface printerInterface, byte[] bytes) {
    }
    // endregion
}
