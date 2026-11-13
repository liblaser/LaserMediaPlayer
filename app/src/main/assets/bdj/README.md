# BD-J assets

Blu-ray menus written in Java (BD-J) run in the app's own ART, from
libbluray's jars. They are not in the repository: put them in this folder
before building. Without them the app still builds and plays Blu-ray discs,
only without BD-J menus.

## Files

libbluray's android target installs three jars, already converted to dex,
in `share/java/` of the VLC contrib's install prefix:

| File | What it is |
|------|------------|
| `libbluray-android-<version>.jar` | libbluray's BD-J classes |
| `libbluray-awt-android-<version>.jar` | libbluray's AWT classes |
| `libbluray-android-dexer-<version>.jar` | the dexer: converts a disc's classes to dex on the device (dx, from libbluray's `contrib/dx`) |

All three are needed: the app checks for them at start. They are the same
for every ABI.

`<version>` must be the libbluray version the app is built with: the
jars and libbluray's native code go together.

Example:

```
app/src/main/assets/bdj/
├── libbluray-android-1.4.1.jar
├── libbluray-awt-android-1.4.1.jar
└── libbluray-android-dexer-1.4.1.jar
```
