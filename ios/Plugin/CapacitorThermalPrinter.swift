import Foundation
import Capacitor
import CoreBluetooth

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */
@objc(CapacitorThermalPrinterPlugin)
public class CapacitorThermalPrinterPlugin: CAPPlugin {
    let fonts = ["A", "B"];
    let alignments = ["left", "center", "right"];
    let placements = ["none", "above", "below", "both"];
    let barcodeTypes = [
        "UPC_A",
        "EAN8",
        "EAN13",
        "CODE39",
        "ITF",
        "CODABAR",
        "CODE128",
    ];
    let fontEnumValues = [ESCFontType_FontA, ESCFontType_FontB];
    let placementEnumValues = [BarcodeHRIpos_noprint, BarcodeHRIpos_above, BarcodeHRIpos_Below, BarcodeHRIpos_both];
    let barcodeTypeEnumValues = [
        BarcodeTypeUPC_A,
        BarcodeTypeEAN8,
        BarcodeTypeEAN13,
        BarcodeTypeCODE39,
        BarcodeTypeITF,
        BarcodeTypeCODABAR,
        BarcodeTypeCODE128,
    ];
    let blueToothPI = BlueToothFactory.create(BlueToothKind_Ble)!

    var isScanning = false;
    var discoveryFinish: DispatchWorkItem?;
    var connectionsById: [String: PrinterConnectionContext] = [:]
    var connectionsByAddress: [String: PrinterConnectionContext] = [:]
    var pendingConnectionsByAddress: [String: PrinterConnectionContext] = [:]
    var pendingCallsById: [String: CAPPluginCall] = [:]
    var connectTimeouts: [String: DispatchWorkItem] = [:]

    class PrinterConnectionContext {
        let connectionId = UUID().uuidString
        let address: String
        let manager: PrinterManager
        let bluetoothPI: RTBlueToothPI
        var name: String?
    var cmd: ESCCmd = ESCCmd()
    var textSetting: TextSetting = TextSetting()
    var bitmapSetting: BitmapSetting = BitmapSetting()
    var dataCodeSetting: BarcodeSetting = BarcodeSetting()
    var dpiUnits: Int = 8

        init(address: String) {
            self.address = address
            self.manager = PrinterManager.createESC()
            self.bluetoothPI = BlueToothFactory.create(BlueToothKind_Ble)!
            resetCommand()
            resetFormattingState()
        }

        func resetCommand() {
            cmd = ESCCmd()
            cmd.encodingType = Encoding_UTF8
        }

        func resetFormattingState() {
            textSetting = TextSetting()
            textSetting.alignmode = Align_NoSetting
            textSetting.isTimes_Wide = Set_DisEnable
            textSetting.isTimes_Heigh = Set_DisEnable
            textSetting.isUnderline = Set_DisEnable
            textSetting.isBold = Set_DisEnable

            bitmapSetting = BitmapSetting()
            bitmapSetting.alignmode = Align_NoSetting
            bitmapSetting.limitWidth = 48 * 8

            dataCodeSetting = BarcodeSetting()
            dataCodeSetting.alignmode = Align_NoSetting
            dataCodeSetting.coord.width = 3
            dataCodeSetting.coord.height = 72
            dataCodeSetting.high = 25
            dpiUnits = 8
        }

        func toDictionary() -> [String: Any?] {
            return [
                "connectionId": connectionId,
                "address": address,
                "name": name
            ]
        }
    }
    @objc func listConnections(_ call: CAPPluginCall) {
        var activeConnections: [[String: Any?]] = []
        var disconnected: [PrinterConnectionContext] = []

        for context in connectionsById.values {
            if isContextConnected(context) {
                activeConnections.append(context.toDictionary())
            } else {
                disconnected.append(context)
            }
        }

        for context in disconnected {
            context.manager.currentPrinter.close()
            removeContext(context)
            notifyListeners("disconnected", data: disconnectedPayload(context))
        }

        call.resolve(["connections": activeConnections])
    }

