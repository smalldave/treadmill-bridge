# FitPro1 Protocol Specification

Reverse-engineered from `Sindarin.FitPro1.Core.dll`, `Sindarin.Core.dll`, and
`Sindarin.Usb.Android.dll` decompiled from the iFit wolf APK. Describes the
USB protocol between the Android tablet and the motor controller on
NordicTrack/ProForm iFit treadmills.

## USB Device

| Property | Value |
|----------|-------|
| Vendor ID | 8508 |
| Product ID | 2 (`UsbProduct.FitPro1`) |
| Interface class | 3 (HID) |
| Subclass | 0 |
| Endpoint 0 | IN, addr=129, max packet=64 (read) |
| Endpoint 1 | OUT, addr=2, max packet=64 (write) |

Endpoints are assigned by **index** (0=read, 1=write), not by direction flag.

## Transport

### Handshake (ClearBuffer)

Clears stale data from the USB buffer before sending commands.

**Method:** `UsbRequest.Queue()` + `RequestWaitAsync()` (async USB request API)

**Procedure:**
1. Send 64 bytes of `0xFF`
2. Read 64 bytes
3. Verify: all bytes == `0xFF` except byte[3] (wildcard)
4. Repeat until 2 consecutive matches (max 10 attempts, 500ms between)

### Commands

**Method:** `BulkTransferAsync()` (Android USB bulk transfer)

**Write:** `bulkTransfer(writeEndpoint, data, data.length, 50)`
- Sends exact message size (wrapper + command), NOT padded to 64

**Read:** `bulkTransfer(readEndpoint, buffer, 64, 50)`
- Always reads 64 bytes
- Retries if `buffer[0] == 0xFF` (up to 5 times, 50ms timeout each)

**Read delay:** Between write and read, wait the command's `ReadDelayMs`:
- ReadWriteData: 80ms
- DeviceInfo: 300ms
- Connect: no read (fire-and-forget)

## Message Framing

Two layers for USB transport:

### Layer 1: FitPro Command

```
Byte  Field
[0]   Device ID
[1]   Total length (ContentLength + 4)
[2]   Command ID
[3..N-2]  Content (command-specific)
[N-1]  Checksum
```

**Checksum:** `sum(bytes[0] .. bytes[Length-2]) & 0xFF`

### Layer 2: FitProCommunication Wrapper

```
Byte  Field
[0]   0x02
[1]   0x04
[2]   0x02
[3]   Length of FitPro command
[4..] FitPro command bytes (Layer 1)
```

**USB sends Layer 1 directly — NO wrapper.** Verified on hardware: wrapped
commands return `SecurityBlock (8)`, raw commands return `Done (2)`.

The wrapper is BLE-only. The decompiled code shows wolf's USB adapter sending
`OriginalBytes` which includes the wrapper, but the actual hardware rejects it.
This may be a firmware version difference or the decompiled code path may not
be the one actually executed at runtime.

(BLE transport adds the wrapper plus a third layer of 20-byte chunking with
`[0xFE, 0x02, len, count]` init frames — neither is used for USB.)

### Response Framing

**Responses have NO wrapper.** The motor controller returns raw FitPro bytes:

```
[Device][Length][CmdID][Status][...data...][Checksum]
```

The `[0x02, 0x04, 0x02, len]` wrapper is **TX-only**. Verified from decompiled code:
- `FitProUsbConsoleCommunicationAdapter.DataReceived()` stores raw bytes (line 2976)
- `FormattedBytes` for USB returns raw `ResponseBytes` without stripping (line 2614-2618)
- `CleanResponse()` works directly on `bytes[0]=Device` (line 162-202)

Note: the response Device may differ from the request Device. Request uses
`Device.Main (2)`, response may come back as `Device.Treadmill (4)`.

**Verified against real hardware:** Response `04 05 02 04 0F` decodes as
Device=Treadmill(4), Len=5, Cmd=ReadWriteData(2), Status=Failed(4), Checksum=0x0F (valid).

## Enums

### Device

```
None = 0
MultipleDevices = 1
Main = 2
Portal = 3
Treadmill = 4
InclineTrainer = 5
Elliptical = 6
FitnessBike = 7
SpinBike = 8
VerticalElliptical = 9
Vibration = 10
StairClimber = 11
Skier = 12
Rower = 20
```

Default for commands: `Device.Main = 2`

### Command

```
None = 0
PortalDevListen = 1
ReadWriteData = 2
Test = 3
Connect = 4
Calibrate = 6
DeviceInfo = 129 (0x81)
VerifySecurity = 144 (0x90)
Raw = 255 (0xFF)
```

### CmdStatus

```
DevNotSupported = 0
CmdNotSupported = 1
Done = 2
InProgress = 3
Failed = 4
TimeLeft = 5
UnknownFailure = 7
SecurityBlock = 8
CommFailed = 9
```

