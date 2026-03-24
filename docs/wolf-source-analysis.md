# iFit Wolf Source Analysis

Reverse-engineered from decompiled Xamarin/.NET DLLs extracted from
`com.ifit.wolf` APK (version 2.6.86.4458) on a NordicTrack Commercial 2450
treadmill (Android 7, Malata MediatekArgon1 tablet).

## App Architecture

Three Android packages run on the treadmill tablet:

| Package | Role |
|---------|------|
| `com.ifit.standalone` | iFit UI (workouts, login, video). Loads wolf as an in-process library via Xamarin. |
| `com.ifit.wolf` | Workout engine. Talks to motor controller via USB. Runs inside standalone's process. |
| `com.ifit.eru` | Equipment Resource Unit. Manages USB permissions, firmware updates, crash reporting. Separate process. |

Standalone and wolf share a process — standalone calls wolf's .NET code
directly via Xamarin bindings. Wolf and ERU communicate via Android IPC
(`Messenger` + `Bundle` with JSON-serialized `IpcObject`).

## Decompiled DLLs

DLLs extracted from `assemblies/` inside the APK, LZ4-decompressed (XALZ
format), then decompiled with `ilspycmd`.

| DLL | Size | Role |
|-----|------|------|
| `Sindarin.FitPro1.Core.dll` | 133KB | FitPro1 protocol: commands, BitField enum, data converters, queue manager |
| `Sindarin.Core.dll` | 458KB | Connection abstraction, retry logic, fitness value facades (KphFacade, GradeFacade) |
| `Sindarin.Usb.Android.dll` | 28KB | Android USB transport: device claim, endpoint setup, bulk transfer, handshake |
| `Wolf.Core.dll` | 3.8MB | Business logic, workout management, logging, settings, analytics |
| `Wolf.Android.dll` | 2.3MB | Android-specific services, IPC handlers, platform integration |
| `Shire.Core.dll` | 2.0MB | Shared framework: IPC objects, communication adapters, UI abstractions |
| `Shire.Android.dll` | 506KB | Android IPC client/service base classes, broadcast receivers |

## USB Communication Code Path

Traced from USB device claim to speed data read. Line references are from
the decompiled `.cs` files.

### 1. Device Discovery and Claim

`BaseAndroidUsbDevice` (Sindarin.Usb.Android, line 36-332):
- Filters by VendorID=8508, ProductID=2 (`UsbProduct.FitPro1`)
- Requests USB permission via ERU broadcast (`com.ifit.eru.USB_PERMISSION_REQUEST`)
  or Android `UsbManager` fallback
- Claims interface 0 with `force: true`
- Stores `UsbConnection`, `UsbInterface`

### 2. Endpoint Setup

`UsbConsoleConnection.Connected()` (Sindarin.Usb.Android, line 405-420):
```
writeEndpoint = Device.UsbInterface.GetEndpoint(1)  // OUT, addr=2
readEndpoint  = Device.UsbInterface.GetEndpoint(0)  // IN, addr=129
```
Hardcoded by index, not by direction flag.

### 3. Buffer Clear (Handshake)

`UsbConsoleConnection.ClearBuffer()` (Sindarin.Usb.Android, line 436-486):
- Sends 64 bytes of `0xFF` via `UsbRequest.Queue()` + `RequestWaitAsync()`
- Reads 64-byte reply via same mechanism
- Expects reply to match (all `0xFF` except byte[3] which is wildcard)
- Requires 2 consecutive matches
- Up to 10 attempts with 500ms delay between

### 4. Console Initialization

`FitPro1Console.InitializeConsole()` (Sindarin.FitPro1.Core, line 355-415):
1. `DeviceInfoCmd(Device.Main)` — gets device capabilities, supported BitFields
2. Walks device tree (child devices)
3. `SystemInfoCmd()` — gets part number, serial number, model
4. `VersionInfoCmd()` — gets firmware version
5. `SerialNumberCmd()` — gets brainboard serial
6. `VerifySecurity()` — security hash exchange (SHA-based)
7. Sets up `ConsoleInfo` with capabilities

