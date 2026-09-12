# CorePatch Zygisk

Zygisk/LSPlant port of CorePatch. The module does not depend on LSPosed or libxposed at runtime.

## Build

```bash
./gradlew :app:assembleRelease
./gradlew :app:zygiskModuleZip
```

The second task packages the APK and native Zygisk libraries into a Magisk module ZIP.

## Important

- Enable Zygisk in the root manager.
- Java hooks are installed through LSPlant from the Zygisk system-server process.
- The ZIP is packaged with stored entries (no DEFLATE) to avoid the archive-parser/CI issue seen with the previous artifact.
