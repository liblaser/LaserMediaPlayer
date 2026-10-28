# libVLC

Put the libVLC AAR built by libvlcjni here, named `libvlc-release.aar`.

It must be built from a VLC 4 tree with the `laser` module and the
contribs it needs: liblaser, libdvdcss, libdvdread, libdvdnav and libbluray.

It can be built from those branches:
- https://code.videolan.org/Pierrebog/libvlcjni/-/tree/laser-media-player
- https://code.videolan.org/Pierrebog/vlc/-/tree/laser-media-player

The prebuilt `org.videolan.android:libvlc-all` from Maven does not have the
`laser` module and cannot be used.
