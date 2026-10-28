package io.github.pierrebog.lasermediaplayer.ui.options

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.pierrebog.lasermediaplayer.R

/** The licences of the libraries, named in the interface's language. */
enum class Licence {
    GPL2_PLUS, GPL2_CLASSPATH, LGPL21_PLUS, LGPL21, LGPL2_PLUS, LGPL3_OR_GPL2,
    MIT, BSD2, BSD3, ZLIB, LIBPNG, FREETYPE, GSM, APACHE2, OFL,
}

@Composable
fun licenceLabel(licence: Licence): String = when (licence) {
    Licence.GPL2_PLUS -> stringResource(R.string.lic_or_later, "GPLv2")
    Licence.GPL2_CLASSPATH -> "GPLv2 + Classpath"
    Licence.LGPL21_PLUS -> stringResource(R.string.lic_or_later, "LGPLv2.1")
    Licence.LGPL21 -> "LGPLv2.1"
    Licence.LGPL2_PLUS -> stringResource(R.string.lic_or_later, "LGPLv2")
    Licence.LGPL3_OR_GPL2 -> stringResource(R.string.lic_either, "LGPLv3+", "GPLv2+")
    Licence.MIT -> stringResource(R.string.lic_named, "MIT")
    Licence.BSD2 -> stringResource(R.string.lic_bsd, 2)
    Licence.BSD3 -> stringResource(R.string.lic_bsd, 3)
    Licence.ZLIB -> stringResource(R.string.lic_named, "zlib")
    Licence.LIBPNG -> stringResource(R.string.lic_named, "libpng")
    Licence.FREETYPE -> stringResource(R.string.lic_named, "FreeType")
    Licence.GSM -> stringResource(R.string.lic_named, "GSM")
    Licence.APACHE2 -> stringResource(R.string.lic_named, "Apache 2.0")
    Licence.OFL -> stringResource(R.string.lic_named, "SIL Open Font")
}

/** A library in the APK: its name, its copyright notice, its licence. */
data class Library(val name: String, val copyright: String, val licence: Licence)

/**
 * What the About screen credits, written by hand: update it with the
 * libVLC build. The libraries are those whose code is linked into
 * libvlc.so by build-libvlc.sh, as its symbols show, and those of the app.
 */
object Credits {
    const val YEAR = "2026"
    const val AUTHOR = "Pierre Bogdanovscky"
    /* The app's public repository: its source, as the GPL asks, and the
     * contact the online lookups give. */
    const val SOURCE = "https://github.com/liblaser/LaserMediaPlayer"

    const val LIBVLC_VERSION = "4.0.0-dev"
    const val LIBLASER_VERSION = "0.2.1"
    /* Vendored in liblaser. */
    const val LIBUSB_VERSION = "1.0.30"
    /* The Java runtime for the Blu-ray menus (BD-J), in the assets: the
     * Android port of OpenJDK 8 built by angelauramc-openjdk-build at
     * OPENJDK_SOURCE - its source, as GPLv2 asks - prepared by the app's
     * bdj-jre-tools. */
    const val OPENJDK_VERSION = "8"
    const val OPENJDK_SOURCE = "https://github.com/AngelAuraMC/angelauramc-openjdk-build/" +
        "commit/62359f04413c3ffe339e652b88a96d9087840cbf"