    func context(for call: CAPPluginCall, requireConnected: Bool = true) -> PrinterConnectionContext? {
        var context: PrinterConnectionContext?
        if let connectionId = call.getString("connectionId") {
            context = connectionsById[connectionId]
        } else if connectionsById.count == 1 {
            context = connectionsById.values.first
        }

        guard let resolved = context else {
            call.reject("Unknown printer connection. Provide a valid connectionId.")
            return nil
        }

        if requireConnected && !resolved.manager.currentPrinter.isOpen {
            call.reject("Printer is not connected!")
            return nil
        }

        return resolved
    }

    func applyDefaultFormatting(_ context: PrinterConnectionContext) {
        context.resetFormattingState()
        appendAlignment(context, alignment: 0)
        appendLineSpacing(context, spacing: 30)
        appendCharSpacing(context, spacing: 1)
    }

    func appendAlignment(_ context: PrinterConnectionContext, alignment: Int) {
        var alignment = alignment
        if alignment > 2 || alignment < 0 {
            alignment = 0
        }
        context.cmd.append(Data([27, 97, UInt8(alignment)]))
    }

    func appendLineSpacing(_ context: PrinterConnectionContext, spacing: Int) {
        var spacing = spacing
        if spacing < 0 { spacing = 0 }
        if spacing > 255 { spacing = 255 }
        context.cmd.append(Data([27, 51, UInt8(spacing)]))
    }

    func appendCharSpacing(_ context: PrinterConnectionContext, spacing: Int) {
        var spacing = spacing
        if spacing < 0 { spacing = 0 }
        if spacing > 30 { spacing = 30 }
        context.cmd.append(Data([27, 32, UInt8(spacing)]))
    }

    func isContextConnected(_ context: PrinterConnectionContext?) -> Bool {
        guard let context = context else { return false }
        guard context.manager.currentPrinter.isOpen else { return false }

        context.manager.currentPrinter.write(Data())
        return context.manager.currentPrinter.isOpen
    }

    func disconnectedPayload(_ context: PrinterConnectionContext) -> [String: Any?] {
        return [
            "connectionId": context.connectionId,
            "address": context.address,
            "name": context.name
        ]
    }

    func removeContext(_ context: PrinterConnectionContext) {
        connectionsById.removeValue(forKey: context.connectionId)
        connectionsByAddress.removeValue(forKey: context.address)
        pendingConnectionsByAddress.removeValue(forKey: context.address)
        if let timeout = connectTimeouts.removeValue(forKey: context.connectionId) {
            timeout.cancel()
        }
        pendingCallsById.removeValue(forKey: context.connectionId)
    }
    
    public override init() {
        super.init()
        
        let handleNotificationSelector = #selector(self.handleNotification(notification: ));
        
        let notifications = [
            NSNotification.Name(BleDeviceConnectedNotify),
            NSNotification.Name(BleServiceFindDevice),
            //                NSNotification.Name(BleDeviceRssiChanged),
            NSNotification.Name(BleServiceStatusChanged),
            NSNotification.Name.PrinterConnected,
            NSNotification.Name.PrinterDisconnected,
            NSNotification.Name(BleDeviceDataChanged)
        ];
        for notification in notifications {
            NSLog("Observing Notifications %@", notification.rawValue)
            NotificationCenter.default.addObserver(
                self,
                selector: handleNotificationSelector,
                name: notification,
                object: nil
            )
        }
    }

