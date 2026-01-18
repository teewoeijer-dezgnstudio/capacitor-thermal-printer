import { registerPlugin } from '@capacitor/core';

import type {
  Base64Encodable,
  CapacitorThermalPrinterPlugin,
  DisconnectOptions,
  IsConnectedOptions,
  PrinterConnection,
  PrinterSession,
} from './definitions';
import { WrappedMethodsArgsMap, WrappedMethodsMiddlewareMap } from './private-definitions';
import CallablePromise from './utils/CallablePromise';
import Encoding from './utils/Encoding';

const CapacitorThermalPrinterImplementation = registerPlugin<any>('CapacitorThermalPrinter');

const wrappedMethodsArgNames = {
  //#region Text Formatting
  bold: ['enabled'],
  underline: ['enabled'],
  doubleWidth: ['enabled'],
  doubleHeight: ['enabled'],
  inverse: ['enabled'],
  //#endregion

  //#region Image Formatting
  dpi: ['dpi'],
  limitWidth: ['width'],
  //#endregion

  //#region Data Code Formatting
  barcodeWidth: ['width'],
  barcodeHeight: ['height'],
  barcodeTextPlacement: ['placement'],
  //#endregion

  //#region Hybrid Formatting
  align: ['alignment'],
  charSpacing: ['charSpacing'],
  lineSpacing: ['lineSpacing'],
  font: ['font'],
  clearFormatting: [],
  setEncoding: ['encoding'],
  //#endregion

  //#region Content
  text: ['text'],
  image: ['image'],
  qr: ['data'],
  barcode: ['type', 'data'],
  raw: ['data'],
  selfTest: [],
  //#endregion

  //#region Content Actions
  beep: [],
  openDrawer: [],
  cutPaper: ['half'],
  feedCutPaper: ['half'],
  //#endregion

  //#region Printing Actions
  begin: [],
  write: [],
  //#endregion
} as const satisfies WrappedMethodsArgsMap;

const wrappedMethodsMiddleware = {
  async image(data: Base64Encodable) {
    return { image: await Encoding.toBase64(data) };
  },
  async raw(data: Base64Encodable) {
    return { data: await Encoding.toBase64(data) };
  },
} as const satisfies WrappedMethodsMiddlewareMap<typeof wrappedMethodsArgNames>;

function mapArgs(key: string, args: any[]) {
  if (key in wrappedMethodsMiddleware) {
    // eslint-disable-next-line @typescript-eslint/ban-ts-comment
    // @ts-ignore
    return wrappedMethodsMiddleware[key](...args);
  }
  // eslint-disable-next-line @typescript-eslint/ban-ts-comment
  // @ts-ignore
  const argNames = wrappedMethodsArgNames[key] as string[];

  return Object.fromEntries(argNames.map((name, index) => [name, structuredClone(args[index])])) as Record<string, unknown>;
}

type ConnectionIdResolver = () => string;

const connectionQueues = new Map<string, CallablePromise<void>[]>();
const sessions = new Map<string, PrinterSession>();
let activeConnectionId: string | null = null;

function ensureQueue(connectionId: string) {
  let queue = connectionQueues.get(connectionId);
  if (!queue) {
    queue = [];
    connectionQueues.set(connectionId, queue);
  }

  return queue;
}

function createSession(connectionIdResolver: ConnectionIdResolver): PrinterSession {
  const session: Record<string, any> = {};
  const sessionProxy = session as PrinterSession;

  for (const key in wrappedMethodsArgNames) {
    session[key] = (...args: any[]) => {
      const connectionId = connectionIdResolver();
      const queue = ensureQueue(connectionId);
      const trailingLock = queue.pop();
      const lock = new CallablePromise<void>();
      queue.push(lock);

      const promise = Promise.resolve(trailingLock)
        .then(async () => {
          const mappedArgs = await Promise.resolve(mapArgs(key, args));
          const payload = {
            connectionId,
            ...mappedArgs,
          };
          return CapacitorThermalPrinterImplementation[key](payload);
        })
        .finally(() => {
          lock.resolve();
        });

      if (key === 'write') {
        return promise;
      }

      return sessionProxy;
    };
  }

  return sessionProxy;
}

