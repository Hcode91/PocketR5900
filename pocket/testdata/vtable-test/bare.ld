ENTRY(_start)
SECTIONS {
  . = 0x100000;
  .text : { *(.text.start) *(.text*) }
  .rodata : { *(.rodata*) }
  .init_array : { __init_array_start = .; KEEP(*(.init_array*)) __init_array_end = .; }
  .data : { *(.data*) *(.sdata*) }
  .bss : { *(.sbss*) *(.bss*) *(COMMON) }
  /DISCARD/ : { *(.eh_frame*) *(.comment) *(.MIPS.abiflags) }
}