### 5. Command Execution

`QueueManager` (Sindarin.FitPro1.Core, line ~1400-1750):
- Commands are enqueued as `CommandCommItem<T>`
- `SendBatch()` calls `cmd.GetRequestBytes()` to build FitPro command
- `adapter.SendBytes(request, expectResponse, delay)` wraps and sends

`FitPro1Console.SendBytes()` (line 804-817):
- Creates `FitProCommunication(bytes)` — adds 4-byte wrapper: `[0x02, 0x04, 0x02, len]`
- Calls `Adapter.Fetch(comm)` to send through the adapter pipeline

`FitProUsbConsoleCommunicationAdapter.SendBytes()` (line 3273-3276):
- **Override**: sends `commGroup.OriginalBytes` (wrapped but NOT chunked)
- Base class would send `commGroup.RequestBytes` (chunked into 20-byte BLE frames)
- USB deliberately bypasses chunking

### 6. USB Transport

`RetryingConnection` (Sindarin.Core, line ~13790-13950):
- `SendBytesWithReadDelay()` creates `SendPacket(bytes, reply: true, sendAsBulkTransfer: true, delay)`
- `ReadWrite()` executes:
  1. Write via `DoBulkWriteAsync(bytes)` → `BulkTransferAsync(writeEndpoint, buffer, buffer.Length, 50)`
  2. Wait `ReadDelay` (command-specific: 80ms for ReadWriteData, 300ms for DeviceInfo)
  3. Read via `DoBulkReadAsync(new byte[64])` → `BulkTransferAsync(readEndpoint, buffer, 64, 50)`
     - Retries if `buffer[0] == 0xFF` (up to 5 times)
  4. Fires `whenValueUpdated.OnNext(replyData)`

### 7. Response Processing

`FitProUsbConsoleCommunicationAdapter.DataReceived()` (line 3278-3282):
- Receives raw 64-byte response
- Passes to `CommGroupResponseComplete()` which triggers response parsing

`FitProCommunication.RemoveBleBytes()` (line 2637-2641):
- Strips first 4 bytes (wrapper) from response

`CommandBase.SetResponseBytes()` (line 3475-3480):
- `Device = (Device)bytes[0]`
- `ResponseLength = bytes[1]`
- `Status = (CmdStatus)bytes[3]`

`ReadWriteDataCmd.SetResponseBytes()` (line 3665-3730):
- Validates status == `CmdStatus.Done` (2)
- Parses BitField data from response body
- Updates fitness values via `fitnessValueUpdater`

### 8. Value Change Notification

`FitnessConsoleBase` (Sindarin.Core, line ~12340-12380):
- When BitField values change, fires `Log.Trace("FitPro", "Changed KPH to: " + value)`
- These are the log messages the QZ companion app reads from wolflogs

## Logging

Wolf's startup sequence (Wolf.Core):
1. Initializes logger
2. Checks LaunchDarkly feature flags
3. Calls `"Disabling file logs"` — suppresses all file and logcat output
4. `Log.Trace()` calls still execute in-process but are silently dropped

This is why the QZ companion app can no longer read speed data from wolflogs
or logcat.

## IPC (Wolf-ERU)

`WolfIpcService` (Wolf.Android) / `IpcClient` (Shire.Android):
- Android `Messenger`-based IPC
- Messages carry JSON-serialized `IpcObject` in `Bundle` key `"IpcDataKey"`
- Method dispatch via `IpcMethodHandler` which discovers `[IpcMethod]`-attributed classes

Available IPC methods (Wolf.Android):
- `GetConsoleInfo` — hardware info (part number, firmware version)
- `GetConsoleState` — current state (Idle, Running, Paused, etc.)
- `GetAttachedConsoleInfo` — attached device info
- `GetCurrentScreenBrightness` / `SetScreenBrightness`
- `GetIsUpdateAvailable` / `SetTimeZone`

IPC does **not** expose workout telemetry (speed, incline, calories). That
data only exists in-process within wolf's fitness value facades.
