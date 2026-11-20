Virtual-call test for ELFs without section headers
==================================================

Two classes whose methods are only reached through their vtables, built as one
LOAD block with no section table (like many retail games). If the converter
misses a method, the game stops with `[guest-branch:missing-target]`; if all
are found it ends in `result_ok` (the tick log shows `ra=0x100188`;
`result_bad` would show `ra=0x100180`).

Build (ps2dev):

    mips64r5900el-ps2-elf-g++ -O2 -G0 -fno-devirtualize -fno-rtti -fno-exceptions \
        -nostdlib -nostartfiles -ffreestanding -T bare.ld start.S ctors.c bare.cpp -o bare.elf -lgcc
    mips64r5900el-ps2-elf-strip --strip-all --strip-section-headers bare.elf
