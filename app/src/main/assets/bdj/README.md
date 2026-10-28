# BD-J runtime assets

Blu-ray menus written in Java (BD-J) need a Java runtime and libbluray's own
jars. Neither is in the repository: put them in this folder before building.
Without them the app still builds and plays Blu-ray discs, only without
BD-J menus.

## Files

| File | What it is |
|------|------------|
| `jre8-<abi>.zip` | OpenJDK 8 JRE built for Android, one per ABI |
| `libbluray-j2se-<version>.jar` | libbluray's BD-J classes |
| `libbluray-awt-j2se-<version>.jar` | libbluray's AWT classes |

`<abi>` is `arm64-v8a`, `armeabi-v7a`, `x86_64` or `x86`. Only the ABIs
present get BD-J menus; the app checks for `jre8-<abi>.zip` matching the
device at start.

`<version>` must be the libbluray version the app is built with: the
jars and libbluray's native code go together.

Example:

```
app/src/main/assets/bdj/
├── jre8-arm64-v8a.zip
├── jre8-armeabi-v7a.zip
├── libbluray-j2se-1.4.1.jar
└── libbluray-awt-j2se-1.4.1.jar
```
