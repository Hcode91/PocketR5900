PocketR5900-PC
==============

Converts a PS2 game you own on a Windows PC, for the PocketR5900 app on
Android. It does the same steps as the phone (read the disc, analyse,
recompile, compile, link), using all of the PC's processor cores, and writes
one .pr5900 file.

Use
---
1. Unzip the whole folder somewhere and run PocketR5900-PC.exe.
   The first conversion downloads Google's Android NDK (about 700 MB) into
   %LOCALAPPDATA%\PocketR5900; later ones reuse it.
2. Browse to your own dump: a .cue (with its .bin next to it), a .bin, an
   .iso, or a PS2 ELF.
3. Pick "Faster conversion" or "Faster game" and press Convert.
4. Copy the .pr5900 file to your phone and open it with "Add a game" in
   PocketR5900. It is ready to play straight away, and "Export as APK" works
   as for any other game.

If a game's disc is already on the phone, untick "Include the disc" for a much
smaller file; the phone uses the disc it has.

Stopping and pressing Convert again carries on where it stopped. The full log
of each conversion is in %LOCALAPPDATA%\PocketR5900\work\<game>\convert.log.

Command line (from a Command Prompt in this folder):
    PocketR5900-PC-cli.exe --cli GAME [--fast-game] [--out FOLDER] [--no-disc] [--title TITLE]

Folder contents
---------------
PocketR5900-PC.exe    the converter (window)
PocketR5900-PC-cli.exe  the same, for the command line
tools\                the PS2Recomp analyzer and recompiler (Windows builds)
sdk.zip, sdk.version  Android sysroot and the PS2 runtime, as used on the phone

PocketR5900 is GPL-3.0, based on PS2Recomp.