    @objc
    func handleNotification(notification: Notification) {
        DispatchQueue.main.async {
            switch notification.name {
            case NSNotification.Name(BleServiceFindDevice):
                let devices = self.blueToothPI.getBleDevicelist() as! [RTDeviceinfo]
                self.notifyListeners("discoverDevices", data: [
                    "devices": devices.map({
                        return [
                            "name": $0.name,
                            "address": $0.uuidString
                        ]
                    })
                ])
                break;
            case NSNotification.Name.PrinterConnected:
                guard
                    let observer = notification.object as? ObserverObj,
                    let pi = observer.msgobj as? RTBlueToothPI,
                    let address = pi.address
                else { break }

                let context = self.pendingConnectionsByAddress.removeValue(forKey: address) ?? self.connectionsByAddress[address]
                guard let context = context else { break }

                context.name = pi.name
                if let timeout = self.connectTimeouts.removeValue(forKey: context.connectionId) {
                    timeout.cancel()
                }

                if let call = self.pendingCallsById.removeValue(forKey: context.connectionId) {
                    call.resolve(context.toDictionary())
                }

                self.connectionsById[context.connectionId] = context
                self.connectionsByAddress[address] = context

                self.notifyListeners("connected", data: context.toDictionary())
                break;
            case NSNotification.Name.PrinterDisconnected:
                guard
                    let observer = notification.object as? ObserverObj,
                    let pi = observer.msgobj as? RTBlueToothPI,
                    let address = pi.address
                else { break }

                if let pendingContext = self.pendingConnectionsByAddress.removeValue(forKey: address) {
                    if let timeout = self.connectTimeouts.removeValue(forKey: pendingContext.connectionId) {
                        timeout.cancel()
                    }
                    self.pendingCallsById.removeValue(forKey: pendingContext.connectionId)?.resolve()
                    pendingContext.manager.currentPrinter.close()
                    self.removeContext(pendingContext)
                } else if let context = self.connectionsByAddress[address] {
                    context.manager.currentPrinter.close()
                    self.removeContext(context)
                    self.notifyListeners("disconnected", data: self.disconnectedPayload(context))
                }
                break;
            default:
                break;
            }
        }
        
    }
    
    @objc func startScan(_ call: CAPPluginCall) {
        if (isScanning) {
            call.reject("Already Scanning!");
            return;
        }

        isScanning = true;
        blueToothPI.startScan(30, isclear: true)
        
        discoveryFinish = DispatchWorkItem(block: {
            if  self.discoveryFinish == nil ||
                self.discoveryFinish!.isCancelled {
                return
            }
            
            self.discoveryFinish!.cancel();
    
            self.discoveryFinish = nil;
            self.isScanning = false;
            self.notifyListeners("discoveryFinish", data: nil);
        })

        DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: discoveryFinish!);
        
