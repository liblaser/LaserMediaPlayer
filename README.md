# Laser Media Player

Plays audio CDs, Video CDs, DVD-Video, DVD-Audio and Blu-ray discs from a USB
optical drive, on Android (6.0 or later) phones, boxes and Android TV, through
libVLC 4 and liblaser.

> **Legal notice:** Laser Media Player is meant to play physical discs **you own**. 
> Handling copy-protected discs (CSS / AACS) may be restricted by law where you live, 
> please check them before using this application.
> If you want to watch commercial Blu-ray discs, you must provide your own AACS 
> decryption library and decryption keys files since neither are provided by this project.

## Screenshots
<table>
  <tr>
    <td><img src="https://i.postimg.cc/p2zYsRNp/Screenshot-20261106-213903.png" alt="cdda detection" width="400" /></td>
    <td><img src="https://i.postimg.cc/pWvQ74xx/Screenshot-20261106-213921.png" alt="cdda playback" width="400" /></td>
  </tr>
  <tr>
    <td><img src="https://i.postimg.cc/B45p02Mt/Screenshot-20261106-220835.png" alt="blu-ray detection" width="400" /></td>
    <td><img src="https://i.postimg.cc/HYm0KRHW/Screenshot-20261106-214500.png" alt="blu-ray playback" width="400" /></td>
  </tr>
</table>

## User's manual

### Playing a disc

**Plug the drive in.** Connect a USB CD, DVD or Blu-ray drive - on a phone,
through an OTG adapter - and grant the USB permission Android asks for. The
disc in it is identified, which takes a few seconds, more for a Blu-ray, then
the disc screen shows its title and controls to play it.

**Blu-rays.** A commercial Blu-ray is protected by AACS and needs:

- the libaacs provider app (`org.libaacs.provider`), installed separately
  (see *Building*);
- libaacs's key file, `KEYDB.cfg`, which the app asks for the first time a
  protected disc plays: choose the file, or a zip archive holding it. A newer
  one is loaded from *Options › Playback*.

Neither is provided by this project. Please check your local laws before
installing libaacs.

### Troubleshooting

**If nothing happens when you plug the drive in** - no permission dialog, no
disc - the usual cause is power. An optical drive draws more than most phones
and TV boxes supply over USB: use a powered USB hub, a Y-cable with its own
supply, or the drive's own power input if it has one.

**If a disc stutters,** suspect the power again first: an underpowered drive
silently retries its reads.

**If a disc does not play properly** - no picture, a frozen, garbled or
stuttering picture, playback stopping with an error - try turning off
hardware decoding for that kind of disc, in *Options › Playback*. Some
devices' decoders handle the video of a Video CD, a DVD or a Blu-ray badly;
the app then decodes it itself, at the cost of more battery and CPU.

**"Disc not identified"** means the drive did not answer in time. Retry, take
the disc out and put it back, or unplug the drive and plug it in again.

**"This Blu-ray could not be decrypted"** means the key file has no key for
that disc: load a newer `KEYDB.cfg` in *Options › Playback*.

**To report a problem,** please include the log, ideally after replicating
the issue with the debug pak. I recommend Logcat Extreme for log collection:
https://play.google.com/store/apps/details?id=scd.lcex

## Building

1. Build the libVLC AAR with libvlcjni from your VLC 4 tree (laser module,
   liblaser, libdvdcss, libdvdread, libdvdnav, libbluray, libaacs), and copy
   it to `app/libs/libvlc-release.aar`. libvlcjni's `libvlc/jni/libvlc.mk`
   must build `laser-jni.c` into `libvlc.so`: the app's calls to liblaser
   (`drive/LaserDrive.kt`).
2. For the Blu-ray menus, copy libbluray's three dex jars from the contrib's
   `share/java/` to `app/src/main/assets/bdj/` (see the README there).
   Without them, Blu-ray discs play without their menus.
3. Open the project in Android Studio, or run `./gradlew assembleDebug`.

The libaacs provider APK (`org.libaacs.provider`) can be built from this
branch: https://code.videolan.org/Pierrebog/libaacs/-/tree/org-libaacs-provider

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
  vlc/BdjRuntime.kt        libbluray's BD-J jars, copied from the assets
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
About lists them with their licences.

For the Blu-ray menus, the APK carries a dexer, AOSP's dx 1.16 (Apache 2.0),
as kept for libbluray atnhttps://github.com/liblaser/bdj-dx.
