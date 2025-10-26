[**capacitor-thermal-printer**](../README.md) • **Docs**

***

[capacitor-thermal-printer](../README.md) / PrinterConnection

# Interface: PrinterConnection

Represents an active printer connection. Extends the core [`BluetoothDevice`](BluetoothDevice.md) metadata with the identifier required to address the connection from JavaScript.

## Properties

### address

> **address**: `string`

Bluetooth MAC address / peripheral UUID of the printer.

---

### connectionId

> **connectionId**: `string`

Opaque identifier for the active connection. Pass this to [`CapacitorThermalPrinterPlugin.useConnection`](CapacitorThermalPrinterPlugin.md#useconnection) or other multi-connection helpers.

---

### name

> **name**: `string`

Human-friendly device name if available.