        call.resolve()
    }
    @objc func stopScan(_ call: CAPPluginCall) {
        blueToothPI.stopScan()

        discoveryFinish?.perform()
        call.resolve()
    }

    @objc func isConnected(_ call: CAPPluginCall) {
        if let connectionId = call.getString("connectionId") {
            let state = isContextConnected(connectionsById[connectionId])
            call.resolve(["state": state])
            return
        }

        let state = connectionsById.values.contains { self.isContextConnected($0) }
        call.resolve(["state": state])
    }

    @objc func connect(_ call: CAPPluginCall) {
        guard let address = call.getString("address") else {
            call.reject("Please provide address!")
            return
        }

        if let existing = connectionsByAddress[address], existing.manager.currentPrinter.isOpen {
            call.resolve(existing.toDictionary())
            return
        }

        if pendingConnectionsByAddress[address] != nil {
            call.reject("Printer already connecting!")
            return
        }

        let context = PrinterConnectionContext(address: address)
        pendingConnectionsByAddress[address] = context
        pendingCallsById[context.connectionId] = call

        context.manager.connectBLE2(address: address, blueToothPI: context.bluetoothPI)

        let timeout = DispatchWorkItem { [weak self] in
            guard let self = self else { return }

            if let pending = self.pendingConnectionsByAddress.removeValue(forKey: address) {
                self.pendingCallsById.removeValue(forKey: pending.connectionId)?.resolve()
                pending.manager.currentPrinter.close()
                self.removeContext(pending)
            }
        }

        connectTimeouts[context.connectionId] = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: timeout)
    }
    @objc func disconnect(_ call: CAPPluginCall) {
        guard let context = context(for: call, requireConnected: false) else { return }

        if context.manager.currentPrinter.isOpen {
            blueToothPI.stopScan()
            discoveryFinish?.perform()

            let devices = self.blueToothPI.getBleDevicelist() as! [RTDeviceinfo]
            let exists = devices.contains(where: { $0.uuidString == context.manager.currentPrinter.printerPi.address })

            context.manager.disconnect()

            if !exists {
                context.manager.currentPrinter.close()
                self.removeContext(context)
                self.notifyListeners("disconnected", data: self.disconnectedPayload(context))
            } else {
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
                    guard let self = self else { return }
                    if self.connectionsById[context.connectionId] != nil && !context.manager.currentPrinter.isOpen {
                        context.manager.currentPrinter.close()
                        self.removeContext(context)
                        self.notifyListeners("disconnected", data: self.disconnectedPayload(context))
                    }
                }
            }

            call.resolve()
        } else {
            self.removeContext(context)
            call.reject("Not Connected!")
        }
    }
    
    // MARK: - Text Formatting
    @objc func bold(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        context.textSetting.isBold = parseIsEnabled(call)
        call.resolve()
    }
    @objc func underline(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        context.textSetting.isUnderline = parseIsEnabled(call)
        call.resolve()
    }
    @objc func doubleWidth(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        context.textSetting.isTimes_Wide = parseIsEnabled(call)
        call.resolve()
    }
    @objc func doubleHeight(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        context.textSetting.isTimes_Heigh = parseIsEnabled(call)
        call.resolve()
    }
    @objc func inverse(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        context.textSetting.isInverse = parseIsEnabled(call)
        call.resolve()
    }
    
    // MARK: - Image Formatting
    @objc func dpi(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }

        let dpi = call.getInt("dpi")
        context.dpiUnits = dpi == 300 ? 12 : 8
        call.resolve()
    }
    @objc func limitWidth(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }

        let width = call.getInt("width", 1)
        context.bitmapSetting.limitWidth = width * context.dpiUnits
        call.resolve()
    }

    // MARK: - Data Code Formatting
    @objc func barcodeWidth(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        let width = call.getInt("width", 0)
        context.dataCodeSetting.coord.width = width
        call.resolve()
    }
    @objc func barcodeHeight(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        let height = call.getInt("height", 0)
        context.dataCodeSetting.coord.height = height
        call.resolve()
    }
    @objc func barcodeTextPlacement(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        let placement = placements.firstIndex(of: call.getString("placement", "none"))
        if let placement = placement {
            context.dataCodeSetting.hriPos = placementEnumValues[placement]
            call.resolve()
        } else {
            call.reject("Invalid Placement");
        }
    }

    // MARK: - Hybrid Formatting
    @objc func align(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        let alignmentName = call.getString("alignment", "left")
        let alignment = alignments.firstIndex(of: alignmentName)
        if let alignment = alignment {
            appendAlignment(context, alignment: alignment)
            call.resolve()
        } else {
            call.reject("Invalid Alignment");
        }
    }
    @objc func lineSpacing(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        let spacing = call.getInt("lineSpacing", 0)
        appendLineSpacing(context, spacing: spacing)
        call.resolve()
    }
    @objc func charSpacing(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        let spacing = call.getInt("charSpacing", 0)
        appendCharSpacing(context, spacing: spacing)
        call.resolve()
    }
    
    @objc func font(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        let font = fonts.firstIndex(of: call.getString("font", "A"))
        if let font = font {
            context.textSetting.escFonttype = fontEnumValues[font]
            context.dataCodeSetting.hriFonttype = fontEnumValues[font]
            call.resolve()
        } else {
            call.reject("Invalid Font");
        }
    }
    @objc func clearFormatting(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        applyDefaultFormatting(context)
        call.resolve()
    }
    
    // MARK: - Content
    @objc func text(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        let text = call.getString("text", "")
        context.cmd.append(context.cmd.getTextCmd(context.textSetting, text: text))
        call.resolve()
    }
    @objc func image(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        let dataurl = call.getString("image", "")

        let base64: String
        if let i = dataurl.firstIndex(of: ",") {
            base64 = String(dataurl[dataurl.index(after: i)...])
        } else {
            base64 = dataurl
        }

        let data = Data(base64Encoded: base64)
        if let data = data, let img = UIImage(data: data) {
            context.cmd.append(context.cmd.getBitMapCmd(context.bitmapSetting, image: img))
            call.resolve()
        } else {
            call.reject("Invalid Image")
        }
    }
    
    @objc func qr(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        let data = call.getString("data", "")

        let error: UnsafeMutablePointer<PrinterCodeError> = UnsafeMutablePointer.allocate(capacity: 1)
        let bytes = context.cmd.getBarCodeCmd(context.dataCodeSetting, codeType: BarcodeTypeQrcode, scode: data, codeError: error)

        error.deallocate()

        context.cmd.append(bytes)
        call.resolve()
    }
    @objc func barcode(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        guard let type = barcodeTypes.firstIndex(of: call.getString("type", "")) else {
            call.reject("Invalid Type")
            return
        }
        let data = call.getString("data", "")

        let error: UnsafeMutablePointer<PrinterCodeError> = UnsafeMutablePointer.allocate(capacity: 1)
        let bytes = context.cmd.getBarCodeCmd(context.dataCodeSetting, codeType: barcodeTypeEnumValues[type], scode: data, codeError: error)

        error.deallocate()

        context.cmd.append(bytes)
        call.resolve()
    }
    
    @objc func raw(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        let base64 = call.getString("data");
        if let base64 = base64 {
            if let data = Data(base64Encoded: base64) {
                context.cmd.append(data)
                call.resolve()
            } else {
                call.reject("Invalid Data")
            }
            return;
        }
        guard let dataArray = call.getArray("data") as! [NSNumber]? else { call.reject("Invalid Data"); return }
        
        var data = Data(count: dataArray.count)
        for i in 0..<data.count {
            data[i] = UInt8(truncating: dataArray[i]);
        }

        context.cmd.append(data)
        call.resolve()
    }
    @objc func selfTest(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        context.cmd.append(context.cmd.getSelftestCmd())
        call.resolve()
    }
    // MARK: - Content Actions
    @objc func beep(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        context.cmd.append(context.cmd.getBeepCmd(1, interval: 3))
        call.resolve()
    }
    @objc func openDrawer(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        // ESC p m t1 t2 - Standard ESC/POS cash drawer kick command
        // 0x1B = ESC, 0x70 = p, 0x00 = drawer pin 2
        // 0x32 = pulse ON time (50 * 2ms = 100ms), 0x7D = pulse OFF time (125 * 2ms = 250ms)
        context.cmd.append(Data([0x1B, 0x70, 0x00, 0x32, 0x7D]))
        call.resolve()
    }
    @objc func cutPaper(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        let half = call.getBool("half", false)
        context.cmd.append(context.cmd.getCutPaperCmd(half ? CutterMode_half : CutterMode_Full))
        call.resolve()
    }
    @objc func feedCutPaper(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        let half = call.getBool("half", false)
        context.cmd.append(Data([10]))
        context.cmd.append(context.cmd.getCutPaperCmd(half ? CutterMode_half : CutterMode_Full))
        call.resolve()
    }

    // MARK: Printing Actions
    @objc func begin(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        context.resetCommand()
        applyDefaultFormatting(context)
        call.resolve()
    }
    @objc func write(_ call: CAPPluginCall) {
        guard let context = context(for: call) else { return }
        _writeRaw(call, context: context, data: context.cmd.getCmd())
    }
    
    // MARK: - Utils
    func _writeRaw(_ call: CAPPluginCall, context: PrinterConnectionContext, data: Data?) {
        if !context.manager.currentPrinter.isOpen {
            call.reject("Printer is not connected!")
            return
        }

        let escCmd = context.manager.createCmdClass()
        escCmd.clear()
        escCmd.encodingType = Encoding_UTF8
        escCmd.append(escCmd.getHeaderCmd())
        escCmd.append(data)
        escCmd.append(escCmd.getLFCRCmd())
        escCmd.append(escCmd.getPrintEnd()!)
        
        NSLog("Printing to %@", context.manager.currentPrinter.printerPi.address)
        context.manager.currentPrinter.write(escCmd.getCmd())
        call.resolve()
    }
    func parseIsEnabled(_ call: CAPPluginCall) -> SettingMode {
        if (call.getString("enabled") == "default") {
            return Set_NoSetting
        }

        return call.getBool("enabled", true) ? Set_Enabled: Set_DisEnable;
    }
}