Success = `Done (2)`.

## Commands

### Connect

Fire-and-forget (no response expected).

```
Request: [02] [04] [04] [0A]
         dev  len  cmd  chk
```

### DeviceInfo

Returns device capabilities: library version, BLE version, config tool version.

```
Request: [02] [04] [81] [87]
         dev  len  cmd  chk

ReadDelay: 300ms

Response (inner, after stripping wrapper):
  [Device] [Length] [0x81] [Status]
  [MasterLibraryVersion: 1 byte]
  [MasterLibraryBuild: 2 bytes LE]
  [IconBleLibVersion: 17 bytes UTF-8, 0x00 padded]
  [ConfigToolVersion: 1 byte]
  [BleSdkVersion: 2 bytes LE]
  [Checksum]
```

### ReadWriteData

Reads and/or writes BitField values. The primary command for telemetry.

```
Request content:
  [NumWriteSections] [WriteBitmask bytes...] [WriteData bytes...]
  [NumReadSections]  [ReadBitmask bytes...]

Full request:
  [Device=2] [Length] [Cmd=2] [Content...] [Checksum]

ReadDelay: 80ms

Response content:
  [Status=2]
  [NumWriteSections (echo)]
  [NumReadSections] [ReadBitmask bytes...]
  [ReadData bytes...]
  [Checksum]
```

### VerifySecurity

Security hash exchange. Required during initialization.

```
Request content: 32-byte security hash
Hash calculation: CalculateSecurityHash(serialNumber, partNumber, modelNumber)
```

## BitField Bitmask Encoding

BitFields are numbered 0-255. The bitmask is organized by sections:

- **Section** = BitField ID / 8
- **Bit** = BitField ID % 8
- **NumSections** = highest section index + 1

Each section is 1 byte. A set bit means the field is requested.

**Data follows in BitField order**, using the converter's byte size for each field.

### Example: Read ActualKph (16) and ActualIncline (17)

```
ActualKph = 16:    section 2, bit 0
ActualIncline = 17: section 2, bit 1

NumReadSections = 3  (sections 0, 1, 2)
Section 0 = 0x00     (no fields)
Section 1 = 0x00     (no fields)
Section 2 = 0x03     (bits 0 and 1 set)

Content: [00] [03] [00] [00] [03]
         wSec rSec sec0 sec1 sec2
```

Response data will contain 2 bytes (ActualKph) + 2 bytes (ActualIncline).

## BitField Reference

### Commonly Used

| ID | Name | Converter | Bytes | RO | Description |
|----|------|-----------|-------|----|-------------|
| 0 | Kph | Speed | 2 | no | Target speed |
| 1 | Grade | Grade | 2 | no | Target incline |
| 2 | Resistance | Resistance | 2 | no | Target resistance |
| 3 | Watts | Short | 2 | yes | Current watts |
| 4 | CurrentDistance | Int | 4 | yes | Current distance |
| 5 | Rpm | Short | 2 | yes | Current RPM |
| 7 | KeyObject | KeyObj | ? | yes | Button presses |
| 8 | FanSpeed | Byte | 1 | no | Fan speed |
| 10 | Pulse | Pulse | ? | no | Heart rate |
| 11 | RunningTime | Int | 4 | yes | Running time |
| 12 | WorkoutMode | Mode | ? | no | Current mode |
| 13 | Calories | Calories | ? | yes | Calories burned |
| 16 | ActualKph | Speed | 2 | yes | Current speed |
| 17 | ActualIncline | Grade | 2 | yes | Current incline |
| 18 | ActualResistance | Resistance | 2 | yes | Current resistance |
| 20 | CurrentTime | Int | 4 | yes | Current time |
| 27 | MaxGrade | Grade | 2 | yes | Max incline |
| 28 | MinGrade | Grade | 2 | yes | Min incline |
| 30 | MaxKph | Speed | 2 | yes | Max speed |
| 31 | MinKph | Speed | 2 | yes | Min speed |
| 42 | MaxResistanceLevel | Byte | 1 | yes | Max resistance |
| 58 | KphGoal | Speed | 2 | no | Speed goal |
| 59 | GradeGoal | Grade | 2 | no | Incline goal |

### Full Enum (excerpt)