    /** The other libraries in libvlc.so, liblaser's vendored libudfread
     * included. */
    val inLibvlc = listOf(
        Library("AOM", "Copyright © 2016, Alliance for Open Media", Licence.BSD2),
        Library("dav1d", "Copyright © 2018-2025, VideoLAN and dav1d authors", Licence.BSD2),
        Library("FFmpeg", "Copyright © 2000-2026 the FFmpeg developers", Licence.LGPL21_PLUS),
        Library("FLAC", "Copyright © 2000-2009 Josh Coalson, © 2011-2025 Xiph.Org Foundation",
                Licence.BSD3),
        Library("Fontconfig", "Copyright © 2000, 2001, 2002, 2003, 2004, 2006, 2007 Keith Packard, " +
                "© 2005 Patrick Lam, © 2009 Roozbeh Pournader, © 2008, 2009 Red Hat, Inc., " +
                "© 2008 Danilo Šegan, © 2012 Google, Inc.", Licence.MIT),
        Library("FreeType", "Copyright © The FreeType Project (www.freetype.org)", Licence.FREETYPE),
        Library("GNU FriBidi", "Copyright © the GNU FriBidi authors", Licence.LGPL21_PLUS),
        Library("ggml", "Copyright © 2023-2026 The ggml authors", Licence.MIT),
        Library("glslang", "Copyright © The Khronos Group Inc., Google Inc. and the glslang authors",
                Licence.BSD3),
        Library("GMP", "Copyright © Free Software Foundation, Inc.", Licence.LGPL3_OR_GPL2),
        Library("GnuTLS", "Copyright © Free Software Foundation, Inc. and the GnuTLS authors",
                Licence.LGPL21_PLUS),
        Library("GSM", "Copyright © 1992, 1993, 1994 Jutta Degener and Carsten Bormann, " +
                "Technische Universität Berlin", Licence.GSM),
        Library("LAME", "Copyright © the LAME developers", Licence.LGPL2_PLUS),
        Library("libaribcaption", "Copyright © 2022 magicxqq", Licence.MIT),
        Library("libbluray", "Copyright © the libbluray authors", Licence.LGPL21_PLUS),
        Library("libdvbcsa", "Copyright © the libdvbcsa authors", Licence.GPL2_PLUS),
        Library("libdvbpsi", "Copyright © VideoLAN and the libdvbpsi authors", Licence.LGPL21_PLUS),
        Library("libdvdcss", "Copyright © VideoLAN and the libdvdcss authors", Licence.GPL2_PLUS),
        Library("libdvdnav", "Copyright © the libdvdnav authors", Licence.GPL2_PLUS),
        Library("libdvdread", "Copyright © the libdvdread authors", Licence.GPL2_PLUS),
        Library("libebur128", "Copyright © 2011 Jan Kokemüller", Licence.MIT),
        Library("libgcrypt", "Copyright © Free Software Foundation, Inc. and g10 Code GmbH",
                Licence.LGPL21_PLUS),
        Library("libgpg-error", "Copyright © Free Software Foundation, Inc. and g10 Code GmbH",
                Licence.LGPL21_PLUS),
        Library("libiconv", "Copyright © Free Software Foundation, Inc.", Licence.LGPL21_PLUS),
        Library("libmysofa", "Copyright © 2016-2017, Symonics GmbH, Christian Hoene", Licence.BSD3),
        Library("libnoidea", "Copyright © VideoLAN and the libnoidea authors", Licence.LGPL21_PLUS),
        Library("libogg", "Copyright © 2002, Xiph.org Foundation", Licence.BSD3),
        Library("libplacebo", "Copyright © Niklas Haas and the libplacebo authors", Licence.LGPL21_PLUS),
        Library("libpng", "Copyright © 1995-2026 The PNG Reference Library Authors", Licence.LIBPNG),
        Library("libpostproc", "Copyright © Michael Niedermayer and the FFmpeg developers",
                Licence.GPL2_PLUS),
        Library("librist", "Copyright © the librist authors", Licence.BSD2),
        Library("libsoxr", "Copyright © 2007-2013 Rob Sykes", Licence.LGPL21_PLUS),
        Library("libspatialaudio", "Copyright © Videolabs and the libspatialaudio authors",
                Licence.LGPL21_PLUS),
        Library("libssh2", "Copyright © 2004-2007 Sara Golemon, © 2009-2023 Daniel Stenberg " +
                "and the libssh2 contributors", Licence.BSD3),
        Library("libtheora", "Copyright © 2002-2009 Xiph.org Foundation", Licence.BSD3),
        Library("libudfread", "Copyright © Petri Hintukainen and the libudfread authors",
                Licence.LGPL21_PLUS),
        Library("libxml2", "Copyright © 1998-2012 Daniel Veillard, © The Libxml2 Contributors",
                Licence.MIT),
        Library("mpg123", "Copyright © 1995-2026 Michael Hipp and the mpg123 project", Licence.LGPL21),
        Library("Nettle", "Copyright © Niels Möller and the Nettle authors", Licence.LGPL3_OR_GPL2),
        Library("OpenAPV", "Copyright © 2022 Samsung Electronics Co., Ltd.", Licence.BSD3),
        Library("OpenJPEG", "Copyright © 2002-2014, Université catholique de Louvain (UCL), " +
                "Belgium, and the OpenJPEG authors", Licence.BSD2),
        Library("RNNoise", "Copyright © 2017, Mozilla, © 2007-2017, Jean-Marc Valin, " +
                "© 2005-2017, Xiph.Org Foundation, © 2003-2004, Mark Borgerding", Licence.BSD3),
        Library("sam3.cpp", "Copyright © 2025-2026 Pierre-Antoine Bannier", Licence.MIT),
        Library("Speex", "Copyright © 2002-2008 Xiph.org Foundation, Jean-Marc Valin and others",
                Licence.BSD3),
        Library("zlib", "Copyright © Jean-loup Gailly and Mark Adler", Licence.ZLIB),
    )

    /** The libraries of the app itself, FreeType, built for the Java
     * runtime's fonts, and the app's fonts. */
    val inApp = listOf(
        Library("AndroidX, Jetpack Compose", "Copyright © The Android Open Source Project",
                Licence.APACHE2),
        Library("Kotlin", "Copyright © 2010-2026 JetBrains s.r.o. and Kotlin Programming " +
                "Language contributors", Licence.APACHE2),
        Library("kotlinx.coroutines", "Copyright © 2016-2026 JetBrains s.r.o. and contributors",
                Licence.APACHE2),
        Library("FreeType", "Copyright © The FreeType Project (www.freetype.org)", Licence.FREETYPE),
        Library("Instrument Sans", "Copyright © 2022 The Instrument Sans Project Authors",
                Licence.OFL),
        Library("Instrument Serif", "Copyright © 2022 The Instrument Serif Project Authors",
                Licence.OFL),
    )
}