function ensureSession(connectionId: string) {
  let session = sessions.get(connectionId);
  if (!session) {
    session = createSession(() => connectionId);
    sessions.set(connectionId, session);
  }

  ensureQueue(connectionId);

  return session;
}

function clearSession(connectionId: string) {
  sessions.delete(connectionId);
  const queue = connectionQueues.get(connectionId);
  if (queue) {
    for (const lock of queue) {
      lock.resolve();
    }
  }
  connectionQueues.delete(connectionId);
  if (activeConnectionId === connectionId) {
    activeConnectionId = sessions.size ? Array.from(sessions.keys())[0] ?? null : null;
  }
}

function resolveConnectionId(preferred?: string | null): string {
  if (preferred) {
    return preferred;
  }

  if (activeConnectionId) {
    return activeConnectionId;
  }

  if (sessions.size === 1) {
    return Array.from(sessions.keys())[0];
  }

  throw new Error('No active printer connection. Set an active connection or pass a connectionId.');
}

const defaultSession = createSession(() => resolveConnectionId());

const CapacitorThermalPrinter = Object.assign(defaultSession as CapacitorThermalPrinterPlugin, {
  async connect(options: { address: string; encoding?: string }) {
    // Default to GBK encoding for best Chinese character support
    const connectOptions = {
      address: options.address,
      encoding: options.encoding || 'GBK',
    };
    const result = (await CapacitorThermalPrinterImplementation.connect(connectOptions)) as PrinterConnection | null;
    if (result) {
      ensureSession(result.connectionId);
      if (!activeConnectionId) {
        activeConnectionId = result.connectionId;
      }
    }

    return result;
  },
  async disconnect(options?: DisconnectOptions) {
    const connectionId = resolveConnectionId(options?.connectionId ?? null);
    await CapacitorThermalPrinterImplementation.disconnect({ connectionId });
    clearSession(connectionId);
  },
  async isConnected(options?: IsConnectedOptions) {
    const payload: Record<string, unknown> = {};
    if (options?.connectionId) {
      payload.connectionId = options.connectionId;
    }
    const { state } = await CapacitorThermalPrinterImplementation.isConnected(payload);
    return Boolean(state);
  },
  async listConnections() {
    const { connections } = (await CapacitorThermalPrinterImplementation.listConnections()) as {
      connections: PrinterConnection[];
    };

    for (const connection of connections) {
      ensureSession(connection.connectionId);
    }

    if (!activeConnectionId && connections.length > 0) {
      activeConnectionId = connections[0].connectionId;
    }

    return { connections };
  },
  useConnection(connectionId: string) {
    const session = ensureSession(connectionId);
    return session;
  },
  setActiveConnection(connectionId: string | null) {
    if (connectionId === null) {
      activeConnectionId = null;
      return;
    }

    if (!sessions.has(connectionId)) {
      throw new Error(`Unknown connectionId: ${connectionId}`);
    }

    activeConnectionId = connectionId;
  },
  getActiveConnection() {
    return activeConnectionId;
  },
  startScan: (...args: Parameters<typeof CapacitorThermalPrinterImplementation.startScan>) =>
    CapacitorThermalPrinterImplementation.startScan(...args),
  stopScan: (...args: Parameters<typeof CapacitorThermalPrinterImplementation.stopScan>) =>
    CapacitorThermalPrinterImplementation.stopScan(...args),
  addListener: CapacitorThermalPrinterImplementation.addListener.bind(CapacitorThermalPrinterImplementation),
}) as CapacitorThermalPrinterPlugin;

// Keep internal bookkeeping in sync with native events.
CapacitorThermalPrinterImplementation.addListener('connected', (device: PrinterConnection) => {
  ensureSession(device.connectionId);
  if (!activeConnectionId) {
    activeConnectionId = device.connectionId;
  }
});

CapacitorThermalPrinterImplementation.addListener(
  'disconnected',
  (data: { connectionId: string | undefined }) => {
    if (!data?.connectionId) {
      return;
    }
    clearSession(data.connectionId);
  },
);

export * from './definitions';
export { CapacitorThermalPrinter };
