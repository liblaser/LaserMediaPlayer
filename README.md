# Laser Media Player

Plays audio CDs, Video CDs, DVD-Video, DVD-Audio and Blu-ray discs from a USB
optical drive, on Android phones, boxes and Android TV, through libVLC 4 and
liblaser.

## Building

1. Build the libVLC AAR with libvlcjni from your VLC 4 tree (laser module,
   liblaser, libdvdcss, libdvdread, libdvdnav, libbluray, libaacs), and copy
   it to `app/libs/libvlc-release.aar`. libvlcjni's `libvlc/jni/libvlc.mk`
   must build `laser-jni.c` into `libvlc.so`: the app's calls to liblaser
   (`drive/LaserDrive.kt`).
2. For the Blu-ray menus, put the Java runtime and libbluray's jars in
   `app/src/main/assets/bdj/` (see the README there). Without them, Blu-ray
   discs play without their menus.
3. Open the project in Android Studio, or run `./gradlew assembleDebug`.

The libaacs provider APK (`org.libaacs.provider`) is found at run time, if
present. It can be built from this branch: https://code.videolan.org/Pierrebog/libaacs/-/tree/libaacs-android-apk

libaacs's key file, KEYDB.cfg, is chosen by the user with the
system's file picker - the file or a zip archive holding it - the first time
a protected Blu-ray plays, and copied into the app's private files; a newer
one is loaded from Options › Playback. No storage permission is asked for.

Discs are named online: audio CDs and DVD-Audio on MusicBrainz, with their
covers from the Cover Art Archive, and films on TMDB, with a key the user
enters in the options.

## Layout

```
app/src/main/java/io/github/pierrebog/lasermediaplayer/
  LaserApp.kt              process-wide objects
  MainActivity.kt          the home, player and options screens, switched
                           on state
  disc/Disc.kt             disc model, row MRLs, track lists from laser.c
  disc/Album.kt            an album's names, from CD-Text or MusicBrainz
  disc/MusicBrainz.kt      MusicBrainz and Cover Art Archive lookups
  disc/Film.kt             the film on a video disc, from TMDB
  disc/ExtraRoots.kt       Let's Encrypt roots for Android before 7.1.1
  drive/UsbDrives.kt       candidate drives (sysfs), USB permission
  drive/DriveRepository.kt drives and their discs, classification passes,
                           following the discs
  drive/DriveConnections.kt  each drive's one connection
  drive/LaserDrive.kt      liblaser's media events, tray lock and eject
  vlc/VlcEngine.kt         the LibVLC instance and its options
  vlc/DiscClassifier.kt    browse of laser-dir://<fd>
  vlc/AlbumLookup.kt       names an audio CD or a DVD-Audio
  vlc/NativeEnvironment.kt libdvdcss, libdvdread, libaacs and BD-J
                           environment
  vlc/BdjRuntime.kt        the BD-J Java runtime, unpacked from the assets
  settings/                the options and the default track languages
  storage/AacsKeys.kt      libaacs's key file, copied from the user's choice
  resume/ResumeStore.kt    resume points and track choices, per disc
  ui/home/                 disc screen, states, track sheet
  ui/player/               audio and video players, their libVLC calls
  ui/options/              options, about and credits screens
```

## Flow

1. A drive is plugged: `DriveRepository` asks for the USB permission, opens
   the drive's connection - kept until it is unplugged - and
   `DiscClassifier` browses `laser-dir://<fd>`. laser.c returns one item per
   way of playing the disc, with its title and, for a video disc, its audio
   and subtitle tracks as extra metadata.
2. While the app is shown, each drive is polled every 1.5 s for what
   happened to its disc (`LaserDrive.poll`): a disc put in is classified, one
   taken out leaves "no disc".
3. The disc screen shows the title, the resume point and the tracks, and one
   button per way of playing it.
4. Play: `PlayerController` puts the connection's descriptor in the row's
   MRL and plays it, the tray locked. The eject button stops playback and
   opens the tray. On exit, it saves the resume point, stops libVLC, and
   unlocks the tray once libVLC has stopped.

## License

GPL version 2 or later: see `COPYING`. The APK also carries libVLC and the
libraries linked into it, and AndroidX and Kotlin's (Apache 2.0); Options ›
About lists them with their licences (`ui/options/Credits.kt`, kept by hand
in step with the libVLC build).

For the Blu-ray menus, the APK carries a Java runtime per ABI
(`app/src/main/assets/bdj/jre8-<abi>.zip`): the Android port of OpenJDK 8
(GPLv2 with the Classpath Exception) built at
https://github.com/AngelAuraMC/angelauramc-openjdk-build/commit/62359f04413c3ffe339e652b88a96d9087840cbf,
prepared by `bdj-jre-tools/prepare-bdj-jre.sh` - which also builds the
FreeType it adds (FreeType License or GPLv2) - and libbluray's BD-J jars
(LGPLv2.1 or later).