```
Kph = 0, Grade = 1, Resistance = 2, Watts = 3,
CurrentDistance = 4, Rpm = 5, Distance = 6, KeyObject = 7,
FanSpeed = 8, Volume = 9, Pulse = 10, RunningTime = 11,
WorkoutMode = 12, Calories = 13, AudioSource = 14, LapTime = 15,
ActualKph = 16, ActualIncline = 17, ActualResistance = 18,
ActualDistance = 19, CurrentTime = 20, CurrentCalories = 21,
GoalTime = 22, IntervalKph = 23, Age = 24, Weight = 25,
Gear = 26, MaxGrade = 27, MinGrade = 28, TransMax = 29,
MaxKph = 30, MinKph = 31, IdleTimeout = 34, PauseTimeout = 35,
SystemUnits = 36, Gender = 37, FirstName = 38, LastName = 39,
UserName = 40, Height = 41, MaxResistanceLevel = 42, MaxWeight = 43,
WtMaxKph = 51, AverageGrade = 52, WtMaxGrade = 53,
AverageWatts = 54, MaxWatts = 55, AverageRpm = 56, MaxRpm = 57,
KphGoal = 58, GradeGoal = 59, ResistanceGoal = 60, WattGoal = 61,
RpmGoal = 63, DistanceGoal = 64, PulseGoal = 65,
StartUpTime = 66, BeltTotalTime = 67, BeltTotalMeters = 68,
MotorTotalDistance = 69, TotalTime = 70
```

## Data Converters

### SpeedConverter (2 bytes)

```
Encode: uint16_LE = (int)(kph * 100)
Decode: kph = uint16_LE / 100.0

Examples:
  5.00 km/h = 500  = [F4 01]
  10.50 km/h = 1050 = [1A 04]
  0.00 km/h = 0    = [00 00]
```

### GradeConverter (2 bytes, signed)

```
Encode: int16_LE = (int)(pct * 100)
Decode: pct = int16_LE / 100.0

Examples:
  3.50%  = 350   = [5E 01]
  -3.00% = -300  = [D4 FE]
  0.00%  = 0     = [00 00]
  15.00% = 1500  = [DC 05]
```

### IntConverter (4 bytes)

```
Encode/Decode: int32_LE
```

### ShortConverter (2 bytes)

```
Encode/Decode: uint16_LE
```

### ByteConverter (1 byte)

```
Encode/Decode: single byte
```

### StringConverter (45 bytes max)

```
Encode/Decode: UTF-8, trimmed
```

## Response Validation

A valid response must satisfy:
1. `bytes[0] != 0` — Device can't be None
2. `bytes[1] <= 64` — Length within packet size
3. `bytes[2]` — matches the Command ID that was sent
4. `bytes[bytes[1]-1]` — matches calculated checksum
5. `bytes[3] == 2` — CmdStatus.Done

## Initialization Sequence

1. Claim USB device (VID=8508, PID=2)
2. ClearBuffer — 0xFF handshake
3. Connect command (fire-and-forget)
4. DeviceInfo command → capabilities
5. SystemInfo command → part/serial numbers
6. VersionInfo command → firmware version
7. VerifySecurity → hash exchange
8. Begin ReadWriteData polling (~100-200ms interval)

## Worked Example

### Read current speed and incline

**Build FitPro command:**
```
Content: [00] [03] [00] [00] [03]
         wSec rSec sec0 sec1 sec2

         wSec=00: no write sections
         rSec=03: 3 read sections
         sec0=00: no fields in section 0 (BitFields 0-7)
         sec1=00: no fields in section 1 (BitFields 8-15)
         sec2=03: fields 16+17 (ActualKph + ActualIncline)

Full command:
  [02] [09] [02] [00] [03] [00] [00] [03] [13]
  dev  len  cmd  <-- content -->            chk

  Length = 4 + 5 = 9
  Checksum = (02+09+02+00+03+00+00+03) & 0xFF = 0x13
```

**Wrap for USB:**
```
  [02] [04] [02] [09]  [02] [09] [02] [00] [03] [00] [00] [03] [13]
  <-- wrapper -->       <-- FitPro command -->
```

**Send:** `bulkTransfer(writeEndpoint, data, 13, 50)`

**Wait:** 80ms (ReadWriteData read delay)

**Read:** `bulkTransfer(readEndpoint, buf, 64, 50)`, retry if `buf[0] == 0xFF`

**Parse response:**
```
Strip wrapper (first 4 bytes) → FitPro response:
  [Device] [Length] [02] [Status=02] [wSec=00] [rSec=03] [00] [00] [03]
  [SpeedLo] [SpeedHi] [InclineLo] [InclineHi] [Checksum]

Speed = uint16_LE(SpeedLo, SpeedHi) / 100.0
Incline = int16_LE(InclineLo, InclineHi) / 100.0
```

### Set target speed to 8.0 km/h

```
BitField 0 (Kph): section 0, bit 0
Value: 800 = [20 03]

Content: [01] [01] [20] [03] [00]
         wSec wBit data data  rSec

Full command:
  [02] [09] [02] [01] [01] [20] [03] [00] [checksum]

Wrapped:
  [02] [04] [02] [09]  [02] [09] [02] [01] [01] [20] [03] [00] [checksum]
```
