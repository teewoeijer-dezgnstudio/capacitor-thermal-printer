<h1 align="center">
  <img
    src="./assets/Logo.png"
    width="64"
    valign="middle"
  />
  <code>capacitor-thermal-printer-bluetooth</code>
  <br>
  <img src="https://img.shields.io/badge/bluetooth-6796f9?&logo=bluetooth&logoColor=white">
  <img src="https://img.shields.io/badge/Capacitor-119EFF?logo=Capacitor&logoColor=white">
  <img src="https://img.shields.io/badge/TypeScript-007ACC?logo=typescript&logoColor=white">
  <img src="https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white">
  <img src="https://img.shields.io/badge/iOS-157EFB?logo=apple&logoColor=white">
  <br>
  <img src="https://img.shields.io/github/license/teewoeijer/capacitor-thermal-printer?color=orange">

</h1>

High-speed, reliable bluetooth ESC thermal printer and encoder Capacitor plugin. Both on Android and iOS!

- [x] Using the official RTPrinter SDK by [Rongta Technology](https://www.rongtatech.com/) <3
- [x] Cross-platform compatibility (Android & iOS)
- [x] **Multiple simultaneous Bluetooth connections** — connect and manage multiple printers at once
- [x] **_Swift_** speeds on iOS of [BLE](https://en.wikipedia.org/wiki/Bluetooth_Low_Energy) **(known for it's pain-in-the-ass speed)**

## Install

```bash
npm install capacitor-thermal-printer-bluetooth --save
npx cap sync
```

### Additional iOS Setup

<img src="./assets/ios-include.png" />

Open your iOS project in Xcode, then:

1. In the left sidebar, select your project (usually named "App").
2. Select your main target (usually also named "App").
3. Navigate to "Build Phases" tab.
4. Under "Copy Bundle Resources", click the "+" button and choose "Add Other..."
5. Navigate to the `node_modules/capacitor-thermal-printer-bluetooth/ios/Plugin/Resources/ble_serial.plist` file and select it

And voilà! You're all set!

## Example

Make sure to check the Ionic Angular example in the [example](./example) folder.

### 1. Import the plugin

```ts
import { CapacitorThermalPrinter } from 'capacitor-thermal-printer-bluetooth';
```

### 2. Connect to printer

```ts
const device = await CapacitorThermalPrinter.connect({
  address: 'XX:XX:XX:XX:XX:XX',
});
if (device === null) {
  console.log('Woops, failed to connect!');
} else {
    console.log('Connected!', device.name, device.address);
}
```

You can also use the `startScan` method to discover nearby devices.

- On Android, only printers will be discovered.
- On iOS, all bluetooth devices will be discovered.

```ts
CapacitorThermalPrinter.addListener('discoverDevices', (devices) => {
  console.log('Discovered devices list:', devices);
});

await CapacitorThermalPrinter.startScan();
```

### Manage Multiple Printers (New!)

**Version 0.2.8+** introduces support for managing multiple simultaneous Bluetooth connections. You can now connect to several printers at once and route print jobs independently to each device.

The plugin maintains separate connection contexts per printer. Each successful `connect` call returns a unique `connectionId` that you can use to direct print jobs.

**Quick example:**

```ts
// Connect to multiple printers
const kitchen = await CapacitorThermalPrinter.connect({ address: 'AA:BB:CC:DD:EE:01' });
const bar = await CapacitorThermalPrinter.connect({ address: 'AA:BB:CC:DD:EE:02' });

// Create scoped sessions for each printer
const kitchenPrinter = CapacitorThermalPrinter.useConnection(kitchen!.connectionId);
const barPrinter = CapacitorThermalPrinter.useConnection(bar!.connectionId);

// Send different content to each printer independently
await kitchenPrinter.begin().text('Kitchen Order #42\n').write();
await barPrinter.begin().text('Bar Order #15\n').write();

// List all active connections
const { connections } = await CapacitorThermalPrinter.listConnections();
console.log(`Connected to ${connections.length} printers`);

// Optionally set a default printer for direct method calls
CapacitorThermalPrinter.setActiveConnection(bar!.connectionId);
await CapacitorThermalPrinter.begin().text('Uses bar printer').write();
```

**Key APIs:**

- `listConnections()` — Returns all active printer connections with their IDs, addresses, and names.
- `useConnection(connectionId)` — Creates a scoped session bound to a specific printer.
- `setActiveConnection(connectionId)` — Sets the default printer for direct plugin method calls.
- `getActiveConnection()` — Returns the currently active connection ID (or null).
- `disconnect({ connectionId })` — Disconnects a specific printer (or the active one if omitted).

**Events:**

The plugin emits `connected` and `disconnected` events with the `connectionId` so you can track connection state changes per device:

```ts
CapacitorThermalPrinter.addListener('connected', (device) => {
  console.log(`Printer connected: ${device.name} (${device.connectionId})`);
});

CapacitorThermalPrinter.addListener('disconnected', (data) => {
  console.log(`Printer disconnected: ${data.connectionId}`);
});
```

### 3. Print sample receipt

```ts
await CapacitorThermalPrinter.begin()
  .align('center')

  .image('./assets/Logo-Black.png')

  .bold()
  .underline()
  .text('The amazing store\n')

  .doubleWidth()
  .text('RECEIPT\n')
  .clearFormatting()

  .text('Item 1: $10.00\n')
  .text('Item 2: $15.00\n')

  .align('right')
  .text('Total: $25.00\n')

  .align('center')
  .qr('https://example.com')
  .barcode('UPC_A', '123456789012')

  .cutPaper()

  .write()
  .then(() => console.log('Printed!'))
  .catch((e) => console.error('Failed to print!', e));
```

## Documentation

Check out the [Docs](./docs/README.md)!

## Issues

If you encounter any issues with this plugin, please report them at [Issues](https://github.com/teewoeijer/capacitor-thermal-printer/issues)

## Contributing

We're open to, and grateful for, any contributions made! Make sure to check [Contribution Guidelines](./CONTRIBUTING.md)
