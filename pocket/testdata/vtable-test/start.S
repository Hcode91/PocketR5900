    .set noreorder
    .section .text.start,"ax"
    .globl _start
_start:
    lui   $sp, 0x01f0
    jal   ctors
    nop
    jal   bare_main
    nop
1:  b 1b
    nop
